package com.vayunmathur.auto.platform

import android.util.Log
import com.vayunmathur.auto.protocol.AudioCodec
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.InboundAudio
import com.vayunmathur.auto.protocol.SelectedConfig
import com.vayunmathur.auto.protocol.gal.Service

/**
 * The guidance audio sink: claims ch3 through the shared sink handshake,
 * then streams while the phone-side TTS session owns it.
 *
 * Bring-up mirrors the other sinks (`requestSetup` -> CONFIG observed) but
 * the stream itself is TTS-owned: `CarTts.start()` arms it through
 * [startStream] and `CarTts.stop()` parks it through [stopStream], so an
 * open sink with no TTS session stays set-up-but-idle -- "no guidance to
 * play" stated by idleness rather than implied by absence. A start that
 * lands before CONFIG confirms only arms ([startRequested]); the confirm
 * then starts the stream instead of leaving it parked.
 *
 * Reuses audio-dev's [AudioCodec] for the shared `jdk` sink shape (setup
 * encoding, config negotiation, inbound classification) rather than
 * re-encoding it: ch3 differs from ch4/5 only by service id, not wire
 * protocol. ch3 opens in the generic wire-order pass; the setup goes out on
 * the grant -- sending on a channel the head unit has not opened earns a
 * bare MessageError (0xff).
 *
 * Route by channel id: 0x8004 here would be a MediaAck, parsed by the shared
 * codec, never confused with the ch7 SensorError -- the 0x04 CONTROL frame
 * flag never enters routing (HANDOFF.md section 9).
 */
class GuidanceChannel(
    private val service: Service,
    private val connection: GalConnection,
    /**
     * Whether the phone-side TTS session is running. Read on the grant (to
     * arm a session that started before ch3 opened) and on CONFIG confirm;
     * the service wires the TTS owner's lifetime here.
     */
    private val ttsActive: () -> Boolean = { false },
    private val onEvent: (SensorEvent) -> Unit = {},
) {
    val channelId: Int get() = service.id

    private var open = false
    private var selected: SelectedConfig? = null
    private var sessionId = -1

    /**
     * Set by [startStream] before any CONFIG confirmed -- or by the grant
     * when [ttsActive] -- and honored on confirm. Cleared by [stopStream]
     * and [release]: a parked stream never restarts itself.
     */
    private var startRequested = false

    /** The grant arrived: claim the channel for audio. Called once per channel open. */
    fun onChannelOpen() {
        open = true
        val (type, payload) = AudioCodec.encodeSetup()
        connection.send(channelId, type, payload)
        Log.i(TAG, "guidance setup requested")
        onEvent(SensorEvent.GuidanceSetup)
        // The TTS session started before ch3 opened: arm the stream now so
        // the CONFIG confirm starts it instead of parking it idle.
        if (ttsActive()) startStream()
    }

    /** One message for this channel; anything else is ignored, never misparsed. */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.w(TAG, "ignoring 0x${type.toString(16)} for channel $channelId")
            return
        }
        when (val inbound = AudioCodec.decodeSinkInbound(type, payload)) {
            is InboundAudio.Config -> {
                // Confirm against discovery like the other sinks, then start
                // when the TTS session armed the stream -- otherwise stay
                // idle: confirmed-but-never-started is the no-TTS state.
                val pick = selected ?: AudioCodec.selectConfig(service)
                selected = pick?.let { AudioCodec.confirmConfig(inbound.response, it, service) }
                Log.i(TAG, "guidance configured (accepted=${selected != null})")
                onEvent(SensorEvent.GuidanceConfigured(accepted = selected != null))
                if (startRequested && selected != null) startStream(sessionId.coerceAtLeast(0))
            }
            is InboundAudio.Ack -> Log.d(TAG, "guidance ack (no stream running)")
            is InboundAudio.Sync -> Log.d(TAG, "guidance sync pulse")
            is InboundAudio.Observed -> Log.d(TAG, "unhandled guidance message 0x${type.toString(16)}")
        }
    }

    /**
     * Starts streaming the confirmed configuration for the TTS session.
     * Called by the TTS owner (`CarTts.start`, plus the grant when the
     * session predates ch3): arming before CONFIG only sets [startRequested]
     * and the confirm starts the stream. Re-sending START for a live stream
     * is harmless (same session id and config).
     */
    fun startStream(sessionId: Int = 0) {
        startRequested = true
        val config = selected ?: run {
            Log.i(TAG, "guidance start armed; starts on CONFIG confirm")
            return
        }
        this.sessionId = sessionId
        val (type, payload) = AudioCodec.encodeStart(sessionId, config.index)
        connection.send(channelId, type, payload)
        Log.i(TAG, "guidance stream started (session $sessionId, config ${config.index})")
    }

    /**
     * Parks the stream for the TTS session; the channel stays open and idle.
     * Called by the TTS owner (`CarTts.stop`) and on teardown. Disarms, so a
     * later CONFIG confirm cannot restart a parked stream by itself.
     */
    fun stopStream() {
        startRequested = false
        if (sessionId < 0) return
        val (type, payload) = AudioCodec.encodeStop()
        connection.send(channelId, type, payload)
        sessionId = -1
        Log.i(TAG, "guidance stream stopped")
    }

    fun release() {
        runCatching { stopStream() }
        open = false
        selected = null
    }

    private companion object {
        const val TAG = "MaAuto.Guidance"
    }
}
