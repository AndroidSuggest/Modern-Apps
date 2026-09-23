package com.vayunmathur.communicate.telephony

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.ims.SipMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.MainActivity
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.CommunicateRepository
import com.vayunmathur.communicate.data.cacheInboundRcs
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground owner for the RCS single-registration line: keeps the SIP
 * delegate + UCE listener alive, writes inbound messages to Room, and posts
 * new-message notifications while Communicate is backgrounded. Mirrors
 * [SignalSyncService] (FGS type remoteMessaging).
 */
class RcsSyncService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: Android requires startForeground() within ~5s of any
        // startForegroundService() call, INCLUDING the ACTION_STOP path (MainActivity calls stop()
        // at launch when not registered, on a service that was never started).
        ensureChannels()
        startForegroundCompat(buildSyncNotification())

        // Dev-only feature: if ever started in a release build, immediately stand down.
        if (!RcsFeature.enabled) {
            shutdown()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            RcsSipTransport.ensureRegistered(this@RcsSyncService)
            RcsSipTransport.onInboundMessage = { message ->
                serviceScope.launch { handleInbound(message) }
            }
            // Watch for teardown: when the transport goes unavailable, reflect it
            // in the sync notification and stop if the gate flips off.
            launch {
                RcsSipTransport.state.collect { state ->
                    if (!RcsFeature.enabled) shutdown()
                    updateSyncNotification(state)
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        RcsSipTransport.onInboundMessage = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout for type=$fgsType; leaving foreground to avoid crash")
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private suspend fun handleInbound(message: SipMessage) {
        val parsed = parseInbound(message) ?: return
        CommunicateRepository.cacheInboundRcs(
            context = this,
            conversationId = parsed.conversationId,
            body = parsed.body,
            senderId = parsed.senderId,
            messageId = parsed.messageId,
        )
        showIncomingNotification(parsed)
    }

    private fun parseInbound(message: SipMessage): InboundRcs? {
        return runCatching {
            val headers = message.getHeaderSection()
            val from = headerValue(headers, "From:")?.substringAfter("<")?.substringBefore(">")
                ?.substringAfter("sip:")?.substringBefore("@")
                ?.takeIf { it.isNotBlank() } ?: return null
            val body = message.getContent().toString(Charsets.UTF_8)
            if (body.isBlank()) return null
            val callId = message.getCallIdParameter() ?: body.hashCode().toString()
            InboundRcs(
                conversationId = from,
                body = body,
                senderId = from,
                messageId = "in-$callId-${message.getViaBranchParameter()}",
            )
        }.getOrNull()
    }

    private data class InboundRcs(
        val conversationId: String,
        val body: String,
        val senderId: String,
        val messageId: String,
    )

    private fun showIncomingNotification(parsed: InboundRcs) {
        val target = ConversationTarget(
            line = CommunicateLine.Rcs,
            address = parsed.conversationId,
            remoteId = parsed.conversationId,
            isGroup = false,
            personName = parsed.senderId,
        )
        ConversationSpace.notifyIncoming(
            context = this,
            target = target,
            channelId = INCOMING_CHANNEL_ID,
            body = parsed.body.ifBlank { getString(R.string.new_message) },
            timestamp = System.currentTimeMillis(),
            smallIcon = R.mipmap.ic_launcher,
        )
    }

    private fun ensureChannels() {
        ensureNotificationChannel(
            id = SYNC_CHANNEL_ID,
            name = "RCS sync",
            importance = NotificationManager.IMPORTANCE_LOW,
            description = "Keeps the RCS connection alive",
        ) {
            setSound(null, null)
            enableVibration(false)
        }
        ensureNotificationChannel(
            id = INCOMING_CHANNEL_ID,
            name = "RCS messages",
            importance = NotificationManager.IMPORTANCE_HIGH,
            description = "Incoming RCS messages",
        ) {
            setAllowBubbles(true)
        }
    }

    private fun buildSyncNotification(state: RcsRegistrationState? = null): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RcsSyncService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val status = when (state) {
            is RcsRegistrationState.Available -> getString(R.string.rcs_available)
            is RcsRegistrationState.Unavailable -> getString(R.string.rcs_not_available)
            else -> getString(R.string.rcs_provisioning)
        }
        return NotificationCompat.Builder(this, SYNC_CHANNEL_ID)
            .setContentTitle("RCS")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, getString(R.string.notification_action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateSyncNotification(state: RcsRegistrationState) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(SYNC_NOTIFICATION_ID, buildSyncNotification(state))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(SYNC_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(SYNC_NOTIFICATION_ID, notification)
        }
    }

    private fun shutdown() {
        RcsSipTransport.onInboundMessage = null
        runCatching { RcsSipTransport.tearDown(this) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "RcsSync"
        private const val SYNC_NOTIFICATION_ID = 4723
        private const val SYNC_CHANNEL_ID = "rcs_sync"
        private const val INCOMING_CHANNEL_ID = "rcs_messages_incoming"
        private const val ACTION_STOP = "com.vayunmathur.communicate.rcs.STOP_SYNC"
        const val EXTRA_OPEN_RCS_THREAD = "open_rcs_thread"

        fun start(context: Context) {
            // Dev-only feature: never start the RCS sync service in the release variant.
            if (!RcsFeature.enabled) return
            ContextCompat.startForegroundService(context, Intent(context, RcsSyncService::class.java))
        }

        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RcsSyncService::class.java).apply { action = ACTION_STOP },
            )
        }

        /** Parse a header value out of a SIP header section (case-sensitive name + colon). */
        private fun headerValue(headers: String, name: String): String? {
            for (line in headers.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith(name, ignoreCase = true)) {
                    return trimmed.substringAfter(":").trim().takeIf { it.isNotEmpty() }
                }
            }
            return null
        }
    }
}
