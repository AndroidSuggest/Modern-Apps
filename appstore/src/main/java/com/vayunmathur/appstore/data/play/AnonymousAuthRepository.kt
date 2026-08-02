package com.vayunmathur.appstore.data.play

import android.content.Context
import android.util.Log
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.helpers.AuthHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import kotlin.random.Random

/**
 * Anonymous auth via Aurora dispenser — now using HttpURLConnection.
 * Ported from AuroraStore's AuthProvider; previously used OkHttp.
 *
 * Two things govern whether this works at all.
 *
 * First the User-Agent: auroraoss.com is behind Cloudflare and only admits
 * Aurora Store's own, so [DISPENSER_USER_AGENT] must identify as Aurora.
 * Anything else gets a 403 challenge page before the API is ever reached.
 *
 * Second, the accounts are shared and the service throttles, which shows up as
 * a 429 or a 5xx that clears by itself shortly after. Those are retried with
 * exponential backoff and jitter rather than surfaced immediately, so a brief
 * throttle no longer leaves Play access broken until the app restarts.
 */
class AnonymousAuthRepository {

    companion object {
        private const val TAG = "AnonAuthRepo"
        const val DEFAULT_DISPENSER_URL = "https://auroraoss.com/api/auth"
        val FALLBACK_DISPENSERS = listOf(
            "https://auroraoss.com/api/auth"
        )

        /**
         * User-Agent sent to the dispenser, in Aurora's
         * `applicationId-versionName-versionCode` form.
         *
         * This deliberately identifies as Aurora Store. auroraoss.com sits
         * behind Cloudflare and only lets Aurora's own User-Agent reach the
         * API - verified directly: `com.aurora.store-*` reaches the endpoint,
         * while this app's own id gets a 403 Cloudflare challenge page on
         * every request. That 403 is what surfaced as "VPN/proxy detected -
         * dispenser blocked", and no amount of retrying fixes it.
         *
         * Keep the version roughly current; a version that has aged out could
         * start being refused. Mirrors AuroraStore app/build.gradle.kts.
         */
        private const val AURORA_APPLICATION_ID = "com.aurora.store"
        private const val AURORA_VERSION_NAME = "4.8.4"
        private const val AURORA_VERSION_CODE = 76
        const val DISPENSER_USER_AGENT =
            "$AURORA_APPLICATION_ID-$AURORA_VERSION_NAME-$AURORA_VERSION_CODE"

        private val json = Json { ignoreUnknownKeys = true }

        /** Attempts per dispenser before moving on. */
        private const val MAX_ATTEMPTS = 4

        /** First backoff step; doubles each attempt. */
        private const val BASE_BACKOFF_MS = 800L

        /**
         * Statuses worth retrying.
         *
         * 403 stays in the list even though the systematic one - Cloudflare
         * refusing a non-Aurora User-Agent - is now fixed at the source. What
         * is left is the edge throttling that also answers 403, and that does
         * clear. The cost of being wrong is bounded: a genuine block waits out
         * the backoff before reporting, rather than reporting a recoverable
         * hiccup as a permanent failure.
         */
        private fun isTransient(error: Throwable): Boolean = when (error) {
            is AuthError.RateLimited, is AuthError.VpnRequired, is AuthError.Maintenance -> true
            is AuthError.Network -> true
            is AuthError.Unknown -> error.code >= 500
            else -> false
        }
    }

    @Serializable
    data class AuthResponse(
        val email: String,
        @SerialName("authToken") val auth: String
    )

    sealed class AuthError : Exception() {
        object RateLimited : AuthError()
        object BadRequest : AuthError()
        object VpnRequired : AuthError()
        object NotFound : AuthError()
        object Maintenance : AuthError()
        data class Unknown(val code: Int, val body: String?) : AuthError()
        data class Network(override val message: String?) : AuthError()
    }

    /**
     * Fetch raw credentials from dispenser.
     */
    suspend fun fetchAnonCredentials(
        deviceProps: Properties,
        dispenserUrl: String = DEFAULT_DISPENSER_URL
    ): Result<AuthResponse> = withContext(Dispatchers.IO) {
        try {
            val propsJson = propertiesToJson(deviceProps)
            val bodyBytes = propsJson.toByteArray(Charsets.UTF_8)

            val conn = (URL(dispenserUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                requestMethod = "POST"
                doOutput = true
                doInput = true
                instanceFollowRedirects = false
                useCaches = false
                // Aurora's postAuth sends exactly these two and nothing else.
                setRequestProperty("User-Agent", DISPENSER_USER_AGENT)
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(bodyBytes.size)
            }
            conn.outputStream.use { it.write(bodyBytes) }

            val code = try { conn.responseCode } catch (e: Exception) {
                conn.disconnect()
                return@withContext Result.failure(AuthError.Network(e.message))
            }
            val responseBody = try {
                val stream = if (code >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
                stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            } catch (_: Exception) { "" } finally {
                conn.disconnect()
            }

            if (code !in 200..299) {
                return@withContext Result.failure(mapError(code, responseBody))
            }

            try {
                val auth = json.decodeFromString(AuthResponse.serializer(), responseBody)
                Result.success(auth)
            } catch (e: Exception) {
                Log.w(TAG, "Parse auth failed: ${e.message}")
                Result.failure(AuthError.Unknown(code, responseBody))
            }
        } catch (e: Exception) {
            Result.failure(AuthError.Network(e.message))
        }
    }

    /**
     * Build AuthData from dispenser response using gplayapi AuthHelper.
     */
    suspend fun buildAuthData(
        context: Context,
        email: String,
        token: String,
        deviceProps: Properties
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        try {
            val locale = java.util.Locale.getDefault()
            val authData = AuthHelper
                .build(
                    email = email,
                    token = token,
                    tokenType = AuthHelper.Token.AUTH,
                    isAnonymous = true,
                    properties = deviceProps,
                    locale = locale
                )
            Result.success(authData)
        } catch (e: Exception) {
            Log.w(TAG, "buildAuthData failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Dispense a fresh anonymous account, retrying throttled responses.
     *
     * Each dispenser gets [MAX_ATTEMPTS] tries with exponential backoff and
     * jitter before the next is tried. Jitter matters because every install
     * that got throttled at the same moment would otherwise retry in lockstep
     * and throttle each other again.
     */
    suspend fun ensureAuthData(
        context: Context,
        deviceProps: Properties,
        dispenserUrls: List<String> = FALLBACK_DISPENSERS
    ): Result<AuthData> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null

        for (url in dispenserUrls) {
            for (attempt in 0 until MAX_ATTEMPTS) {
                currentCoroutineContext().ensureActive()

                val credResult = fetchAnonCredentials(deviceProps, url)
                val cred = credResult.getOrNull()
                if (cred != null) {
                    val authResult = buildAuthData(context, cred.email, cred.auth, deviceProps)
                    if (authResult.isSuccess) return@withContext authResult
                    // Credentials arrived but were unusable. A different
                    // account may well work, so this is worth another attempt.
                    lastError = authResult.exceptionOrNull()
                } else {
                    val error = credResult.exceptionOrNull()
                    lastError = error
                    if (error != null && !isTransient(error)) {
                        Log.w(TAG, "Dispenser $url rejected us permanently: $error")
                        break
                    }
                }

                if (attempt < MAX_ATTEMPTS - 1) {
                    val backoff = BASE_BACKOFF_MS shl attempt
                    val wait = backoff + Random.nextLong((backoff / 2).coerceAtLeast(1))
                    Log.w(TAG, "Dispenser $url attempt ${attempt + 1} failed ($lastError), " +
                        "retrying in ${wait}ms")
                    delay(wait)
                }
            }
        }
        Result.failure(lastError ?: Exception("All dispensers failed"))
    }

    private fun propertiesToJson(props: Properties): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in props.entries) {
            if (!first) sb.append(",")
            sb.append("\"").append(escapeJson(k.toString())).append("\":\"").append(escapeJson(v.toString())).append("\"")
            first = false
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun mapError(code: Int, body: String?): Throwable {
        return when (code) {
            400 -> AuthError.BadRequest
            403 -> AuthError.VpnRequired
            404 -> AuthError.NotFound
            429 -> AuthError.RateLimited
            503 -> AuthError.Maintenance
            else -> AuthError.Unknown(code, body)
        }
    }

    fun errorMessage(error: Throwable): String {
        return when (error) {
            is AuthError.RateLimited -> "Play dispenser is rate limiting – try again shortly"
            // Only reported once retries are exhausted, so by this point a
            // transient throttle has been ruled out.
            is AuthError.VpnRequired -> "Play dispenser refused this network – try again, or disable VPN"
            is AuthError.BadRequest -> "Bad request to dispenser"
            is AuthError.NotFound -> "Dispenser unreachable"
            is AuthError.Maintenance -> "Dispenser maintenance, try later"
            is AuthError.Unknown -> "Dispenser error ${error.code}"
            is AuthError.Network -> "Network error: ${error.message}"
            else -> error.message ?: "Auth failed"
        }
    }
}
