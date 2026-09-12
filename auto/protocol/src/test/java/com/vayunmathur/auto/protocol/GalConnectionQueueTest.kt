package com.vayunmathur.auto.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The connection-level queue contract: sends from any thread go out control-first
 * under the single engine lock, so a video burst never head-blocks control traffic.
 *
 * Plaintext sends exercise the queue without a TLS handshake (encrypted service
 * sends need a handshook engine; see [GalConnectionTest]).
 */
class GalConnectionQueueTest {

    /** Captures everything the connection writes; reads as end of stream. */
    private class FakeTransport : GalTransport {
        val written = ByteArrayOutputStream()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            written.write(bytes, offset, length)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    private fun channelOf(frame: ByteArray): Int = frame[0].toInt() and 0xFF

    /** Splits the written bytes into frames via their 4-byte headers. */
    private fun framesOf(written: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < written.size) {
            val header = FrameHeader.readFrom(ByteBuffer.wrap(written, offset, written.size - offset))
            val frame = written.copyOfRange(offset, offset + header.size + header.payloadLength)
            frames += frame
            offset += frame.size
        }
        return frames
    }

    @Test
    fun `a video burst queued first still loses the wire to control`() {
        val transport = FakeTransport()
        val connection = GalConnection(transport, TestTls.context(), deviceModel = "Pixel 8")

        // A video bulk send queued ahead of control traffic...
        connection.enqueue(
            OutboundMessage(
                type = GalMessage.Media.DATA_WITH_TIMESTAMP,
                payload = ByteArray(64),
                encrypted = false,
                channelId = GalService.VIDEO_SINK.id,
            ),
        )
        // ...then a control reply and a mic ack.
        connection.enqueue(
            OutboundMessage(
                type = GalMessage.Control.PING_RESPONSE,
                payload = byteArrayOf(0x08, 0x01),
                encrypted = false,
            ),
        )
        connection.enqueue(
            OutboundMessage(
                type = GalMessage.Media.ACK,
                payload = byteArrayOf(0x08, 0x00),
                encrypted = false,
                channelId = GalService.AUDIO_SOURCE.id,
            ),
        )
        assertEquals(3, connection.pendingSends())

        connection.flushSends()

        assertEquals(0, connection.pendingSends())
        val channels = framesOf(transport.written.toByteArray()).map(::channelOf)
        assertEquals(
            listOf(0, GalService.VIDEO_SINK.id, GalService.AUDIO_SOURCE.id),
            channels,
            "channel 0 first, then service channels in first-enqueue order",
        )
    }

    @Test
    fun `enqueue without flush holds sends for one ordered drain`() {
        val transport = FakeTransport()
        val connection = GalConnection(transport, TestTls.context(), deviceModel = "Pixel 8")

        connection.enqueue(
            OutboundMessage(
                type = GalMessage.Control.PING_RESPONSE,
                payload = byteArrayOf(0x08, 0x01),
                encrypted = false,
            ),
        )
        assertEquals(1, connection.pendingSends())
        assertEquals(0, transport.written.size(), "nothing goes out before the flush")

        connection.flushSends()
        assertEquals(0, connection.pendingSends())
        assertTrue(transport.written.size() > 0)
    }
}
