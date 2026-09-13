package com.vayunmathur.auto.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The I/O-thread contract behind the main-thread crash fix: every socket write
 * leaves the connection on `ma-auto-gal-io`, never on the caller's thread, while
 * the per-channel queue order (control first, then round-robin service channels,
 * FIFO within a channel) is unchanged.
 *
 * A Choreographer vsync callback used to drain the encoder synchronously into
 * [GalConnection.send], which wrote the socket on the main thread -- StrictMode
 * killed the process with NetworkOnMainThreadException on the first video frame.
 * Plaintext sends exercise the path without a TLS handshake.
 */
class GalConnectionIoThreadTest {

    /** Captures written bytes and the name of every thread that wrote. */
    private class FakeTransport : GalTransport {
        val written = ByteArrayOutputStream()
        val writers = mutableListOf<String>()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writers += Thread.currentThread().name
            written.write(bytes, offset, length)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

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
    fun `a send from a foreign thread writes on the IO thread`() {
        val transport = FakeTransport()
        val connection = GalConnection(transport, TestTls.context(), deviceModel = "Pixel 8")
        try {
            val caller = AtomicReference("<unset>")
            val gate = CountDownLatch(1)
            val sender = Thread({
                caller.set(Thread.currentThread().name)
                gate.await(10, TimeUnit.SECONDS)
                connection.send(
                    OutboundMessage(
                        type = GalMessage.Control.PING_RESPONSE,
                        payload = byteArrayOf(0x08, 0x01),
                        encrypted = false,
                    ),
                )
            }, "fake-main")
            sender.start()
            gate.countDown()
            sender.join(10_000)

            assertTrue(transport.written.size() > 0, "the ping response should have gone out")
            assertEquals(
                listOf(GAL_IO_THREAD_NAME),
                transport.writers.distinct(),
                "no socket write may run on the sender (${caller.get()})",
            )
        } finally {
            connection.close()
        }
    }

    @Test
    fun `control still jumps a queued video burst on the IO path`() {
        val transport = FakeTransport()
        val connection = GalConnection(transport, TestTls.context(), deviceModel = "Pixel 8")
        try {
            connection.enqueue(
                OutboundMessage(
                    type = GalMessage.Media.DATA_WITH_TIMESTAMP,
                    payload = ByteArray(64),
                    encrypted = false,
                    channelId = GalService.VIDEO_SINK.id,
                ),
            )
            connection.enqueue(
                OutboundMessage(
                    type = GalMessage.Control.PING_RESPONSE,
                    payload = byteArrayOf(0x08, 0x01),
                    encrypted = false,
                ),
            )
            connection.flushSends()

            val channels = framesOf(transport.written.toByteArray()).map { it[0].toInt() and 0xFF }
            assertEquals(
                listOf(0, GalService.VIDEO_SINK.id),
                channels,
                "channel 0 first, even flushed from the IO thread",
            )
            assertEquals(
                listOf(GAL_IO_THREAD_NAME),
                transport.writers.distinct(),
                "every write on the IO thread",
            )
        } finally {
            connection.close()
        }
    }

    @Test
    fun `concurrent sends from many threads stay ordered per channel`() {
        val transport = FakeTransport()
        val connection = GalConnection(transport, TestTls.context(), deviceModel = "Pixel 8")
        try {
            val threads = (0 until 4).map { index ->
                Thread({
                    repeat(10) { round ->
                        connection.send(
                            GalService.VIDEO_SINK.id,
                            GalMessage.Media.DATA_WITH_TIMESTAMP,
                            byteArrayOf(index.toByte(), round.toByte()),
                            // Plaintext: no handshake runs here (see class KDoc), and a
                            // real engine that never handshook fails the wrap loudly.
                            encrypted = false,
                        )
                    }
                }, "sender-$index")
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(10_000) }

            assertEquals(40, framesOf(transport.written.toByteArray()).size)
            assertEquals(
                listOf(GAL_IO_THREAD_NAME),
                transport.writers.distinct(),
                "every write on the IO thread, from every sender",
            )
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a send after close is dropped, never thrown`() {
        val transport = FakeTransport()
        val connection = GalConnection(transport, TestTls.context(), deviceModel = "Pixel 8")
        connection.close()

        // Must not throw RejectedExecutionException at the caller.
        connection.send(
            OutboundMessage(
                type = GalMessage.Control.PING_RESPONSE,
                payload = byteArrayOf(0x08, 0x01),
                encrypted = false,
            ),
        )

        assertEquals(0, transport.written.size(), "nothing goes out after close")
    }
}
