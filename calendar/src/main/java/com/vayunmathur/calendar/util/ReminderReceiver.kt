package com.vayunmathur.calendar.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.vayunmathur.calendar.MainActivity
import com.vayunmathur.calendar.R
import com.vayunmathur.library.util.ensureNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Fires when an event reminder alarm scheduled by [ReminderScheduler] goes off.
 * Posts a notification, then re-reconciles so the next occurrence of a
 * recurring event is scheduled (a no-op for one-off events).
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(ReminderScheduler.EXTRA_EVENT_ID, -1L)
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.reminder_notification_default_title)
        val instanceStart = intent.getLongExtra(ReminderScheduler.EXTRA_INSTANCE_START, 0L)

        context.ensureNotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
            context.getString(R.string.reminder_channel_description),
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            eventId.toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val timeString = DateFormat.getTimeFormat(context).format(Date(instanceStart))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_notification_text, timeString))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        val notificationId = 31 * eventId.hashCode() + instanceStart.hashCode()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId, notification)

        // Reschedule the following occurrence for recurring events. Runs in the
        // background via goAsync() because it queries the calendar provider.
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderScheduler.reconcileAll(context)
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "calendar_reminders"
    }
}
