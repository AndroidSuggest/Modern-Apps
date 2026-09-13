package com.vayunmathur.auto.notifications

import android.app.PendingIntent
import android.app.RemoteInput

/**
 * How a head-unit reply leaves the phone for one conversation thread.
 *
 * Gearhead never sends SMS itself: replies go back through the message app
 * that posted the notification, via its own reply `PendingIntent` plus a
 * `RemoteInput` result (`lwg.c()` in the teardown: `REPLY_USING_NOTIFICATION_ACTION`
 * vs `REPLY_USING_CAR_EXTENDER`). The mark-read intent dismisses/handles the
 * thread on the phone side when the car marks it read.
 *
 * Retained per thread by the mirror service and handed to the ch14 owner on
 * the bus; intents are fire-once snapshots (a reposted notification replaces
 * them). Sending is fail-closed: a missing route or a failed `send()` drops
 * with a count, never a crash and never a direct SMS.
 *
 * Only the direct-action shape is retained (an action carrying a `RemoteInput`,
 * what modern `MessagingStyle` notifications post). The legacy `CarExtender`
 * unread-conversation shape is not read -- its androidx accessor is gone and
 * current message apps use direct actions.
 */
data class MessageReplyRoute(
    val threadId: String,
    /** Fires the message app's reply action; null when the app offers none. */
    val replyIntent: PendingIntent?,
    /** Carries the reply text into [replyIntent]; null with the intent. */
    val remoteInput: RemoteInput?,
    /** Marks the thread read on the phone; null when the app offers none. */
    val readIntent: PendingIntent?,
)
