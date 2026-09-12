package com.vayunmathur.auto.protocol

import java.io.Closeable
import javax.net.ssl.SSLContext

/** A message on a service channel, handed to whatever owns that service. */
fun interface ChannelMessageHandler {
    fun onMessage(message: ChannelMessage)
}

/**
 * Ties a [GalTransport] to the control session.
 *
 * Owns the single [javax.net.ssl.SSLEngine] for the connection and therefore the read/write
 * loop: an engine is not thread-safe, so wrapping and unwrapping both happen on whichever
 * thread calls [pump]. Callers drive it rather than it spawning threads, which keeps the
 * whole thing testable over an in-memory transport.
 *
 * The [FrameFlags.ENCRYPTED] flag decides whether a frame goes through TLS, so the same
 * reader handles the plaintext version and handshake frames and the wrapped ones that
 * follow, with no mode switch.
 */
class GalConnection(
    private val transport: GalTransport,
    sslContext: SSLContext,
    deviceName: String,
    private val onChannelMessage: ChannelMessageHandler = ChannelMessageHandler { },
) : Closeable {

    private val engine = GalCredential.serverEngine(sslContext)
    private val tls = TlsCodec(engine)
    private val reader = FrameReader(decrypt = tls::unwrap)
    private val writer = FrameWriter(encrypt = tls::wrap)

    val session: GalControlSession = GalControlSession(engine, deviceName)

    private val buffer = ByteArray(READ_BUFFER_SIZE)

    /**
     * Reads once and handles whatever that produced.
     *
     * @return false at end of stream, when the head unit has gone away.
     */
    fun pump(): Boolean {
        val count = transport.read(buffer, 0, buffer.size)
        if (count < 0) return false

        for (message in reader.offer(buffer, 0, count)) {
            val decoded = MessageCodec.decode(message.channelId, message.payload)
            if (message.isControl) {
                session.onMessage(decoded.type, decoded.payload).forEach(::send)
            } else {
                onChannelMessage.onMessage(decoded)
            }
        }
        return true
    }

    /** Frames a control message and writes it. */
    fun send(message: OutboundMessage) {
        write(CONTROL_CHANNEL, MessageCodec.encode(message.type, message.payload),
            isControl = true, encrypted = message.encrypted)
    }

    /** Frames a service-channel message and writes it. Always encrypted. */
    fun send(channelId: Int, type: Int, payload: ByteArray) {
        write(channelId, MessageCodec.encode(type, payload), isControl = false, encrypted = true)
    }

    private fun write(channelId: Int, payload: ByteArray, isControl: Boolean, encrypted: Boolean) {
        for (frame in writer.frame(channelId, payload, isControl, encrypted)) {
            transport.write(frame, 0, frame.size)
        }
        transport.flush()
    }

    override fun close() = transport.close()

    private companion object {
        const val CONTROL_CHANNEL = 0

        /** Comfortably larger than one frame, so a read rarely splits one. */
        const val READ_BUFFER_SIZE = 32 * 1024
    }
}
