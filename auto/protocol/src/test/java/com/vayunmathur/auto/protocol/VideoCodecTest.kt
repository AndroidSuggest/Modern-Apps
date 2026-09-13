package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.UpdateUiConfigRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pins the video UI-config update (service 2, 0x800A): the `xow` envelope
 * shape and the honest-empty default. Exact bytes, because a round-trip
 * passes happily with a wrong field number and the failure only shows up as
 * a head unit that ignores us.
 */
class VideoCodecTest {

    @Test
    fun `an update with no config sends the empty envelope`() {
        // The `xop` interior is unrecovered, so the default send is zero
        // bytes -- never invented inner fields.
        val (type, payload) = VideoCodec.encodeUpdateUiConfig()
        assertEquals(GalMessage.Video.UPDATE_UI_CONFIG_REQUEST, type)
        assertContentEquals(ByteArray(0), payload)
    }

    @Test
    fun `opaque config rides field 1 length-delimited`() {
        val (type, payload) = VideoCodec.encodeUpdateUiConfig(byteArrayOf(0x01, 0x02))
        assertEquals(GalMessage.Video.UPDATE_UI_CONFIG_REQUEST, type)
        assertContentEquals(byteArrayOf(0x0A, 0x02, 0x01, 0x02), payload)
        val parsed = UpdateUiConfigRequest.parseFrom(payload)
        assertContentEquals(byteArrayOf(0x01, 0x02), parsed.config.toByteArray())
    }

    @Test
    fun `the update id sits in the service range`() {
        assertEquals(0x800A, GalMessage.Video.UPDATE_UI_CONFIG_REQUEST)
    }
}
