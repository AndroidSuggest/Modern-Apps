package com.vayunmathur.auto.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vayunmathur.auto.platform.MessagingPrefs
import com.vayunmathur.auto.protocol.gal.MessagingMessage
import com.vayunmathur.auto.protocol.gal.MessagingThread

/**
 * Mirrors phone message notifications onto the projection session's ch14 owner.
 *
 * Posts only `CATEGORY_MESSAGE` notifications (SMS and chat apps tag theirs;
 * everything else is skipped) that carry non-blank text, and only while the
 * user has both granted the system listener binding and flipped the in-app
 * consent switch: either gate off means fail-closed silence, never a send.
 * The system binds this service itself once the user enables MA Auto in the
 * notification-access settings; there is nothing to start or stop.
 */
class MessageMirrorService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!MessagingPrefs.isMirroringConsented(this)) return
        val notification = sbn.notification
        if (notification.category != Notification.CATEGORY_MESSAGE) return
        val (thread, message) = extract(sbn) ?: return
        MessageMirrorBus.post(thread, message)
        Log.d(TAG, "mirrored message for thread ${thread.threadId}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Dismissals on the phone side stay local: without a per-thread read
        // store there is nothing truthful to tell the head unit.
    }

    /**
     * Pulls a ch14 snapshot out of a message notification, or null when it
     * carries nothing worth sending: no `MessagingStyle`, or a style with no
     * messages, or a latest message with blank text.
     */
    internal fun extract(sbn: StatusBarNotification): Pair<MessagingThread, MessagingMessage>? {
        val extras = NotificationCompat.getExtras(sbn.notification) ?: return null
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotificationExtras(extras)
            ?.messages
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val latest = messages.lastOrNull { !it.text.isNullOrBlank() } ?: return null
        val threadId = conversationKey(sbn)
        val thread = MessagingThread.newBuilder()
            .setThreadId(threadId)
            .setTitle(conversationTitle(sbn, latest))
            .setSender(latest.person?.name?.toString().orEmpty())
            .setLastMessageMs(latest.timestamp)
            .build()
        val message = MessagingMessage.newBuilder()
            .setThreadId(threadId)
            .setMessageId(messageKey(sbn, latest.timestamp))
            .setSender(latest.person?.name?.toString().orEmpty())
            .setBody(latest.text.toString())
            .setTimestampMs(latest.timestamp)
            .build()
        return thread to message
    }

    /**
     * Stable per-conversation key: the notification's conversation id when the
     * app shortcuts one (people-tagged messages), else the posting package plus
     * tag plus id. Stable across reposts of the same thread, distinct across
     * apps and threads -- which is what ch14 groups by.
     */
    internal fun conversationKey(sbn: StatusBarNotification): String {
        val shortcut = sbn.notification.shortcutId
        if (!shortcut.isNullOrEmpty()) return "${sbn.packageName}:$shortcut"
        return "${sbn.packageName}:${sbn.tag}:${sbn.id}"
    }

    private fun conversationTitle(sbn: StatusBarNotification, latest: NotificationCompat.MessagingStyle.Message): String {
        val styleTitle = NotificationCompat.getExtras(sbn.notification)
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotificationExtras(it)?.conversationTitle }
        if (!styleTitle.isNullOrBlank()) return styleTitle.toString()
        val sender = latest.person?.name?.toString()
        if (!sender.isNullOrBlank()) return sender
        return sbn.packageName
    }

    private fun messageKey(sbn: StatusBarNotification, timestamp: Long): String =
        "${sbn.id}:$timestamp"

    private companion object {
        const val TAG = "MaAuto.MsgMirror"
    }
}
