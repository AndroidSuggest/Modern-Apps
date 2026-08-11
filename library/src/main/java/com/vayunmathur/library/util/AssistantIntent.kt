package com.vayunmathur.library.util

import android.annotation.SuppressLint
import android.util.Log
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.core.content.IntentCompat

abstract class AssistantIntent<Input: Any, Output: Any>(val inputSerializer: KSerializer<Input>, val outputSerializer: KSerializer<Output>): ComponentActivity() {
    @OptIn(InternalSerializationApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Get the incoming data
        val inputString = intent.getStringExtra("DATA")
        if (inputString == null) {
            finish()
            return
        }
        val input = Json.decodeFromString(inputSerializer, inputString)

        lifecycleScope.launch {
            // 2. Do your "rare" processing
            val result = performCalculation(input)

            // 3. Prepare the response
            val responseData = Json.encodeToString(outputSerializer, result)
            val responseIntent = Intent()
            responseIntent.putExtra("RESPONSE_DATA", responseData)

            // 4. Send the result back to the calling app
            setResult(RESULT_OK, responseIntent)

            // Also send to ResultReceiver if present (useful for Services)
            val receiver = IntentCompat.getParcelableExtra(intent, "RECEIVER", ResultReceiver::class.java)
            receiver?.send(RESULT_OK, Bundle().apply {
                putString("RESPONSE_DATA", responseData)
            })

            // 5. Vital: Close immediately!
            finish()
        }
    }

    abstract suspend fun performCalculation(input: Input): Output
}

/**
 * Bridges a suspend call to another app's [AssistantIntent] activity-for-result.
 *
 * This is driven from the OpenAssistant tool-calling path, which runs on a single
 * background inference coroutine (via `runBlocking`). Three properties are
 * therefore essential and were the source of T495 ("assistant hangs/crashes and
 * stops responding until reboot" when creating a note):
 *  - The ActivityResult API must be invoked on the main thread; the inference
 *    coroutine is on [Dispatchers.IO].
 *  - The result callback must resume the in-flight call exactly once — a stale or
 *    duplicate result must not resume an already-completed continuation (that
 *    throws IllegalStateException and crashes the process).
 *  - The call must be time-bounded. If the launched activity never returns a
 *    result (e.g. it was killed, or a background-launch was blocked), the call
 *    must fail instead of blocking the sole inference coroutine forever, which
 *    would wedge every future prompt.
 * A [mutex] serializes launches so the single shared [continuation] is only ever
 * used by one in-flight call.
 */
class IntentLauncher(private val activity: ComponentActivity) {
    private val mutex = Mutex()
    @Volatile private var continuation: CancellableContinuation<String>? = null

    // This MUST be a property so it registers during class initialization.
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data?.getStringExtra("RESPONSE_DATA")
        if (data != null) resumeOnce(value = data)
        else resumeOnce(error = Exception("No data returned"))
    }

    /** Resumes the current continuation at most once, then clears it. */
    private fun resumeOnce(value: String? = null, error: Throwable? = null) {
        val cont = continuation ?: return
        continuation = null
        if (!cont.isActive) return
        if (value != null) cont.resume(value) else cont.resumeWithException(error ?: Exception("No data returned"))
    }

    @SuppressLint("QueryPermissionsNeeded")
    @OptIn(InternalSerializationApi::class)
    suspend fun <Input : Any> launch(
        context: Context,
        packageName: String,
        className: String,
        serializer: SerializationStrategy<Input>,
        input: Input
    ): String = mutex.withLock {
        val intent = Intent().apply {
            setClassName(packageName, className)
            putExtra("DATA", Json.encodeToString(serializer, input))
        }

        // Not installed / not resolvable: report back so callers can raise a
        // MissingAppException rather than waiting for a result that never comes.
        if (intent.resolveActivity(context.packageManager) == null) {
            return@withLock "package $packageName doesn't exist"
        }

        val result = withTimeoutOrNull(INTENT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                continuation = cont
                cont.invokeOnCancellation { continuation = null }
                // The ActivityResult API is main-thread only; the inference path
                // that calls this runs on a background dispatcher.
                activity.runOnUiThread {
                    try {
                        launcher.launch(intent)
                    } catch (e: Exception) {
                        Log.e("IntentLauncher", "Failed to launch $className in $packageName", e)
                        resumeOnce(error = e)
                    }
                }
            }
        }

        // Timed out: drop the stale continuation and surface a recoverable error
        // instead of blocking the inference loop forever.
        result ?: run {
            continuation = null
            throw Exception("Timed out waiting for a response from $packageName")
        }
    }

    companion object {
        private const val INTENT_TIMEOUT_MS = 30_000L
    }
}