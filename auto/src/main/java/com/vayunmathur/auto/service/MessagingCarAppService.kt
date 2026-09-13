package com.vayunmathur.auto.service

import android.util.Log
import android.app.RemoteInput
import com.vayunmathur.auto.notifications.MessageMirrorBus
import com.vayunmathur.auto.notifications.MessageReplyRoute
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
    /** Resolves a context for firing reply intents; read per send, null drops. */
    private val context: () -> android.content.Context? = { null },
) {
    val channelId: Int get() = GalService.NOTIFICATION.id

    private var open = false
    private var mirror: AutoCloseable? = null
    private val threads = LinkedHashMap<String, MessagingThread>()

    /**
     * Reply/mark-read routes per thread, replaced on every repost. A route is
     * only as fresh as the last notification; firing a stale intent fails
     * with a count, never a retry -- the app reposts and the route refreshes.
     */
    private val routes = LinkedHashMap<String, MessageReplyRoute>()

    /** The channel grant arrived; start mirroring the phone side. */
    fun onChannelOpen() {
        open = true
        Log.i(TAG, "messaging channel open; mirroring message threads")
        mirror = MessageMirrorBus.register { thread, message, route ->
            onPhoneMessage(thread, message, route)
        }
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
                sendReply(action.threadId, action.text)
            }
            is InboundMessagingAction.MarkRead -> {
                onEvent(MessagingEvent.MarkedRead(action.threadId))
                sendMarkRead(action.threadId)
            }
            is InboundMessagingAction.VoiceReplyBegin -> audio.beginVoiceReply(action.threadId) { text ->
                onEvent(MessagingEvent.ReplyReceived(action.threadId))
                sendReply(action.threadId, text)
            }
        }
    }

    /**
     * Hands head-unit reply text to the message app through its own reply
     * intent, exactly like gearhead's `lwg.c()`: the `RemoteInput` result
     * rides an explicit intent into `PendingIntent.send()`. This service still
     * knows GAL, not SMS -- sending stays the message app's job; here we only
     * fire what it advertised. Missing routes and failed sends drop with a
     * count, never a crash.
     */
    private fun sendReply(threadId: String, text: String) {
        val route = routes[threadId]
        val intent = route?.replyIntent
        val remoteInput = route?.remoteInput
        if (intent == null || remoteInput == null) {
            Log.i(TAG, "reply for $threadId with no app route; dropping")
            onEvent(MessagingEvent.ReplyFailed(threadId, "no-route"))
            onReply(threadId, text)
            return
        }
        val appContext = context() ?: run {
            Log.i(TAG, "reply for $threadId with no context; dropping")
            onEvent(MessagingEvent.ReplyFailed(threadId, "no-context"))
            onReply(threadId, text)
            return
        }
        runCatching {
            val fillIn = android.content.Intent()
            val results = android.os.Bundle()
            results.putCharSequence(remoteInput.resultKey, text)
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), fillIn, results)
            intent.send(appContext, 0, fillIn)
        }
            .onSuccess {
                Log.i(TAG, "reply for $threadId handed to message app")
                onEvent(MessagingEvent.ReplySent(threadId))
            }
            .onFailure {
                Log.w(TAG, "reply for $threadId failed to send", it)
                onEvent(MessagingEvent.ReplyFailed(threadId, "send-failed"))
            }
        onReply(threadId, text)
    }

    /** Fires the thread's mark-read intent, if the app advertised one. */
    private fun sendMarkRead(threadId: String) {
        val readIntent = routes[threadId]?.readIntent ?: run {
            Log.d(TAG, "mark-read for $threadId with no app route")
            return
        }
        runCatching { readIntent.send() }
            .onFailure { Log.w(TAG, "mark-read for $threadId failed", it) }
    }

    /** A phone notification arrived: snapshot the threads, post the body, read it aloud. */
    private fun onPhoneMessage(
        thread: MessagingThread,
        message: MessagingMessage,
        route: MessageReplyRoute?,
    ) {
        if (!open) return
        threads[thread.threadId] = thread
        if (route != null) routes[route.threadId] = route
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
        routes.clear()
    }

    private companion object {
        const val TAG = "MaAuto.Messaging"
    }
}
