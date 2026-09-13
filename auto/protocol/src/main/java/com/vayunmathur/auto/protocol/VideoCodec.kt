package com.vayunmathur.auto.protocol

import com.google.protobuf.ByteString
import com.vayunmathur.auto.protocol.gal.UpdateUiConfigRequest

/**
 * Pure video-channel (VIDEO_SINK, service 2) codecs.
 *
 * Android-free like everything else in this module, so the UI-config update
 * is host-testable. Setup/start/stop/focus encoders live with the ch2 owner
 * (`VideoSinkChannel`); this holds the messages that needed no owner-side
 * negotiation state. Sends go out with
 * `GalConnection.send(channelId, type, payload)` -- always encrypted, never
 * CONTROL-flagged, like all service-channel traffic.
 *
 * Wire values are raw (FINDINGS.md section 2, `jem` compares raw ids -- no
 * `wub.o` off-by-one on this channel).
 */
object VideoCodec {

    /**
     * Phone -> HU: UI-config update (0x800A, `xow` envelope, field 1 carries
     * `xop`).
     *
     * The `xop` interior is unrecovered (see `gal/media.proto`), so an empty
     * [config] sends the honest empty envelope (zero bytes) and a non-empty
     * one rides field 1 opaque -- never invented inner fields.
     */
    fun encodeUpdateUiConfig(config: ByteArray = ByteArray(0)): Pair<Int, ByteArray> {
        val builder = UpdateUiConfigRequest.newBuilder()
        if (config.isNotEmpty()) builder.config = ByteString.copyFrom(config)
        return GalMessage.Video.UPDATE_UI_CONFIG_REQUEST to builder.build().toByteArray()
    }
}
