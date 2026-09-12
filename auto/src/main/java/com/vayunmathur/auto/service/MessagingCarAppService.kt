package com.vayunmathur.auto.service

import android.util.Log
import com.vayunmathur.auto.notifications.MessageMirrorBus
import com.vayunmathur.auto.platform.MessagingAudio
import com.vayunmathur.auto.platform.MessagingEvent
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.InboundMessagingAction
import com.vayunmathur.auto.protocol.MessagingCodec
import com.vayunmathur.auto.protocol.gal.MessagingMessage
import com.vayunmathur.auto.protocol.gal.MessagingThread

/**
 * The messaging channel: mirrors phone threads onto ch14 and routes head-unit replies back.
 *
 * Bring-up needs nothing: ch14 opens in the generic wire-order pass like every other
 * service, and an unknown payload earns at worst a bare MessageError on ch14 while
 * video carries on. Posting starts on [onChannelOpen], never before -- sending on a
 * channel the head unit has not granted earns a 0xff.
 *
 * Notify/TTS goes out through [audio] (audio-dev's ch4 sink when it lands; silent
 * until then), and a voice-reply turn opens a ch6 mic turn whose transcription
 * returns as text and is treated exactly like a typed reply. Replies leave the
 * phone through [onReply]: this service knows GAL, not SMS -- sending is the
 * message app's job (direct reply via the notification, or the default-SMS path),
 * and the callback is where that lands.
 */
class MessagingCarAppService(
    private val connection: GalConnection,
    private val audio: MessagingAudio = MessagingAudio.NoOp,
    private val onEvent: (MessagingEvent) -> Unit = {},
    private val onReply: (threadId: String, text: String) -> Unit = { _, _ -> },
) {
    val channelId: Int get() = GalService.NOTIFICATION.id

    private var open = false
    private var mirror: AutoCloseable? = null
    private val threads = LinkedHashMap<String, MessagingThread>()

    /** The channel grant arrived; start mirroring the phone side. */
    fun onChannelOpen() {
        open = true
        Log.i(TAG, "messaging channel open; mirroring message threads")
        mirror = MessageMirrorBus.register { thread, message -> onPhoneMessage(thread, message) }
    }

    /** One message for this channel; anything else is ignored, never misparsed. */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.w(TAG, "ignoring 0x${type.toString(16)} for channel $channelId")
            return
        }
        // Only ACTION arrives here; anything else (a head unit echoing our own
        // posts, a future extension) is observed and ignored.
        val action = MessagingCodec.decodeInbound(type, payload)
        if (action == null) {
            Log.d(TAG, "unhandled messaging message 0x${type.toString(16)}")
            return
        }
        when (action) {
            is InboundMessagingAction.Reply -> {
                onEvent(MessagingEvent.ReplyReceived(action.threadId))
                onReply(action.threadId, action.text)
            }
            is InboundMessagingAction.MarkRead -> onEvent(MessagingEvent.MarkedRead(action.threadId))
            is InboundMessagingAction.VoiceReplyBegin -> audio.beginVoiceReply(action.threadId) { text ->
                onEvent(MessagingEvent.ReplyReceived(action.threadId))
                onReply(action.threadId, text)
            }
        }
    }

    /** A phone notification arrived: snapshot the threads, post the body, read it aloud. */
    private fun onPhoneMessage(thread: MessagingThread, message: MessagingMessage) {
        if (!open) return
        threads[thread.threadId] = thread
        val (threadsType, threadsPayload) = MessagingCodec.encodeThreads(threads.values.toList())
        connection.send(channelId, threadsType, threadsPayload)
        onEvent(MessagingEvent.ThreadsPosted(threads.size))
        val (messageType, messagePayload) = MessagingCodec.encodeMessage(message)
        connection.send(channelId, messageType, messagePayload)
        onEvent(MessagingEvent.MessagePosted(thread.threadId))
        audio.speakOnCar(message.body)
    }

    fun release() {
        runCatching { mirror?.close() }
        mirror = null
        open = false
    }

    private companion object {
        const val TAG = "MaAuto.Messaging"
    }
}
