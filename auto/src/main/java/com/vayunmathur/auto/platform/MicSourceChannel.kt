package com.vayunmathur.auto.platform

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.vayunmathur.auto.protocol.AudioCodec
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage

/**
 * The microphone source channel: the car's microphone, service 6.
 *
 * Direction is the reverse of the sinks: the CAR sends bulk PCM (0x0000 with
 * an 8-byte timestamp prefix, or prefix-less 0x0001) plus 0x8006 mic
 * requests, and the PHONE answers each chunk with a 0x8004 ack. There is no
 * setup/start handshake on this channel -- the head unit opens it and starts
 * talking, and we ack from the first chunk.
 *
 * Retention is permission-gated ([MicPermission]): without RECORD_AUDIO no
 * voice data is buffered -- chunks are acked and counted only, so the head
 * unit sees a live endpoint while the phone keeps nothing. With the grant, a
 * voice-reply turn buffers chunks until [endTurn] and hands the PCM to its
 * callback; a TTS/speak turn needs no buffer and only counts.
 *
 * All turn work lands on the mic's own thread -- never the pump thread --
 * and the only thing shared with the pump is [GalConnection.send], which
 * enqueues and hands the flush to the connection's single I/O thread
 * (thread-safe from anywhere, main thread included).
 */
class MicSourceChannel(
    private val connection: GalConnection,
    /** Whether buffered retention is allowed; read per turn, so a mid-session grant applies. */
    private val retentionAllowed: () -> Boolean = { false },
    /** Fire-and-forget observations for the phone status screen; never gates. */
    private val onEvent: (AudioEvent) -> Unit = {},
) {
    val channelId: Int get() = com.vayunmathur.auto.protocol.GalService.AUDIO_SOURCE.id

    private var open = false

    @Volatile
    private var retained: ByteArray? = null

    @Volatile
    private var turnCallback: ((ByteArray) -> Unit)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** The grant arrived: mark the channel open. Called once per channel open. */
    fun onChannelOpen() {
        open = true
        startMicThread()
        Log.i(TAG, "mic channel open; acking upstream chunks")
    }

    /** One message for this channel; anything else is ignored, never misparsed. */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.w(TAG, "ignoring 0x${type.toString(16)} for channel $channelId")
            return
        }
        if (type == GalMessage.Microphone.REQUEST) {
            Log.d(TAG, "mic request (${payload.size}B); acking")
            ack()
            return
        }
        val chunk = AudioCodec.decodeMicData(type, payload)
        if (chunk == null) {
            Log.d(TAG, "unhandled mic message 0x${type.toString(16)}")
            return
        }
        val target = handler ?: run {
            ack()
            return
        }
        val snapshot = chunk.pcm.copyOf()
        target.post {
            if (retentionAllowed() && retained != null) {
                retained = retained!! + snapshot
            } else {
                onEvent(AudioEvent.MicIdle)
            }
            ack()
        }
    }

    /**
     * Opens a voice-reply turn for [MessagingAudio.beginVoiceReply]: retains
     * chunks until [endTurn] hands the PCM to [onResult]. No retention without
     * the permission grant -- the callback never fires and the turn yields
     * nothing, which the caller treats as "no reply".
     */
    fun beginTurn(onResult: (ByteArray) -> Unit) {
        val target = handler ?: return
        target.post {
            if (!retentionAllowed()) {
                Log.w(TAG, "mic turn without permission; retaining nothing")
                turnCallback = null
                retained = null
                return@post
            }
            retained = ByteArray(0)
            turnCallback = onResult
            Log.i(TAG, "mic turn started")
        }
    }

    /** Closes the turn: the buffered PCM goes to the turn callback, if any. */
    fun endTurn() {
        val target = handler ?: return
        target.post {
            val pcm = retained ?: ByteArray(0)
            val callback = turnCallback
            retained = null
            turnCallback = null
            if (callback != null && pcm.isNotEmpty()) {
                onEvent(AudioEvent.MicTurn(pcm.size.toLong()))
                callback(pcm)
            } else {
                Log.d(TAG, "mic turn ended with ${pcm.size}B; yielding nothing")
            }
        }
    }

    fun release() {
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
        open = false
        retained = null
        turnCallback = null
    }

    private fun ack() {
        val (type, payload) = AudioCodec.encodeMicAck()
        runCatching { connection.send(channelId, type, payload) }
        onEvent(AudioEvent.MicAcked)
    }

    private fun startMicThread() {
        if (thread != null) return
        thread = HandlerThread("ma-auto-mic").also {
            it.start()
            handler = Handler(it.looper)
        }
    }

    private companion object {
        const val TAG = "MaAuto.Mic"
    }
}
