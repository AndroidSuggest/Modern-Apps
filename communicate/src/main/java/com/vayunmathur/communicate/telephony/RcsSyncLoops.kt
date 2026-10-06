package com.vayunmathur.communicate.telephony

import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsOutbox
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.refreshEventSubscriptions
import kotlinx.coroutines.launch

/**
 * Maintenance loops (split from RcsSyncService.kt for file length).
 * Behavior identical, call sites unchanged.
 */

internal fun RcsSyncService.startMaintenanceLoops() {
    startOutboxPump()
    startTeardownWatch()
    startMsrpLifecycle()
}

/** Deferred outbox pump + conference-subscription refresh (§4.3, §3.1). */
internal fun RcsSyncService.startOutboxPump() {
    val self = this
    serviceScope.launch {
        // Deferred outbox pump + conference-subscription refresh (§4.3,
        // §3.1): every 5 minutes while the service lives.
        while (RcsFeature.enabled) {
            kotlinx.coroutines.delay(RcsSyncService.OUTBOX_POLL_MS)
            runCatching {
                RcsOutbox.pumpDue(self, CommunicateRepository)
            }
            runCatching { RcsSessionManager.refreshEventSubscriptions() }
        }
    }
}

/** Watch for teardown + re-ensure the MSRP listener on availability. */
internal fun RcsSyncService.startTeardownWatch() {
    val self = this
    serviceScope.launch {
        // Watch for teardown: when the transport goes unavailable, reflect it
        // in the sync notification and stop if the gate flips off. On
        // (re-)Availability, re-ensure the passive MSRP listener — a
        // subId-switch teardown stops it (IMS PDN is per-sub).
        RcsSipTransport.state.collect { state ->
            if (!RcsFeature.enabled) self.shutdown()
            self.updateSyncNotification(state)
            if (state is RcsRegistrationState.Available) {
                RcsSessionManager.listenContextProvider = { self }
                RcsMsrpListen.ensureListening(self, self::onMsrpAccepted)
            }
        }
    }
}

/** Own MSRP connection lifecycle per session path. */
internal fun RcsSyncService.startMsrpLifecycle() {
    serviceScope.launch {
        // Own MSRP connection lifecycle: when a session gains a usable
        // remote path, connect out and feed inbound chunks to the inbox.
        // Also sweep sessions that never completed or idled out, closing
        // their connections so the map cannot leak. Re-INVITEs that move
        // the bound path/setup swap the connection (§2.4 handover).
        // conversationId → remote path the live connection serves.
        RcsSessionManager.sessions.collect { sessions ->
            val stale = RcsSessionManager.sweepStaleSessions()
            for ((conversationId, session) in sessions) {
                val path = session.msrpRemotePath
                if (path == null) {
                    boundPaths.remove(conversationId)
                } else {
                    syncMsrpConnection(conversationId, session, path)
                }
            }
            // Drop connections whose sessions went away.
            val liveKeys = sessions.keys
            msrpConnections.keys.filter { it !in liveKeys || it in stale }.forEach { id ->
                msrpConnections.remove(id)?.close()
                boundPaths.remove(id)
            }
        }
    }
}
