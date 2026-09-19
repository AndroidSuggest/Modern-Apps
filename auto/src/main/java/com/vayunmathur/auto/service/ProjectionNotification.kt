package com.vayunmathur.auto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.vayunmathur.auto.R

/**
 * Foreground-service chrome for [ProjectionService]: the low-importance
 * "projection running" notification plus the channel/start helpers. Split
 * out so the service file stays under the 800-line limit.
 */
internal object ProjectionNotification {
    private const val CHANNEL_ID = "projection"
    const val NOTIFICATION_ID = 1

    fun build(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.projection_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.projection_running))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }
}
