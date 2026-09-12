package com.vayunmathur.auto.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
        GalConnection(transport, TestTls.context(), deviceName = "Pixel 8")

    /** A control frame as a head unit would put it on the wire: plaintext, channel 0. */
    private fun headUnitFrame(type: Int, payload: ByteArray): ByteArray =
        FrameWriter().frame(
            channelId = 0,
            payload = MessageCodec.encode(type, payload),
            isControl = true,
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

        // The full frame: 4-byte header, then type 2, major, minor, status. Flags are 0x07,
        // FIRST|LAST|CONTROL -- the control bit is what puts this on the control channel.
        assertContentEquals(
            byteArrayOf(
                0x00, 0x07, 0x00, 0x08, // channel 0, FIRST|LAST|CONTROL, 8-byte payload
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
            deviceName = "Pixel 8",
        ) { received += it }

        // A plaintext service-channel frame. Real ones are encrypted, but the routing
        // decision is the control flag, which is what this is checking.
        transport.queue(
            FrameWriter().frame(
                channelId = GalService.VIDEO_SINK.id,
                payload = MessageCodec.encode(GalMessage.Video.FOCUS_INDICATION, byteArrayOf(1)),
                isControl = false,
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
