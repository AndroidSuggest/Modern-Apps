package com.vayunmathur.communicate.data.rcs

import android.content.Context
import android.net.Uri
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsException
import android.telephony.ims.ImsManager
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Capability exchange over the hidden `RcsUceAdapter#requestCapabilities`
 * (see [RcsHiddenApi]).
 *
 * Unknown contacts count as not capable → the send path falls back to SMS.
 * All failures are fail-closed (`false`); never throws.
 */
object RcsCapabilityExchange {
    private const val TAG = "RcsCapability"

    // AOSP RcsContactUceCapability values (hidden from the public SDK).
    private const val REQUEST_RESULT_FOUND = 3
    private const val CAPABILITY_MECHANISM_OPTIONS = 2

    private val executor: Executor = Executors.newSingleThreadExecutor()

    private val _updates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val updates: StateFlow<Map<String, Boolean>> = _updates.asStateFlow()

    /** Whether the UCE path is usable at all (setting on + no privilege error). */
    @Volatile
    var uceAvailable: Boolean = true
        private set

    /**
     * True when [e164] is RCS-capable (capability FOUND and the CPM session
     * tag is covered). False for unknown contacts, errors, or when the gate
     * is off.
     */
    suspend fun isContactRcsCapable(context: Context, e164: String): Boolean {
        if (!RcsFeature.enabled || e164.isBlank()) return false
        // Fast path: single-contact availability (cheaper than capabilities,
        // §7.3). Falls back to the full query when the bridge is missing.
        if (runCatching { isContactAvailable(context, e164) }.getOrDefault(false)) return true
        val results = queryCapabilities(context, listOf(e164))
        return results[e164] == true
    }

    /**
     * Single-contact availability via `requestAvailability` (§7.3): cheaper
     * than `requestCapabilities` (presence-style, no feature-tag fetch).
     * Fail-closed false on any error. Throws SecurityException/ImsException
     * for the caller to map (mirrors the capabilities path).
     */
    private suspend fun isContactAvailable(context: Context, e164: String): Boolean {
        val app = context.applicationContext
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (!SubscriptionManager.isValidSubscriptionId(subId)) return false
        val ims = app.getSystemService(ImsManager::class.java) ?: return false
        val adapter = runCatching { ims.getImsRcsManager(subId).getUceAdapter() }.getOrNull()
            ?: return false
        val uri = Uri.fromParts("tel", e164, null)
        val caps: List<Map<String, Any?>> = suspendCancellableCoroutine { cont ->
            val collected = mutableListOf<Map<String, Any?>>()
            val bridged: Boolean = try {
                RcsHiddenApi.requestUceAvailability(
                    adapter = adapter,
                    contactUri = uri,
                    executor = executor,
                    callback = object : RcsHiddenApi.UceCallback {
                        override fun onCapabilitiesReceived(caps: List<*>) {
                            for (cap in caps) {
                                readCapability(cap)?.let { collected += it }
                            }
                        }

                        override fun onComplete() {
                            if (cont.isActive) cont.resume(collected.toList())
                        }

                        override fun onError(errorCode: Int, retryAfterMillis: Long) {
                            Log.w(TAG, "UCE availability error code=$errorCode")
                            if (cont.isActive) cont.resume(collected.toList())
                        }
                    },
                )
            } catch (expected: SecurityException) {
                uceAvailable = false
                if (cont.isActive) cont.resume(emptyList())
                false
            } catch (expected: ImsException) {
                if (cont.isActive) cont.resume(emptyList())
                false
            }
            if (!bridged && cont.isActive) cont.resume(collected.toList())
        }
        return caps.any { isStrictlyCapable(it) }
    }

    /**
     * Batch capability check for group members. Returns the subset that is
     * RCS-capable. Empty on any failure.
     */
    suspend fun filterCapable(context: Context, members: List<String>): List<String> {
        if (!RcsFeature.enabled || members.isEmpty()) return emptyList()
        val results = queryCapabilities(context, members.distinct())
        return members.filter { results[it] == true }
    }

    private suspend fun queryCapabilities(
        context: Context,
        numbers: List<String>,
    ): Map<String, Boolean> {
        if (numbers.isEmpty()) return emptyMap()
        val app = context.applicationContext
        // TestRcsApp keys everything off the default SMS subscription.
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        if (!SubscriptionManager.isValidSubscriptionId(subId)) return emptyMap()
        val ims = app.getSystemService(ImsManager::class.java) ?: return emptyMap()
        val adapter = runCatching { ims.getImsRcsManager(subId).getUceAdapter() }.getOrNull()
            ?: return emptyMap()
        val uris = numbers.map { Uri.fromParts("tel", it, null) }
        val caps: List<Map<String, Any?>> = requestCapabilities(adapter, uris)
        val result = mutableMapOf<String, Boolean>()
        for (cap in caps) {
            val contact = cap["contact"] as? String ?: continue
            val number = contact.substringAfter("tel:")
            result[number] = isCapable(cap)
        }
        if (result.isNotEmpty()) {
            _updates.value = _updates.value + result
        }
        return result
    }

    /** Request capabilities via the hidden UCE API, collecting results. */
    private suspend fun requestCapabilities(
        adapter: Any,
        uris: List<Uri>,
    ): List<Map<String, Any?>> = suspendCancellableCoroutine { cont ->
        val collected = mutableListOf<Map<String, Any?>>()
        val bridged: Boolean = try {
            RcsHiddenApi.requestUceCapabilities(
                adapter = adapter,
                contactUris = uris,
                executor = executor,
                callback = object : RcsHiddenApi.UceCallback {
                    override fun onCapabilitiesReceived(caps: List<*>) {
                        for (cap in caps) {
                            readCapability(cap)?.let { collected += it }
                        }
                    }

                    override fun onComplete() {
                        if (cont.isActive) cont.resume(collected.toList())
                    }

                    override fun onError(errorCode: Int, retryAfterMillis: Long) {
                        Log.w(TAG, "UCE error code=$errorCode")
                        if (cont.isActive) cont.resume(collected.toList())
                    }
                },
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "No UCE permission", e)
            uceAvailable = false
            if (cont.isActive) cont.resume(emptyList())
            false
        } catch (e: ImsException) {
            Log.w(TAG, "UCE service unavailable", e)
            if (cont.isActive) cont.resume(emptyList())
            false
        }
        if (!bridged && cont.isActive) cont.resume(collected.toList())
    }

    /**
     * Read the framework `RcsContactUceCapability` reflectively into a plain
     * map. All getters are hidden; any failure yields null (not capable).
     */
    private fun readCapability(cap: Any?): Map<String, Any?>? {
        if (cap == null) return null
        return runCatching {
            val cls = cap.javaClass
            val contact = cls.getMethod("getContactUri").invoke(cap) as? Uri
            val result = cls.getMethod("getRequestResult").invoke(cap) as? Int
            val mechanism = cls.getMethod("getCapabilityMechanism").invoke(cap) as? Int
            @Suppress("UNCHECKED_CAST")
            val tags = cls.getMethod("getFeatureTags").invoke(cap) as? Set<String>
            mapOf(
                "contact" to contact?.toString(),
                "result" to result,
                "mechanism" to mechanism,
                "tags" to (tags ?: emptySet<String>()),
            )
        }.getOrNull()
    }

    private fun isCapable(cap: Map<String, Any?>): Boolean {
        if (cap["result"] as? Int != REQUEST_RESULT_FOUND) return false
        // OPTIONS mechanism carries feature tags; PRESENCE tuples don't encode CPM tags.
        if (cap["mechanism"] as? Int == CAPABILITY_MECHANISM_OPTIONS) {
            @Suppress("UNCHECKED_CAST")
            val tags = cap["tags"] as? Set<String> ?: emptySet()
            return tags.any { it.contains("cpm.session", ignoreCase = true) }
        }
        return true
    }

    /**
     * Strict variant for the availability fast path (§7.3): presence-style
     * FOUND without CPM tags does NOT short-circuit true (an RCS-presence
     * contact may still lack the CPM session tag — the full query decides).
     */
    private fun isStrictlyCapable(cap: Map<String, Any?>): Boolean {
        if (cap["result"] as? Int != REQUEST_RESULT_FOUND) return false
        if (cap["mechanism"] as? Int != CAPABILITY_MECHANISM_OPTIONS) return false
        @Suppress("UNCHECKED_CAST")
        val tags = cap["tags"] as? Set<String> ?: emptySet()
        return tags.any { it.contains("cpm.session", ignoreCase = true) }
    }
}
