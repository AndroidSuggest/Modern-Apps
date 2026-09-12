package com.vayunmathur.auto.platform

/**
 * Something the messaging channel observed, forwarded to [AutoSessionState] for the phone UI.
 *
 * Streaming never waits on these: they are fire-and-forget observations, mirroring
 * [VideoEvent] and [MediaEvent].
 */
sealed interface MessagingEvent {
    /** A thread-list snapshot went out on ch14; [count] is threads in it. */
    data class ThreadsPosted(val count: Int) : MessagingEvent

    /** One message body went out on ch14 for [threadId]. */
    data class MessagePosted(val threadId: String) : MessagingEvent

    /** The head unit answered with reply text for [threadId]. */
    data class ReplyReceived(val threadId: String) : MessagingEvent

    /**
     * The head unit marked [threadId] read. Observed only: the phone keeps no
     * per-thread read state yet, so there is nothing to update. Kept as an
     * event (rather than dropped in the channel) so a future car card can
     * render it without re-plumbing the channel.
     */
    data class MarkedRead(val threadId: String) : MessagingEvent
}
