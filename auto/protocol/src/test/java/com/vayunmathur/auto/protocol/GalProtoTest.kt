package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AudioConfiguration
import com.vayunmathur.auto.protocol.gal.AuthComplete
import com.vayunmathur.auto.protocol.gal.ByeByeReason
import com.vayunmathur.auto.protocol.gal.ByeByeRequest
import com.vayunmathur.auto.protocol.gal.CallAvailabilityStatus
import com.vayunmathur.auto.protocol.gal.ChannelOpenResponse
import com.vayunmathur.auto.protocol.gal.ChannelOpenRequest
import com.vayunmathur.auto.protocol.gal.MediaAck
import com.vayunmathur.auto.protocol.gal.MediaCodecType
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.MicrophoneRequest
import com.vayunmathur.auto.protocol.gal.PingRequest
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.ServiceDiscoveryResponse
import com.vayunmathur.auto.protocol.gal.UpdateUiConfigRequest
import com.vayunmathur.auto.protocol.gal.VideoConfiguration
import com.vayunmathur.auto.protocol.gal.VideoResolution
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the GAL wire format.
 *
 * These assert exact bytes rather than round-tripping, because a round-trip passes happily
 * with a wrong field number: the failure only shows up as a head unit that ignores us. The
 * expected bytes below are derived from the field numbers recovered from the Android Auto
 * APK, so if someone renumbers a field the test says so immediately.
 */
class GalProtoTest {

    @Test
    fun `ChannelOpenRequest puts priority first and zigzags it`() {
        // The control channel opens itself at priority -128, which is why field 1 is sint32
        // rather than int32 -- as int32 a negative would cost ten bytes of varint.
        val bytes = ChannelOpenRequest.newBuilder()
            .setPriority(-128)
            .setServiceId(2)
            .build()
            .toByteArray()

        assertContentEquals(
            byteArrayOf(
                0x08, 0xFF.toByte(), 0x01, // field 1, varint, zigzag(-128) = 255
                0x10, 0x02, //               field 2, varint, 2
            ),
            bytes,
        )
    }

    @Test
    fun `ChannelOpenRequest refuses to build without both required fields`() {
        assertFailsWith<Exception> {
            ChannelOpenRequest.newBuilder().setServiceId(2).build()
        }
        assertFailsWith<Exception> {
            ChannelOpenRequest.newBuilder().setPriority(0).build()
        }
    }

    @Test
    fun `PingRequest carries its timestamp in field 1`() {
        // Read off the APK as field 1, not field 2 -- a hand-read of the decompiled Java
        // suggested 2 because `b` there is the hasbit word, not the first field.
        val bytes = PingRequest.newBuilder().setTimestamp(1).build().toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x01), bytes)
    }

    @Test
    fun `AuthComplete status is a plain int32 in field 1`() {
        assertContentEquals(
            byteArrayOf(0x08, 0x00),
            AuthComplete.newBuilder().setStatus(0).build().toByteArray(),
        )
    }

    @Test
    fun `a channel-open response pins status in field 1`() {
        // `xir`: single enum field 1. STATUS_SUCCESS = 0 encodes as 08 00.
        assertContentEquals(
            byteArrayOf(0x08, 0x00),
            ChannelOpenResponse.newBuilder().setStatus(0).build().toByteArray(),
        )
    }

    @Test
    fun `ByeByeRequest encodes the reason enum by value`() {
        val bytes = ByeByeRequest.newBuilder()
            .setReason(ByeByeReason.DEVICE_SWITCH)
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x08, 0x02), bytes)
    }

    @Test
    fun `AudioConfiguration needs all three fields`() {
        assertFailsWith<Exception> {
            AudioConfiguration.newBuilder().setSamplingRate(48000).build()
        }
        val config = AudioConfiguration.newBuilder()
            .setSamplingRate(48000)
            .setNumberOfBits(16)
            .setNumberOfChannels(2)
            .build()
        assertEquals(48000, config.samplingRate)
    }

    @Test
    fun `a discovery response parses back into its services`() {
        val response = ServiceDiscoveryResponse.newBuilder()
            .setHeadUnitName("Pioneer")
            .addServices(
                Service.newBuilder()
                    .setId(GalService.VIDEO_SINK.id)
                    .setMediaSink(
                        MediaSinkService.newBuilder()
                            .setAvailableType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                            .addVideoConfigs(
                                VideoConfiguration.newBuilder()
                                    .setCodecResolution(VideoResolution.VIDEO_1920x1080)
                                    .setFrameRate(60)
                                    .setDensity(160),
                            ),
                    ),
            )
            .build()

        val parsed = ServiceDiscoveryResponse.parseFrom(response.toByteArray())
        assertEquals("Pioneer", parsed.headUnitName)
        val service = parsed.servicesList.single()
        assertEquals(GalService.VIDEO_SINK, GalService.fromId(service.id))
        assertTrue(service.hasMediaSink())
        assertEquals(
            VideoResolution.VIDEO_1920x1080,
            service.mediaSink.getVideoConfigs(0).codecResolution,
        )
    }

    @Test
    fun `resolution enum values match the head unit's numbering`() {
        // Off-by-one here would negotiate the wrong screen size.
        assertEquals(1, VideoResolution.VIDEO_800x480.number)
        assertEquals(2, VideoResolution.VIDEO_1280x720.number)
        assertEquals(3, VideoResolution.VIDEO_1920x1080.number)
        assertEquals(5, VideoResolution.VIDEO_3840x2160.number)
        assertEquals(9, VideoResolution.VIDEO_2160x3840.number)
    }

    @Test
    fun `codec enum values match the head unit's numbering`() {
        assertEquals(1, MediaCodecType.MEDIA_CODEC_AUDIO_PCM.number)
        assertEquals(3, MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP.number)
        assertEquals(7, MediaCodecType.MEDIA_CODEC_VIDEO_H265.number)
    }

    @Test
    fun `MediaAck carries session id, ack counter and field3 entries`() {
        // 0x8004 from the head unit. The video channel reads the optional ack
        // counter (field 2) for its sequence track and counts field3 entries,
        // so pin both the exact bytes and the accessors here.
        val bytes = MediaAck.newBuilder()
            .setSessionId(0)
            .setAck(118)
            .addField3(1L)
            .addField3(2L)
            .build()
            .toByteArray()
        assertContentEquals(
            byteArrayOf(
                0x08, 0x00, // field 1, varint, session 0
                0x10, 0x76, // field 2, varint, 118
                0x18, 0x01, // field 3, varint, 1
                0x18, 0x02, // field 3, varint, 2
            ),
            bytes,
        )
        val parsed = MediaAck.parseFrom(bytes)
        assertEquals(0, parsed.sessionId)
        assertTrue(parsed.hasAck())
        assertEquals(118, parsed.ack)
        assertEquals(2, parsed.field3Count)
    }

    @Test
    fun `MediaAck without ack leaves the counter absent`() {
        // The head unit may omit the optional counter; the channel then reports
        // a null sequence rather than a bogus zero.
        val parsed = MediaAck.parseFrom(
            MediaAck.newBuilder().setSessionId(0).build().toByteArray(),
        )
        assertTrue(!parsed.hasAck())
        assertEquals(0, parsed.field3Count)
    }

    @Test
    fun `CallAvailabilityStatus carries its flag in field 1`() {
        // Control 24 (`control.proto:225-227`): `optional bool call_available=1`.
        // True is 08 01, false is 08 00, absent stays absent.
        assertContentEquals(
            byteArrayOf(0x08, 0x01),
            CallAvailabilityStatus.newBuilder().setCallAvailable(true).build().toByteArray(),
        )
        assertContentEquals(
            byteArrayOf(0x08, 0x00),
            CallAvailabilityStatus.newBuilder().setCallAvailable(false).build().toByteArray(),
        )
        assertContentEquals(
            ByteArray(0),
            CallAvailabilityStatus.newBuilder().build().toByteArray(),
        )
    }

    @Test
    fun `MicrophoneRequest pins the recovered xkt layout`() {
        // 0x8006 (`xkt`): required int32 field 1, optional int32 field 2.
        // 3000 encodes as varint B8 17.
        val bytes = MicrophoneRequest.newBuilder()
            .setField1(3000)
            .setField2(7)
            .build()
            .toByteArray()
        assertContentEquals(
            byteArrayOf(
                0x08, 0xB8.toByte(), 0x17, // field 1, varint, 3000
                0x10, 0x07, //               field 2, varint, 7
            ),
            bytes,
        )
        val parsed = MicrophoneRequest.parseFrom(bytes)
        assertEquals(3000, parsed.field1)
        assertEquals(7, parsed.field2)
    }

    @Test
    fun `MicrophoneRequest refuses to build without its required field`() {
        assertFailsWith<Exception> {
            MicrophoneRequest.newBuilder().setField2(7).build()
        }
    }

    @Test
    fun `UpdateUiConfigRequest is an empty envelope by default`() {
        // 0x800A (`xow`): optional message field 1 carrying the unrecovered
        // `xop`. The honest send is the empty envelope: zero bytes.
        assertContentEquals(
            ByteArray(0),
            UpdateUiConfigRequest.newBuilder().build().toByteArray(),
        )
    }

    @Test
    fun `UpdateUiConfigRequest carries opaque config in field 1`() {
        // Field 1 length-delimited: tag 0x0A, length, then the opaque bytes.
        // The interior is never invented inner fields -- bytes only.
        val bytes = UpdateUiConfigRequest.newBuilder()
            .setConfig(com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x01, 0x02)))
            .build()
            .toByteArray()
        assertContentEquals(byteArrayOf(0x0A, 0x02, 0x01, 0x02), bytes)
    }
}
