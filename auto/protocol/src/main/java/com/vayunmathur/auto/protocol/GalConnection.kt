package com.vayunmathur.auto.protocol

import java.io.Closeable
import javax.net.ssl.SSLContext

/** A message on a service channel, handed to whatever owns that service. */
fun interface ChannelMessageHandler {
    fun onMessage(message: ChannelMessage)
}

/**
 * Whether a service-channel message type carries protobuf rather than bulk media.
 *
 * The [FrameFlags.CONTROL] bit does *not* mean "control channel" -- channel 0 is what
 * identifies that, and a head unit never sets the bit there. Observed against a Desktop Head
 * Unit: its VersionRequest, its SslHandshake frames, its AuthComplete and even its encrypted
 * control replies all arrive as `FIRST|LAST` or `FIRST|LAST|ENC`, with the bit clear.
 *
 * What it distinguishes is a channel's own protobuf messages (the 0x8000 range) from the
 * bulk media that shares the channel with them.
 */
fun isChannelControlMessage(type: Int): Boolean = when (type) {
    GalMessage.Media.DATA, GalMessage.Media.DATA_WITH_TIMESTAMP -> false
    else -> true
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
    deviceBrand: String = deviceName,
    private val onChannelMessage: ChannelMessageHandler = ChannelMessageHandler { },
    /**
     * Traces every message and state change. A live session is otherwise opaque, and this
     * module stays free of `android.util.Log` so its tests run on the host, so the app
     * supplies the sink.
     */
    private val trace: (String) -> Unit = {},
) : Closeable {

    private val engine = GalCredential.serverEngine(sslContext)
    private val tls = TlsCodec(engine)
    private val reader = FrameReader(decrypt = tls::unwrap)
    private val writer = FrameWriter(encrypt = tls::wrap)

    val session: GalControlSession = GalControlSession(engine, deviceName, deviceBrand)

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
            // Routed by channel id, not by the frame's control bit: a head unit leaves that
            // bit clear on the version request, which is a control-channel message.
            if (message.channelId == CONTROL_CHANNEL) {
                val before = session.state
                val replies = session.onMessage(decoded.type, decoded.payload)
                trace(
                    "ctrl in 0x${decoded.type.toString(16)} (${decoded.payload.size}B) " +
                        "$before -> ${session.state}, ${replies.size} reply",
                )
                session.failure?.let { trace("session failed: $it") }
                replies.forEach(::send)
            } else {
                trace(
                    "ch${message.channelId} in 0x${decoded.type.toString(16)} " +
                        "(${decoded.payload.size}B)",
                )
                onChannelMessage.onMessage(decoded)
            }
        }
        return true
    }

    /** Frames a control message and writes it. */
    fun send(message: OutboundMessage) {
        write(
            channelId = CONTROL_CHANNEL,
            payload = MessageCodec.encode(message.type, message.payload),
            // Never set on channel 0; see isChannelControlMessage.
            isControl = false,
            encrypted = message.encrypted,
        )
    }

    /** Frames a service-channel message and writes it. Always encrypted. */
    fun send(channelId: Int, type: Int, payload: ByteArray) {
        write(
            channelId = channelId,
            payload = MessageCodec.encode(type, payload),
            isControl = isChannelControlMessage(type),
            encrypted = true,
        )
    }

    private fun write(channelId: Int, payload: ByteArray, isControl: Boolean, encrypted: Boolean) {
        trace(
            "out ch$channelId 0x${
                ((payload[0].toInt() and 0xFF shl 8) or (payload[1].toInt() and 0xFF)).toString(16)
            } ctrl=$isControl enc=$encrypted ${payload.take(48).joinToString("") {
                "%02x".format(it)
            }}",
        )
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
