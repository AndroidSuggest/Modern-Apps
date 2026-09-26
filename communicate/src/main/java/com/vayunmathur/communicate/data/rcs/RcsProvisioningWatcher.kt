package com.vayunmathur.communicate.data.rcs

import android.content.Context
import android.telephony.SubscriptionManager
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Live provisioning watcher: registers the hidden
 * `ProvisioningManager` RCS callback (see [RcsHiddenApi]) so carrier config
 * changes reach us without re-probing.
 *
 * On `onConfigurationChanged` the raw RCS config XML is parsed for the
 * FT-over-HTTP content-server URI (`ftHTTPCSURI` →
 * [RcsFileTransferHttp.contentServerUri], which is what activates the
 * FT-over-HTTP upload path) and provisioning is re-probed to refresh the
 * transport state. On reset/removal the content-server URI is cleared and
 * the state re-probed.
 *
 * Owned by the transport lifecycle: [watch] at `ensureRegistered` time,
 * [unwatch] on teardown. Best-effort throughout — the bridge returns false
 * without carrier privilege and everything keeps working on the last-known
 * values. Never throws.
 */
object RcsProvisioningWatcher {
    private const val TAG = "RcsProvWatch"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var manager: Any? = null

    @Volatile
    private var watchingSubId: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    /**
     * Register for provisioning callbacks on [subId]. Idempotent per subId —
     * re-calls for the same subId are no-ops. Call [unwatch] first when
     * switching subscriptions.
     */
    fun watch(context: Context, subId: Int) {
        if (!RcsFeature.enabled) return
        if (!SubscriptionManager.isValidSubscriptionId(subId)) return
        if (subId == watchingSubId && manager != null) return
        unwatch()
        val pm = RcsHiddenApi.provisioningManager(subId) ?: run {
            Log.i(TAG, "No provisioning manager for subId=$subId (no privilege or unsupported)")
            return
        }
        val app = context.applicationContext
        val ok = RcsHiddenApi.registerProvisioningCallback(
            provisioningManager = pm,
            executor = executor,
            rcsVersion = "6.0",
            rcsProfile = "UP_2.0",
            callback = object : RcsHiddenApi.ProvisioningCallback {
                override fun onConfigurationChanged(configXml: ByteArray) {
                    scope.launch { applyConfig(app, subId, configXml) }
                }

                override fun onConfigurationReset() {
                    scope.launch {
                        Log.i(TAG, "Provisioning config reset for subId=$subId")
                        RcsFileTransferHttp.contentServerUri = null
                        RcsProvisioning.probe(app, subId)
                    }
                }

                override fun onRemoved() {
                    scope.launch {
                        Log.i(TAG, "Provisioning removed for subId=$subId")
                        RcsFileTransferHttp.contentServerUri = null
                        RcsProvisioning.probe(app, subId)
                    }
                }
            },
        )
        if (ok) {
            manager = pm
            watchingSubId = subId
            Log.i(TAG, "Watching provisioning for subId=$subId")
        }
    }

    /** Unregister (best-effort) and drop watcher state. */
    fun unwatch() {
        manager?.let { runCatching { RcsHiddenApi.unregisterProvisioningCallback(it) } }
        manager = null
        watchingSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    private suspend fun applyConfig(context: Context, subId: Int, configXml: ByteArray) {
        if (!RcsFeature.enabled || configXml.isEmpty()) return
        RcsFileTransferHttp.parseContentServer(configXml)?.let { uri ->
            if (RcsFileTransferHttp.contentServerUri != uri) {
                Log.i(TAG, "FT content server configured for subId=$subId")
                RcsFileTransferHttp.contentServerUri = uri
            }
        }
        // Config changed under us — refresh the transport state.
        RcsProvisioning.probe(context, subId)
    }
}
