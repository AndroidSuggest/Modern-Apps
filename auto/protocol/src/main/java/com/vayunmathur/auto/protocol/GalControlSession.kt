package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AuthComplete
import com.vayunmathur.auto.protocol.gal.ByeByeReason
import com.vayunmathur.auto.protocol.gal.ByeByeRequest
import com.vayunmathur.auto.protocol.gal.ChannelOpenRequest
import com.vayunmathur.auto.protocol.gal.ChannelOpenResponse
import com.vayunmathur.auto.protocol.gal.MessageStatus
import com.vayunmathur.auto.protocol.gal.PingRequest
import com.vayunmathur.auto.protocol.gal.PingResponse
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.ServiceDiscoveryRequest
import com.vayunmathur.auto.protocol.gal.ServiceDiscoveryResponse
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status

/** A control message the session wants sent. */
class OutboundMessage(
    val type: Int,
    val payload: ByteArray,
    /** Whether the frame should be TLS-wrapped and flagged [FrameFlags.ENCRYPTED]. */
    val encrypted: Boolean,
)

/** Where the session has got to. */
enum class SessionState {
    AWAITING_VERSION,
    HANDSHAKING,
    AWAITING_AUTH_COMPLETE,
    DISCOVERING,
    ACTIVE,
    CLOSED,
}

/**
 * The GAL control channel, driven entirely by messages in and messages out.
 *
 * No transport, no threads and no I/O, so the whole bring-up can be tested against a
 * stand-in head unit on the JVM. A driver reads frames off a [GalTransport], reassembles
 * them with [FrameReader], and feeds the control channel's payloads to [onMessage].
 *
 * The order is not the one you would guess from the phone being the thing that drives
 * projection:
 *
 *  1. The **head unit** sends VersionRequest; we answer VersionResponse.
 *  2. The **head unit** opens TLS as the client; we are the server and pump the engine
 *     through message 3 in both directions.
 *  3. The **head unit** confirms with AuthComplete.
 *  4. Only then do we send ServiceDiscoveryRequest, and only at version >= 1.6.
 *
 * Everything from AuthComplete onwards is TLS-wrapped; versions and the handshake itself
 * travel in the clear.
 */
class GalControlSession(
    private val engine: SSLEngine,
    /** Android `Build.MODEL`, e.g. "Pixel 8". */
    private val deviceModel: String,
    /** Android `Build.MANUFACTURER`, e.g. "Google". Defaults for host tests. */
    private val deviceManufacturer: String = deviceModel,
    private val supportedVersion: GalVersion = VersionNegotiation.SUPPORTED,
) {
    var state: SessionState = SessionState.AWAITING_VERSION
        private set

    var negotiatedVersion: GalVersion? = null
        private set

    /** Services the head unit advertised, empty until discovery completes. */
    var services: List<Service> = emptyList()
        private set

    /** Set when the session ends abnormally. */
    var failure: String? = null
        private set

    /** Service ids whose ChannelOpenResponse came back STATUS_SUCCESS. */
    var openChannels: Set<Int> = emptySet()
        private set

    private var pendingChannel: Int = -1

    private var tlsInbound: ByteBuffer = ByteBuffer.allocate(0)

    /** Handles one control-channel message and returns whatever should go back. */
    fun onMessage(type: Int, payload: ByteArray): List<OutboundMessage> = when (type) {
        GalMessage.Control.VERSION_REQUEST -> onVersionRequest(payload)
        GalMessage.Control.SSL_HANDSHAKE -> onHandshakeData(payload)
        GalMessage.Control.AUTH_COMPLETE -> onAuthComplete(payload)
        GalMessage.Control.SERVICE_DISCOVERY_RESPONSE -> onServiceDiscovery(payload)
        GalMessage.Control.CHANNEL_OPEN_RESPONSE -> onChannelOpen(payload)
        GalMessage.Control.PING_REQUEST -> onPing(payload)
        GalMessage.Control.BYEBYE_REQUEST -> onByeBye()
        GalMessage.Control.BYEBYE_RESPONSE -> {
            state = SessionState.CLOSED
            emptyList()
        }
        // Focus and status notifications are observed by higher layers; the control channel
        // itself has nothing to answer.
        GalMessage.Control.AUDIO_FOCUS_NOTIFICATION,
        GalMessage.Control.NAVIGATION_FOCUS_NOTIFICATION,
        GalMessage.Control.CALL_AVAILABILITY_STATUS,
        GalMessage.Control.SERVICE_DISCOVERY_UPDATE,
        GalMessage.Control.PING_RESPONSE,
        -> emptyList()

        // The head unit's rejection of one of our messages. Observed, not fatal:
        // gearhead's `izu` logs it (`ai(1698)`) and carries on, and channel-level
        // errors are per-channel (`iza` answers 0xff on the channel itself). The
        // transport stays alive -- pings keep flowing -- so the session must too.
        // The payload names the offending message; the trace in GalConnection
        // prints it. Killing the session here is what turned a recoverable
        // per-message rejection into a dead bring-up.
        GalMessage.Control.MESSAGE_ERROR -> emptyList()

        else -> fail("unexpected control message type 0x${type.toString(16)}")
    }

    /**
     * Asks the head unit to open [service]'s channel.
     *
     * The channel opens asynchronously: the head unit answers with
     * ChannelOpenResponse, and [openChannels] gains the id only on
     * STATUS_SUCCESS. Nothing may be sent on the channel before that --
     * gearhead's `jdk.Q()` sends media setup from `onChannelOpened`.
     */
    fun openChannel(service: Service, priority: Int = 0): OutboundMessage {
        pendingChannel = service.id
        return OutboundMessage(
            type = GalMessage.Control.CHANNEL_OPEN_REQUEST,
            payload = ChannelOpenRequest.newBuilder()
                .setPriority(priority)
                .setServiceId(service.id)
                .build()
                .toByteArray(),
            encrypted = true,
        )
    }

    /** Ends the session politely. */
    fun disconnect(reason: ByeByeReason = ByeByeReason.USER_SELECTION): OutboundMessage =
        OutboundMessage(
            type = GalMessage.Control.BYEBYE_REQUEST,
            payload = ByeByeRequest.newBuilder().setReason(reason).build().toByteArray(),
            encrypted = true,
        )

    // ---- handlers ----

    private fun onVersionRequest(payload: ByteArray): List<OutboundMessage> {
        val requested = VersionNegotiation.parseRequest(payload)
        val agreed = VersionNegotiation.negotiate(requested, supportedVersion)
        val status = if (agreed == null) {
            MessageStatus.STATUS_NO_COMPATIBLE_VERSION.number
        } else {
            MessageStatus.STATUS_SUCCESS.number
        }

        val response = OutboundMessage(
            type = GalMessage.Control.VERSION_RESPONSE,
            payload = VersionNegotiation.buildResponse(agreed ?: supportedVersion, status),
            encrypted = false,
        )

        if (agreed == null) {
            // Answer before giving up, so the head unit learns why rather than timing out.
            failure = "head unit asked for $requested, we speak $supportedVersion"
            state = SessionState.CLOSED
            return listOf(response)
        }

        negotiatedVersion = agreed
        state = SessionState.HANDSHAKING
        engine.beginHandshake()
        // The head unit is the TLS client, so it sends the first record; nothing to pump yet.
        return listOf(response)
    }

    private fun onHandshakeData(payload: ByteArray): List<OutboundMessage> {
        if (state != SessionState.HANDSHAKING) {
            return fail("handshake data arrived in state $state")
        }
        appendInbound(payload)
        return pumpHandshake()
    }

    private fun onAuthComplete(payload: ByteArray): List<OutboundMessage> {
        val status = AuthComplete.parseFrom(payload).status
        if (status != MessageStatus.STATUS_SUCCESS.number) {
            return fail("head unit rejected authentication: status $status")
        }
        val version = negotiatedVersion
        if (version == null || version < VersionNegotiation.MINIMUM_FOR_DISCOVERY) {
            return fail("negotiated $version is below the service-discovery floor")
        }
        state = SessionState.DISCOVERING
        return listOf(
            OutboundMessage(
                type = GalMessage.Control.SERVICE_DISCOVERY_REQUEST,
                // Field 5 ONLY, composed exactly as gearhead does it in `jog.run()`:
                // `Build.MODEL`, with `"$MANUFACTURER "` prepended unless MODEL
                // already starts with MANUFACTURER (e.g. "Google Pixel 8").
                // Field 5 (`xoa.g`, hasbit 16) is the only unconditional field;
                // fields 1-3 (`izu.g/h/i`), 4 (`izu.f`) and 6 (`izu.j`) are sent
                // only when that state is non-null, which it is not on the DHU
                // path -- and `jog` nulls them after the first send, so repeats
                // are field-5-only by construction. A DHU answers anything else
                // here with a bare MessageError (0xff). Framing is unchanged:
                // `jbj.k(5)` routes to `o(..., true, ...)` where the `true` is
                // isEncrypted (izn.a -> 0x08), NOT the CONTROL bit, which stays
                // clear on this message -- flags FIRST|LAST|ENCRYPTED = 0x0B.
                payload = ServiceDiscoveryRequest.newBuilder()
                    .setDeviceBrand(composeDiscoveryLabel(deviceModel, deviceManufacturer))
                    .build()
                    .toByteArray(),
                encrypted = true,
            ),
        )
    }

    private fun onServiceDiscovery(payload: ByteArray): List<OutboundMessage> {
        services = ServiceDiscoveryResponse.parseFrom(payload).servicesList
        state = SessionState.ACTIVE
        return emptyList()
    }

    /**
     * Records the head unit's answer to [openChannel].
     *
     * The channel is NOT open yet when this returns: like gearhead's `iza.a()`,
     * the driver must wait for STATUS_SUCCESS here before sending anything on
     * the new channel. Sending service messages (e.g. media setup) against a
     * channel the head unit has not opened earns a bare MessageError (0xff).
     */
    private fun onChannelOpen(payload: ByteArray): List<OutboundMessage> {
        val status = ChannelOpenResponse.parseFrom(payload).status
        if (status != MessageStatus.STATUS_SUCCESS.number) {
            return fail("head unit refused channel open: status $status")
        }
        openChannels += pendingChannel
        pendingChannel = -1
        return emptyList()
    }

    private fun onPing(payload: ByteArray): List<OutboundMessage> = listOf(
        OutboundMessage(
            type = GalMessage.Control.PING_RESPONSE,
            payload = PingResponse.newBuilder()
                .setTimestamp(PingRequest.parseFrom(payload).timestamp)
                .build()
                .toByteArray(),
            encrypted = true,
        ),
    )

    private fun onByeBye(): List<OutboundMessage> {
        state = SessionState.CLOSED
        return listOf(
            OutboundMessage(
                type = GalMessage.Control.BYEBYE_RESPONSE,
                payload = ByteArray(0),
                encrypted = true,
            ),
        )
    }

    // ---- TLS ----

    private fun appendInbound(payload: ByteArray) {
        val combined = ByteBuffer.allocate(tlsInbound.remaining() + payload.size)
        combined.put(tlsInbound)
        combined.put(payload)
        combined.flip()
        tlsInbound = combined
    }

    /**
     * Runs the engine until it needs more from the head unit, emitting a message 3 for each
     * record it produces.
     */
    private fun pumpHandshake(): List<OutboundMessage> {
        val outgoing = mutableListOf<OutboundMessage>()
        val empty = ByteBuffer.allocate(0)

        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_TASK -> engine.delegatedTask?.run() ?: return outgoing

                HandshakeStatus.NEED_UNWRAP -> {
                    if (!tlsInbound.hasRemaining()) return outgoing
                    val plain = ByteBuffer.allocate(engine.session.applicationBufferSize)
                    val result = engine.unwrap(tlsInbound, plain)
                    when (result.status) {
                        // Partial record: wait for the next message 3.
                        Status.BUFFER_UNDERFLOW -> return outgoing
                        Status.OK -> Unit
                        else -> return fail("TLS unwrap failed: ${result.status}")
                    }
                }

                HandshakeStatus.NEED_WRAP -> {
                    val record = ByteBuffer.allocate(engine.session.packetBufferSize)
                    val result = engine.wrap(empty, record)
                    if (result.status != Status.OK) {
                        return fail("TLS wrap failed: ${result.status}")
                    }
                    record.flip()
                    val bytes = ByteArray(record.remaining())
                    record.get(bytes)
                    // Handshake records ride in the clear: they ARE the encryption being
                    // set up, so the ENCRYPTED frame flag must stay off.
                    outgoing += OutboundMessage(
                        GalMessage.Control.SSL_HANDSHAKE,
                        bytes,
                        encrypted = false,
                    )
                }

                HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> {
                    // The head unit confirms with AuthComplete; we do not send it.
                    state = SessionState.AWAITING_AUTH_COMPLETE
                    return outgoing
                }

                else -> return outgoing
            }
        }
    }

    private fun fail(reason: String): List<OutboundMessage> {
        failure = reason
        state = SessionState.CLOSED
        return emptyList()
    }
}

/**
 * Composes the `ServiceDiscoveryRequest` device label exactly as gearhead does.
 *
 * `jog.run()` reads `Build.MANUFACTURER` and `Build.MODEL` and, unless MODEL
 * already starts with MANUFACTURER, concatenates `MANUFACTURER + " " + MODEL`
 * (`a.bJ(model, manufacturer, " ")` returns `str2 + str3 + str`).
 */
internal fun composeDiscoveryLabel(model: String, manufacturer: String): String =
    if (model.startsWith(manufacturer)) model else "$manufacturer $model"
