package com.vayunmathur.auto.platform

import android.util.Log
import com.vayunmathur.auto.protocol.AudioCodec
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.InboundAudio
import com.vayunmathur.auto.protocol.SelectedConfig
import com.vayunmathur.auto.protocol.gal.Service

/**
 * The guidance audio sink stub: claims ch3 through the shared sink handshake,
 * then stays idle.
 *
 * Bring-up mirrors the other sinks (`requestSetup` -> CONFIG observed) but
 * deliberately never sends START: an open, set-up-but-idle sink expects no
 * frames, while a started stream the phone never feeds would leave the head
 * unit waiting on audio that never comes. Silence here is the well-formed
 * answer -- "no guidance to play" stated by idleness rather than implied by
 * absence. Live TTS starts the stream through this same owner (audio-dev /
 * maps-dev seam: call [startStream], feed PCM, call [stopStream]).
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
    private val onEvent: (SensorEvent) -> Unit = {},
) {
    val channelId: Int get() = service.id

    private var open = false
    private var selected: SelectedConfig? = null
    private var sessionId = -1

    /** The grant arrived: claim the channel for audio. Called once per channel open. */
    fun onChannelOpen() {
        open = true
        val (type, payload) = AudioCodec.encodeSetup()
        connection.send(channelId, type, payload)
        Log.i(TAG, "guidance setup requested")
        onEvent(SensorEvent.GuidanceSetup)
    }

    /** One message for this channel; anything else is ignored, never misparsed. */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.w(TAG, "ignoring 0x${type.toString(16)} for channel $channelId")
            return
        }
        when (val inbound = AudioCodec.decodeSinkInbound(type, payload)) {
            is InboundAudio.Config -> {
                // Confirm against discovery like the other sinks, then stay
                // idle: confirmed-but-never-started is the stub state.
                val pick = selected ?: AudioCodec.selectConfig(service)
                selected = pick?.let { AudioCodec.confirmConfig(inbound.response, it, service) }
                Log.i(TAG, "guidance configured (accepted=${selected != null}); staying idle")
                onEvent(SensorEvent.GuidanceConfigured(accepted = selected != null))
            }
            is InboundAudio.Ack -> Log.d(TAG, "guidance ack (no stream running)")
            is InboundAudio.Sync -> Log.d(TAG, "guidance sync pulse")
            is InboundAudio.Observed -> Log.d(TAG, "unhandled guidance message 0x${type.toString(16)}")
        }
    }

    /**
     * Live-guidance seam (audio-dev / maps-dev): start streaming the
     * confirmed configuration. A no-op until a CONFIG confirmed one --
     * starting with nothing confirmed would send frames the head unit never
     * agreed to decode.
     */
    fun startStream(sessionId: Int = 0) {
        val config = selected ?: run {
            Log.w(TAG, "guidance start with no confirmed config; staying idle")
            return
        }
        this.sessionId = sessionId
        val (type, payload) = AudioCodec.encodeStart(sessionId, config.index)
        connection.send(channelId, type, payload)
        Log.i(TAG, "guidance stream started (session $sessionId, config ${config.index})")
    }

    /** Live-guidance seam: stop the stream; the channel stays open and idle. */
    fun stopStream() {
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
