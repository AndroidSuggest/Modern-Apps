package com.vayunmathur.communicate.telephony

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.vayunmathur.communicate.MainActivity
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.googlevoice.call.CallPhase
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service (type `phoneCall`) that keeps a Google Voice call — its SIP socket, mic
 * capture, and audio focus — alive while off-screen, showing the ongoing-call notification.
 * Started by [GoogleVoiceConnectionService] and self-stops when the call reaches a terminal
 * state.
 */
class GoogleVoiceCallForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        observeJob?.cancel()
        observeJob = scope.launch {
            GoogleVoiceCallManager.state.collect { state ->
                when (state.phase) {
                    CallPhase.Idle, CallPhase.Ended -> stopSelfSafely()
                    else -> startForegroundWithNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        ensureNotificationChannel(CHANNEL_ID, getString(R.string.gv_call_channel_name))
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.gv_ongoing_call))
            .setContentText(GoogleVoiceCallManager.state.value.remoteNumber)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopSelfSafely() {
        androidx.core.app.ServiceCompat.stopForeground(
            this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE,
        )
        stopSelf()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gv_calls"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, GoogleVoiceCallForegroundService::class.java),
            )
        }
    }
}
