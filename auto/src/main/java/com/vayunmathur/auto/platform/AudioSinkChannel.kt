package com.vayunmathur.auto.platform

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.vayunmathur.auto.protocol.AudioCodec
import com.vayunmathur.auto.protocol.AudioGain
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.FocusArbitration
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.InboundAudio
import com.vayunmathur.auto.protocol.SelectedConfig
import com.vayunmathur.auto.protocol.gal.AudioFocusRequestType
import com.vayunmathur.auto.protocol.gal.Service
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * One audio sink channel: [AudioSinkRole.SYSTEM] (service 4, transient TTS
 * and pings) or [AudioSinkRole.MEDIA] (service 5, music).
 *
 * Bring-up mirrors video's (`requestSetup` on the grant -> CONFIG answered
 * with a focus ask on control 0x18 -> START with the confirmed config), and
 * each sink's samples are written by its own audio thread -- never the pump
 * thread, never the video vsync drain, never the main thread's socket: the only
 * thing shared with the pump is [GalConnection.send], which enqueues and hands
 * the flush to the connection's single I/O thread (see `GalConnection`'s
 * one-thread-many-senders discipline). The
 * sink's state is written on the audio thread and read off it only through
 * the atomic frame counter and the [AudioSinkStatus] snapshots, which hop to
 * the pump/main threads via the thread-safe `StateFlow` set in
 * [AutoSessionState.onAudioEvent].
 *
 * Feeding is file-shaped on purpose: the only phone-side audio sources in
 * scope are TTS WAV bytes (system sink) and decoded PCM (media sink). The
 * sink resamples to the negotiated rate, ducks or mutes per the arbitrated
 * focus ([AudioCodec.gainFor]), and frames at the head unit's MTU --
 * [FRAMES_PER_PACKET] of 16-bit mono samples per 0x0000 bulk frame.
 *
 * An unsupported discovery (non-PCM codec, no sane config) parks the sink in
 * [AudioSinkState.UNSUPPORTED]: observed, counted, never started. Guidance
 * (service 3) is deliberately unowned -- sensors-dev's [GuidanceChannel] stub
 * holds that shape, and this owner only answers for 4/5.
 */
class AudioSinkChannel(
    private val role: AudioSinkRole,
    private val service: Service,
    private val connection: GalConnection,
    /** Arbitrated focus; the service reads it off the session. */
    private val focus: () -> FocusArbitration = { FocusArbitration() },
    /** Fire-and-forget observations for the phone status screen; never gates. */
    private val onEvent: (AudioEvent) -> Unit = {},
) {
    val channelId: Int get() = service.id

    @Volatile
    private var state: AudioSinkState = AudioSinkState.IDLE

    @Volatile
    private var selected: SelectedConfig? = null

    @Volatile
    private var sessionId = -1

    @Volatile
    private var lastGain: AudioGain = AudioGain.MUTED

    /** Frames consumed by the head unit, mod-256 unwrapped for the UI. */
    private val ackSeq = AtomicInteger(-1)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /**
     * The grant arrived: claim the channel and request audio focus. Called
     * once per channel open -- sending on a channel the head unit has not
     * opened earns a bare MessageError (0xff).
     */
    fun requestSetup() {
        val pick = AudioCodec.selectConfig(service)
        if (pick == null) {
            state = AudioSinkState.UNSUPPORTED
            onEvent(AudioEvent.SinkSetup(role.name, "unsupported"))
            Log.w(TAG, "$role sink unsupported (non-PCM or no config); parked")
            publish()
            return
        }
        selected = pick
        state = AudioSinkState.SETUP
        startAudioThread()
        val (type, payload) = AudioCodec.encodeSetup()
        connection.send(channelId, type, payload)
        connection.send(connection.session.requestAudioFocus(focusRequestType()))
        onEvent(
            AudioEvent.SinkSetup(
                role.name,
                "${pick.config.samplingRate}Hz ${pick.config.numberOfBits}b " +
                    "${pick.config.numberOfChannels}ch config ${pick.index}",
            ),
        )
        Log.i(TAG, "$role sink setup requested (config ${pick.index})")
        publish()
    }

    /** One message for this channel; anything else is ignored, never misparsed. */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.w(TAG, "ignoring 0x${type.toString(16)} for channel $channelId")
            return
        }
        when (val inbound = AudioCodec.decodeSinkInbound(type, payload)) {
            is InboundAudio.Config -> onConfig(inbound)
            is InboundAudio.Ack -> {
                ackSeq.set(inbound.ackSeq?.toInt() ?: ackSeq.get())
                onEvent(AudioEvent.AckReceived(role.name, inbound.ackSeq))
            }
            is InboundAudio.Sync -> onEvent(AudioEvent.SyncReceived(role.name))
            is InboundAudio.Observed -> Log.d(TAG, "$role unhandled audio message 0x${type.toString(16)}")
        }
    }

    /**
     * Queue PCM for the car. Safe from any thread: the copy hops to the audio
     * thread, which resamples, gains and frames it. [sampleRateHz] is the
     * buffer's native rate; 16-bit mono little-endian.
     */
    fun feedPcm(pcm: ByteArray, sampleRateHz: Int) {
        val target = handler ?: return
        val snapshot = pcm.copyOf()
        target.post { writeFramed(snapshot, sampleRateHz) }
    }

    /**
     * Queue a TTS WAV file for the car. The WAV unwraps to its native rate
     * ([AudioCodec.stripWavToPcm16Mono]) and rides the same path as [feedPcm].
     * Non-PCM or malformed bytes are dropped with a count, never a crash.
     */
    fun feedWav(wav: ByteArray) {
        val target = handler ?: return
        val snapshot = wav.copyOf()
        target.post {
            val pcm = AudioCodec.stripWavToPcm16Mono(snapshot)
            if (pcm == null) {
                Log.w(TAG, "$role dropping malformed TTS wav (${snapshot.size}B)")
                onEvent(AudioEvent.TtsDropped("malformed"))
                return@post
            }
            writeFramed(pcm.pcm, pcm.sampleRateHz)
            onEvent(AudioEvent.TtsSpoken(pcm.pcm.size / 2))
        }
    }

    /** Recompute the gain against fresh arbitration; called on every focus change. */
    fun onFocusChanged() {
        lastGain = currentGain()
        publish()
        if (lastGain == AudioGain.MUTED) {
            Log.d(TAG, "$role muted by focus")
        }
    }

    fun release() {
        if (state == AudioSinkState.STARTED && sessionId >= 0) {
            val (type, payload) = AudioCodec.encodeStop()
            runCatching { connection.send(channelId, type, payload) }
        }
        state = AudioSinkState.STOPPED
        onEvent(AudioEvent.SinkStopped(role.name))
        publish()
        handler?.removeCallbacksAndMessages(null)
        handler = null
        thread?.quitSafely()
        thread = null
    }

    private fun onConfig(inbound: InboundAudio.Config) {
        val pick = selected ?: AudioCodec.selectConfig(service) ?: run {
            state = AudioSinkState.UNSUPPORTED
            publish()
            return
        }
        val confirmed = AudioCodec.confirmConfig(inbound.response, pick, service) ?: run {
            state = AudioSinkState.UNSUPPORTED
            Log.w(TAG, "$role head unit confirmed nothing usable; parked")
            publish()
            return
        }
        selected = confirmed
        sessionId = nextSessionId()
        val (type, payload) = AudioCodec.encodeStart(sessionId, confirmed.index)
        connection.send(channelId, type, payload)
        state = AudioSinkState.STARTED
        lastGain = currentGain()
        onEvent(AudioEvent.SinkStarted(role.name, confirmed.index))
        Log.i(TAG, "$role sink started (session $sessionId, config ${confirmed.index})")
        publish()
    }

    private fun writeFramed(pcm: ByteArray, sampleRateHz: Int) {
        val config = selected ?: run {
            Log.d(TAG, "$role dropping ${pcm.size}B with no confirmed config")
            return
        }
        if (state != AudioSinkState.STARTED) {
            Log.d(TAG, "$role dropping ${pcm.size}B while $state")
            return
        }
        val gain = currentGain()
        lastGain = gain
        if (gain == AudioGain.MUTED) {
            Log.d(TAG, "$role muted; dropping ${pcm.size}B")
            return
        }
        val resampled = AudioCodec.resampleLinearMono16(pcm, sampleRateHz, config.config.samplingRate)
        val scaled = if (gain == AudioGain.DUCKED) {
            AudioCodec.scalePcm16(resampled, AudioCodec.DUCK_NUMERATOR, AudioCodec.DUCK_DENOMINATOR)
        } else {
            resampled
        }
        var offset = 0
        while (offset < scaled.size) {
            val end = minOf(offset + FRAME_BYTES, scaled.size)
            val frame = timestampedFrame(scaled.copyOfRange(offset, end))
            connection.send(channelId, GalMessage.Media.DATA_WITH_TIMESTAMP, frame)
            onEvent(AudioEvent.FramesSent(role.name, frame.size.toLong()))
            offset = end
        }
        publish()
    }

    private fun timestampedFrame(audio: ByteArray): ByteArray =
        ByteBuffer.allocate(AudioCodec.TIMESTAMP_BYTES + audio.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(SystemClock.elapsedRealtimeNanos() / 1_000)
            .put(audio)
            .array()

    private fun currentGain(): AudioGain {
        val arbitration = focus()
        return AudioCodec.gainFor(role, arbitration.audioFocus, arbitration.videoFocus)
    }

    private fun focusRequestType(): AudioFocusRequestType =
        if (role == AudioSinkRole.SYSTEM) {
            AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT
        } else {
            AudioFocusRequestType.AUDIO_FOCUS_GAIN
        }

    private fun startAudioThread() {
        if (thread != null) return
        val name = "ma-auto-audio-${role.name.lowercase()}"
        thread = HandlerThread(name).also {
            it.start()
            handler = Handler(it.looper)
        }
    }

    private fun publish() {
        onEvent(AudioEvent.SinkStatus(AudioSinkStatus(role, state, lastGain)))
    }

    private companion object {
        const val TAG = "MaAuto.Audio"

        /** Samples per 0x0000 bulk frame: 1024 mono samples at the negotiated rate. */
        const val FRAMES_PER_PACKET = 1024
        const val FRAME_BYTES = FRAMES_PER_PACKET * 2

        private val sessions = AtomicInteger(0)
        private fun nextSessionId(): Int = sessions.getAndIncrement()
    }
}
