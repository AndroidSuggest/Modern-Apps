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
 * Classification helper only: it must NOT drive the outgoing CONTROL bit.
 * `jbe.i()` sets 0x04 from `Lizm.f`, which is false for every service-channel
 * send (`izd.d` → `izl.g(..., isControl=false, ...)`), protobuf or bulk alike
 * -- so service-channel frames always go out CONTROL-clear. The bit is set
 * only on channel-0 channel-open requests (see `GalConnection.send`).
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
    deviceModel: String,
    deviceManufacturer: String = deviceModel,
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

    val session: GalControlSession = GalControlSession(engine, deviceModel, deviceManufacturer)

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
            // Channel-open traffic (0x7 out / 0x8 in) rides the TARGET channel,
            // not channel 0: gearhead's `izd.b()` sends the open via
            // `izl.g(this.b, ...)` where `b` is the channel being opened, and
            // the HU's 0x8 comes back the same way. A ch0-framed 0x7 parses but
            // is refused with STATUS_INVALID_CHANNEL (-5, Run 6). So an inbound
            // 0x8 on ANY channel belongs to the session; everything else on a
            // service channel goes to that channel's owner.
            if (message.channelId == CONTROL_CHANNEL ||
                decoded.type == GalMessage.Control.CHANNEL_OPEN_RESPONSE
            ) {
                val before = session.state
                val replies = session.onMessage(decoded.type, decoded.payload)
                // Log the payload: incoming message bodies are otherwise invisible, and
                // the head unit's answers (discovery list, open status, errors) carry
                // the only explanation we ever get for a rejection. Control payloads
                // are small (a discovery response is a few hundred bytes) and each
                // log line comfortably holds ~1.5KB of hex, so log them whole: the
                // 64-byte cap once hid the 569-byte service list we needed to see.
                trace(
                    "ctrl in 0x${decoded.type.toString(16)} (${decoded.payload.size}B) " +
                        "$before -> ${session.state}, ${replies.size} reply " +
                        decoded.payload.joinToString("") { "%02x".format(it) },
                )
                session.failure?.let { trace("session failed: $it") }
                replies.forEach(::send)
            } else {
                trace(
                    "ch${message.channelId} in 0x${decoded.type.toString(16)} " +
                        "(${decoded.payload.size}B) " +
                        decoded.payload.take(IN_PAYLOAD_LOG_BYTES).joinToString("") {
                            "%02x".format(it)
                        },
                )
                onChannelMessage.onMessage(decoded)
            }
        }
        return true
    }

    /** Frames a control message and writes it. */
    fun send(message: OutboundMessage) {
        // Most control messages ride channel 0; the channel-open request rides
        // its target channel (see OutboundMessage.channelId).
        write(
            channelId = message.channelId,
            payload = MessageCodec.encode(message.type, message.payload),
            // Per-message: only the channel-open request sets CONTROL today.
            // The CONTROL-less 0x5 is live-accepted, so nothing else takes the
            // bit; see OutboundMessage.isControl.
            isControl = message.isControl,
            encrypted = message.encrypted,
        )
    }

    /**
     * Frames a service-channel message and writes it. Always encrypted, never
     * CONTROL-flagged.
     *
     * `jbe.i()` builds flags as `FIRST | LAST | (Lizm.f ? 0x04 : 0) |
     * (Lizm.h ? 0x08 : 0)`, and every service-channel send goes through
     * `izd.d` → `izl.g(i, jcn, canFragment=true, isControl=false, izn)` --
     * `Lizm.f` is false for ALL service-channel traffic, protobuf or bulk
     * (Run 7: HU 0xffs a CONTROL-flagged 0x8000 setup on ch2). The CONTROL bit
     * belongs only to channel-0 channel-open requests (see [send] above).
     */
    fun send(channelId: Int, type: Int, payload: ByteArray) {
        write(
            channelId = channelId,
            payload = MessageCodec.encode(type, payload),
            isControl = false,
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

        /** Bytes of each service-channel payload in the trace log. */
        const val IN_PAYLOAD_LOG_BYTES = 64
    }
}
