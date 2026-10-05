package com.vayunmathur.communicate.telephony

/**
 * Maintenance loops (split from RcsSyncService.kt for file length).
 * Behavior identical, call sites unchanged.
 */

internal fun RcsSyncService.startMaintenanceLoops() {
        serviceScope.apply {
            startOutboxPump()
            startTeardownWatch()
            startMsrpLifecycle()
        }
    }

    /** Deferred outbox pump + conference-subscription refresh (§4.3, §3.1). */
internal fun RcsSyncService.startOutboxPump() {
        serviceScope.apply {
            // Deferred outbox pump + conference-subscription refresh (§4.3,
            // §3.1): every 5 minutes while the service lives.
            launch {
                while (RcsFeature.enabled) {
                    kotlinx.coroutines.delay(OUTBOX_POLL_MS)
                    runCatching {
                        RcsOutbox.pumpDue(this@RcsSyncService, CommunicateRepository)
                    }
                    runCatching { RcsSessionManager.refreshEventSubscriptions() }
                }
            }
        }
    }

    /** Watch for teardown + re-ensure the MSRP listener on availability. */
internal fun RcsSyncService.startTeardownWatch() {
        serviceScope.apply {
            // Watch for teardown: when the transport goes unavailable, reflect it
            // in the sync notification and stop if the gate flips off. On
            // (re-)Availability, re-ensure the passive MSRP listener — a
            // subId-switch teardown stops it (IMS PDN is per-sub).
            launch {
                RcsSipTransport.state.collect { state ->
                    if (!RcsFeature.enabled) shutdown()
                    updateSyncNotification(state)
                    if (state is RcsRegistrationState.Available) {
                        RcsSessionManager.listenContextProvider = { this@RcsSyncService }
                        RcsMsrpListen.ensureListening(this@RcsSyncService, ::onMsrpAccepted)
                    }
                }
            }
        }
    }

    /** Own MSRP connection lifecycle per session path. */
internal fun RcsSyncService.startMsrpLifecycle() {
        serviceScope.apply {
            // Own MSRP connection lifecycle: when a session gains a usable
            // remote path, connect out and feed inbound chunks to the inbox.
            // Also sweep sessions that never completed or idled out, closing
            // their connections so the map cannot leak. Re-INVITEs that move
            // the bound path/setup swap the connection (§2.4 handover).
            launch {
                // conversationId → remote path the live connection serves.
                val boundPaths = java.util.concurrent.ConcurrentHashMap<String, String>()
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
    }
