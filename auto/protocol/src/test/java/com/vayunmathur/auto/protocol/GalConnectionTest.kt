package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.ChannelOpenResponse
import com.vayunmathur.auto.protocol.gal.Service
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives a [GalConnection] over an in-memory transport, so the whole stack -- transport,
 * framing, message codec, session -- runs together with no device.
 */
class GalConnectionTest {

    /** Feeds queued bytes to the connection and captures everything it writes. */
    private class FakeTransport : GalTransport {
        private var inbound = ByteBuffer.allocate(0)
        val written = ByteArrayOutputStream()
        var closed = false
            private set

        fun queue(bytes: ByteArray) {
            val combined = ByteBuffer.allocate(inbound.remaining() + bytes.size)
            combined.put(inbound)
            combined.put(bytes)
            combined.flip()
            inbound = combined
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!inbound.hasRemaining()) return -1 // end of stream
            val count = minOf(length, inbound.remaining())
            inbound.get(buffer, offset, count)
            return count
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written.write(bytes, offset, length)
        }

        override fun flush() = Unit

        override fun close() {
            closed = true
        }
    }

    private fun connection(transport: FakeTransport) =
        GalConnection(transport, TestTls.context(), deviceModel = "Pixel 8")

    /** A control frame exactly as the Desktop Head Unit puts it on the wire. */
    private fun headUnitFrame(type: Int, payload: ByteArray): ByteArray =
        FrameWriter().frame(
            channelId = 0,
            payload = MessageCodec.encode(type, payload),
            isControl = false,
            encrypted = false,
        ).single()

    private fun versionRequest(major: Int, minor: Int) =
        ByteBuffer.allocate(4).putShort(major.toShort()).putShort(minor.toShort()).array()

    @Test
    fun `a version request in yields a version response out`() {
        val transport = FakeTransport()
        val connection = connection(transport)
        transport.queue(
            headUnitFrame(GalMessage.Control.VERSION_REQUEST, versionRequest(1, 6)),
        )

        assertTrue(connection.pump())

        // Flags 0x03, FIRST|LAST with the control bit CLEAR: version negotiation is raw
        // shorts rather than protobuf. A real DHU sends its request the same way.
        assertContentEquals(
            byteArrayOf(
                0x00, 0x03, 0x00, 0x08, // channel 0, FIRST|LAST, 8-byte payload
                0x00, 0x02, //             VersionResponse
                0x00, 0x01, 0x00, 0x06, // 1.6
                0x00, 0x00, //             STATUS_SUCCESS
            ),
            transport.written.toByteArray(),
        )
        assertEquals(GalVersion(1, 6), connection.session.negotiatedVersion)
        assertEquals(SessionState.HANDSHAKING, connection.session.state)
    }

    @Test
    fun `the exact bytes a Desktop Head Unit sends are understood`() {
        // Captured from desktop-head-unit.exe 2.0 on connect. Kept verbatim because it is
        // the only ground truth we have for what a head unit actually puts on the wire.
        val fromDhu = byteArrayOf(
            0x00, 0x03, 0x00, 0x06, // channel 0, FIRST|LAST, 6-byte payload
            0x00, 0x01, //             VersionRequest
            0x00, 0x01, 0x00, 0x07, // 1.7
        )
        val transport = FakeTransport()
        val connection = connection(transport)
        transport.queue(fromDhu)

        assertTrue(connection.pump())

        assertEquals(GalVersion(1, 7), connection.session.negotiatedVersion)
        assertEquals(SessionState.HANDSHAKING, connection.session.state)
        assertTrue(transport.written.size() > 0, "the head unit should have been answered")
    }

    @Test
    fun `the channel-open request rides its target channel`() {
        // gearhead's `izd.b()` sends the open via `izl.g(this.b, ...)` -- the
        // frame channel is the channel being opened. A ch0-framed 0x7 parses
        // but is refused with STATUS_INVALID_CHANNEL (-5, Run 6).
        //
        // Framed at the FrameWriter level with a handshook engine: sending
        // encrypted data through a full connection needs a completed handshake
        // (a fresh engine's wrap() makes no progress -- see TlsCodec.wrap).
        val context = TestTls.context()
        val server = GalCredential.serverEngine(context)
        TestTls.handshake(server, TestTls.carEngine(context))
        val writer = FrameWriter(encrypt = TlsCodec(server)::wrap)
        val message = OutboundMessage(
            type = GalMessage.Control.CHANNEL_OPEN_REQUEST,
            payload = byteArrayOf(0x08, 0x00, 0x10, 0x02),
            encrypted = true,
            isControl = true,
            channelId = GalService.VIDEO_SINK.id,
        )

        val frame = writer.frame(
            channelId = message.channelId,
            payload = MessageCodec.encode(message.type, message.payload),
            isControl = message.isControl,
            encrypted = message.encrypted,
        ).single()

        assertEquals(GalService.VIDEO_SINK.id, frame[0].toInt() and 0xFF)
        assertEquals(0x0F, frame[1].toInt() and 0xFF)
    }

    @Test
    fun `a channel-open response on a service channel reaches the session`() {
        // The HU's 0x8 comes back on the target channel, not channel 0. It must
        // still land in the session rather than the service handler.
        val transport = FakeTransport()
        val received = mutableListOf<ChannelMessage>()
        val connection = GalConnection(
            transport,
            TestTls.context(),
            deviceModel = "Pixel 8",
            onChannelMessage = { received += it },
        )
        val service = Service.newBuilder().setId(GalService.VIDEO_SINK.id).build()
        connection.session.openChannel(service)
        transport.queue(
            FrameWriter().frame(
                channelId = GalService.VIDEO_SINK.id,
                payload = MessageCodec.encode(
                    GalMessage.Control.CHANNEL_OPEN_RESPONSE,
                    ChannelOpenResponse.newBuilder().setStatus(0).build().toByteArray(),
                ),
                isControl = true,
                encrypted = false,
            ).single(),
        )

        assertTrue(connection.pump())
        assertTrue(received.isEmpty(), "the 0x8 must reach the session, not the handler")
        assertEquals(setOf(GalService.VIDEO_SINK.id), connection.session.openChannels)
    }

    @Test
    fun `other control messages leave the CONTROL bit clear`() {
        // Discovery (0x5), ping responses and byebye ride CONTROL-less: the 0x5
        // is live-accepted that way, and nothing else has shown a need.
        // Plaintext here exercises the full connection send path end to end.
        val transport = FakeTransport()
        val connection = connection(transport)
        connection.send(
            OutboundMessage(
                type = GalMessage.Control.SERVICE_DISCOVERY_REQUEST,
                payload = byteArrayOf(0x2A, 0x0E) + "Google Pixel 8".toByteArray(),
                encrypted = false,
            ),
        )

        val written = transport.written.toByteArray()
        assertEquals(0x00, written[0])
        assertEquals(0x03, written[1].toInt() and 0xFF)
    }

    @Test
    fun `wrapping without a handshake fails instead of spinning`() {
        // Pre-handshake wrap() returns OK with zero bytes consumed/produced
        // forever; the codec must fail loudly (production ANR + hung test
        // worker at GalConnectionTest:138) rather than loop.
        val codec = TlsCodec(GalCredential.serverEngine(TestTls.context()))

        assertFailsWith<IllegalStateException> {
            codec.wrap(byteArrayOf(0x08, 0x00, 0x10, 0x02))
        }
    }

    @Test
    fun `pump reports end of stream when the head unit goes away`() {
        val transport = FakeTransport()
        val connection = connection(transport)

        assertFalse(connection.pump(), "no queued bytes should read as EOF")
    }

    @Test
    fun `a request split across two reads is still handled`() {
        val transport = FakeTransport()
        val connection = connection(transport)
        val frame = headUnitFrame(GalMessage.Control.VERSION_REQUEST, versionRequest(1, 7))

        // Deliver the header and the payload in separate reads.
        transport.queue(frame.copyOfRange(0, 5))
        assertTrue(connection.pump())
        assertEquals(0, transport.written.size(), "nothing to answer with yet")

        transport.queue(frame.copyOfRange(5, frame.size))
        assertTrue(connection.pump())
        assertTrue(transport.written.size() > 0, "the full request should have been answered")
        assertEquals(GalVersion(1, 7), connection.session.negotiatedVersion)
    }

    @Test
    fun `service channel messages go to the handler rather than the session`() {
        val transport = FakeTransport()
        val received = mutableListOf<ChannelMessage>()
        val connection = GalConnection(
            transport,
            TestTls.context(),
            deviceModel = "Pixel 8",
            // Named: `trace` is also a trailing lambda, so positional binding here would
            // silently attach this to the wrong parameter.
            onChannelMessage = { received += it },
        )

        // A service-channel frame. Routing is by channel id, so this must reach the handler
        // rather than the session regardless of how the control bit is set.
        transport.queue(
            FrameWriter().frame(
                channelId = GalService.VIDEO_SINK.id,
                payload = MessageCodec.encode(GalMessage.Video.FOCUS_INDICATION, byteArrayOf(1)),
                isControl = true,
                encrypted = false,
            ).single(),
        )

        assertTrue(connection.pump())

        val message = received.single()
        assertEquals(GalService.VIDEO_SINK.id, message.channelId)
        assertEquals(GalMessage.Video.FOCUS_INDICATION, message.type)
        assertEquals(0, transport.written.size(), "the session should not have answered")
    }

    @Test
    fun `closing the connection closes the transport`() {
        val transport = FakeTransport()
        connection(transport).close()
        assertTrue(transport.closed)
    }

    @Test
    fun `each connection gets its own TLS engine`() {
        // A shared engine across connections would corrupt both sessions.
        val a = connection(FakeTransport())
        val b = connection(FakeTransport())

        a.session.onMessage(GalMessage.Control.VERSION_REQUEST, versionRequest(1, 6))

        assertEquals(GalVersion(1, 6), a.session.negotiatedVersion)
        assertEquals(null, b.session.negotiatedVersion)
        assertEquals(SessionState.AWAITING_VERSION, b.session.state)
    }
}
