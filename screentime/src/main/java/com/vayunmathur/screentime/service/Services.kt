package com.vayunmathur.screentime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import com.vayunmathur.screentime.R
import com.vayunmathur.screentime.platform.Coordinator
import com.vayunmathur.screentime.ui.DashboardActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The focus-mode Quick Settings tile, mirroring stock's `FocusModeTileService`.
 *
 * Taps toggle a manual focus session; the tile reflects whether focus is currently running
 * (manual or scheduled). Unlocks the device first - toggling focus is a deliberate act, not
 * something that should happen behind the keyguard.
 */
class FocusTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            scope.launch {
                val coordinator = Coordinator(this@FocusTileService)
                coordinator.setFocusActive(!coordinator.isFocusActive())
                withContext(Dispatchers.Main) { updateTile() }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        scope.launch {
            val active = Coordinator(this@FocusTileService).isFocusActive()
            withContext(Dispatchers.Main) {
                qsTile?.apply {
                    state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    updateTile()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * Holds focus-mode app pauses while a session runs.
 *
 * Suspension itself persists without a process (the platform owns it), but the foreground
 * service keeps our scheduling and observer state alive and gives the user a visible,
 * stoppable handle: the notification's action ends the session directly.
 */
class FocusService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                ensureChannel()
                startForeground(NOTIFICATION_ID, notification())
            }
            ACTION_STOP -> {
                scope.launch {
                    Coordinator(this@FocusService).setFocusActive(false)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, FocusService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DashboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.focus_running_title))
            .setContentText(getString(R.string.focus_running_text))
            .setContentIntent(open)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.focus_stop),
                stop,
            )
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.focus_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.focus_channel_description)
            },
        )
    }

    companion object {
        const val ACTION_START = "com.vayunmathur.screentime.action.FOCUS_START"
        const val ACTION_STOP = "com.vayunmathur.screentime.action.FOCUS_STOP"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, FocusService::class.java).setAction(ACTION_START),
            )
        }

        private const val CHANNEL_ID = "focus_session"
        private const val NOTIFICATION_ID = 3_000_001
    }
}
