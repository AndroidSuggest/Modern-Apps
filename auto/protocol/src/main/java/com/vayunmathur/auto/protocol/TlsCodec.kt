package com.vayunmathur.auto.protocol

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.Status

/**
 * Wraps and unwraps application data once the handshake is done.
 *
 * Gearhead encrypts per frame, so one frame payload is one TLS record and this is what
 * [FrameWriter]'s `encrypt` and [FrameReader]'s `decrypt` hooks are given. Handshake data is
 * *not* routed through here: those records travel in the clear as control message 3, because
 * they are the negotiation itself.
 *
 * An [SSLEngine] is not thread-safe, so a connection must not wrap on one thread while
 * unwrapping on another. The driver reads and writes from a single loop for that reason.
 */
class TlsCodec(private val engine: SSLEngine) {

    /** Encrypts one frame payload into a single TLS record. */
    fun wrap(plaintext: ByteArray): ByteArray {
        val source = ByteBuffer.wrap(plaintext)
        val record = ByteBuffer.allocate(engine.session.packetBufferSize)
        while (source.hasRemaining()) {
            val result = engine.wrap(source, record)
            check(result.status == Status.OK) { "TLS wrap failed: ${result.status}" }
        }
        record.flip()
        return ByteArray(record.remaining()).also { record.get(it) }
    }

    /**
     * Decrypts one frame payload.
     *
     * @throws IllegalStateException if [record] is not a whole TLS record. Frames carry
     *   complete records, so a partial one means the frame layer handed over the wrong
     *   bytes rather than something to wait on.
     */
    fun unwrap(record: ByteArray): ByteArray {
        val source = ByteBuffer.wrap(record)
        var plain = ByteBuffer.allocate(engine.session.applicationBufferSize)
        while (source.hasRemaining()) {
            val result = engine.unwrap(source, plain)
            when (result.status) {
                Status.OK -> Unit
                Status.BUFFER_OVERFLOW -> {
                    // Grow and retry rather than truncating.
                    val bigger = ByteBuffer.allocate(plain.capacity() * 2)
                    plain.flip()
                    bigger.put(plain)
                    plain = bigger
                }
                else -> error("TLS unwrap failed: ${result.status}")
            }
        }
        plain.flip()
        return ByteArray(plain.remaining()).also { plain.get(it) }
    }
}
