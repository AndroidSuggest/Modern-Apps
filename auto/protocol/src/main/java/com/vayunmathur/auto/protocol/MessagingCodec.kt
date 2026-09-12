package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.MessagingAction
import com.vayunmathur.auto.protocol.gal.MessagingActionType
import com.vayunmathur.auto.protocol.gal.MessagingDismiss
import com.vayunmathur.auto.protocol.gal.MessagingMessage
import com.vayunmathur.auto.protocol.gal.MessagingThread
import com.vayunmathur.auto.protocol.gal.MessagingThreads

/**
 * A user action that arrived on ch14, already classified.
 *
 * The head unit is the only sender: the phone never sends [GalMessage.Notification.ACTION]
 * to itself, so anything else on this channel in this direction is out of scope and
 * decodes to null rather than throwing.
 */
sealed interface InboundMessagingAction {
    /** The user typed a reply on the head unit; [text] is what they wrote. */
    data class Reply(val threadId: String, val text: String) : InboundMessagingAction

    /** The user marked the thread read on the head unit. */
    data class MarkRead(val threadId: String) : InboundMessagingAction

    /**
     * The user asked to reply by voice: the owner opens a ch6 mic turn for
     * [threadId] through [com.vayunmathur.auto.platform.MessagingAudio] and the
     * transcribed text comes back as a [Reply].
     */
    data class VoiceReplyBegin(val threadId: String) : InboundMessagingAction
}

/**
 * Pure ch14 (NOTIFICATION, service 14) codecs: threads, body, reply, mark-read.
 *
 * Android-free like everything else in this module, so the whole message set is
 * host-testable. Each encoder returns the channel type alongside the payload;
 * the driver frames them with [MessageCodec] and sends them with
 * `GalConnection.send(channelId, type, payload)` -- always encrypted, never
 * CONTROL-flagged, like all service-channel traffic.
 */
object MessagingCodec {

    /** Phone -> HU: a thread-list snapshot. */
    fun encodeThreads(threads: List<MessagingThread>): Pair<Int, ByteArray> =
        GalMessage.Notification.THREADS to MessagingThreads.newBuilder()
            .addAllThreads(threads)
            .build()
            .toByteArray()

    /** Phone -> HU: one message body posted. */
    fun encodeMessage(message: MessagingMessage): Pair<Int, ByteArray> =
        GalMessage.Notification.MESSAGE to message.toByteArray()

    /** Phone -> HU: a thread dismissed on the phone or read elsewhere. */
    fun encodeDismiss(threadId: String, messageId: String? = null): Pair<Int, ByteArray> {
        val builder = MessagingDismiss.newBuilder().setThreadId(threadId)
        if (messageId != null) builder.messageId = messageId
        return GalMessage.Notification.DISMISS to builder.build().toByteArray()
    }

    /**
     * Parses a HU -> phone ACTION payload, throwing on malformed bytes like
     * every other generated parser here. Callers that must survive hostile
     * traffic use [decodeInbound] instead.
     */
    fun decodeAction(payload: ByteArray): MessagingAction =
        MessagingAction.parseFrom(payload)

    /**
     * Classifies one inbound ch14 message, or null when it is not a user
     * action we handle: a type other than ACTION, an uninitialized ACTION
     * (missing a required field), or an action type with no mapping. Null
     * means "observed and ignored", never an error -- the pump must survive
     * whatever a head unit sends.
     */
    fun decodeInbound(type: Int, payload: ByteArray): InboundMessagingAction? {
        if (type != GalMessage.Notification.ACTION) return null
        val action = runCatching { MessagingAction.parseFrom(payload) }.getOrNull()
            ?: return null
        if (!action.isInitialized) return null
        return when (action.action) {
            MessagingActionType.MESSAGE_REPLY -> InboundMessagingAction.Reply(
                threadId = action.threadId,
                text = action.replyText,
            )
            MessagingActionType.MESSAGE_MARK_READ -> InboundMessagingAction.MarkRead(
                threadId = action.threadId,
            )
            MessagingActionType.MESSAGE_VOICE_REPLY_BEGIN -> InboundMessagingAction.VoiceReplyBegin(
                threadId = action.threadId,
            )
            else -> null
        }
    }
}
