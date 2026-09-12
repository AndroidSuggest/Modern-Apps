package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AuthComplete
import com.vayunmathur.auto.protocol.gal.ChannelOpenResponse
import com.vayunmathur.auto.protocol.gal.MediaCodecType
import com.vayunmathur.auto.protocol.gal.MediaSinkService
import com.vayunmathur.auto.protocol.gal.MessageStatus
import com.vayunmathur.auto.protocol.gal.PingRequest
import com.vayunmathur.auto.protocol.gal.PingResponse
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.ServiceDiscoveryRequest
import com.vayunmathur.auto.protocol.gal.ServiceDiscoveryResponse
import java.io.File
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives the control session through a full bring-up against a stand-in head unit.
 *
 * The head unit here is a real client [SSLEngine] using the same GAL credential, which is
 * what an actual head unit presents, so the TLS half is genuine rather than mocked. The
 * message sequencing above it is scripted to match what gearhead expects.
 */
class GalControlSessionTest {

    private val assetDir = File("src/main/assets/gal")

    private fun context() = GalCredential.create(
        certPem = File(assetDir, "client-cert.pem").readText(),
        keyPem = File(assetDir, "client-key.pem").readText(),
        rootPem = File(assetDir, "root.pem").readText(),
    )

    private fun session(engine: SSLEngine) =
        GalControlSession(engine, deviceModel = "Pixel 8", deviceManufacturer = "Google")

    @Test
    fun `a full bring-up reaches ACTIVE with the head unit's services`() {
        val context = context()
        val server = GalCredential.serverEngine(context)
        val car = context.createSSLEngine().apply { useClientMode = true }
        val session = session(server)

        // 1. The head unit asks for a version; we answer.
        assertEquals(SessionState.AWAITING_VERSION, session.state)
        val versionReply = session.onMessage(
            GalMessage.Control.VERSION_REQUEST,
            versionRequest(1, 6),
        ).single()
        assertEquals(GalMessage.Control.VERSION_RESPONSE, versionReply.type)
        assertTrue(!versionReply.encrypted, "version negotiation is in the clear")
        assertEquals(GalVersion(1, 6), session.negotiatedVersion)
        assertEquals(SessionState.HANDSHAKING, session.state)

        // 2. TLS, with the head unit as client.
        runHandshake(session, car)
        assertEquals(
            SessionState.AWAITING_AUTH_COMPLETE,
            session.state,
            "failure: ${session.failure}",
        )
        // We authenticated the head unit's certificate.
        assertNotNull(server.session.peerCertificates.firstOrNull())

        // 3. The head unit confirms, and that is what triggers our discovery request.
        val discovery = session.onMessage(
            GalMessage.Control.AUTH_COMPLETE,
            AuthComplete.newBuilder()
                .setStatus(MessageStatus.STATUS_SUCCESS.number)
                .build()
                .toByteArray(),
        ).single()
        assertEquals(GalMessage.Control.SERVICE_DISCOVERY_REQUEST, discovery.type)
        assertTrue(discovery.encrypted, "everything after auth is wrapped")
        // Field 5 only, composed exactly as gearhead does: MANUFACTURER + " " + MODEL.
        val request = ServiceDiscoveryRequest.parseFrom(discovery.payload)
        assertEquals("Google Pixel 8", request.deviceBrand)
        assertEquals(false, request.hasDeviceName())
        assertEquals(SessionState.DISCOVERING, session.state)

        // 4. The head unit advertises; we consume.
        val outputs = session.onMessage(
            GalMessage.Control.SERVICE_DISCOVERY_RESPONSE,
            headUnitServices().toByteArray(),
        )
        assertTrue(outputs.isEmpty(), "discovery response needs no reply")
        assertEquals(SessionState.ACTIVE, session.state)
        assertEquals(
            listOf(GalService.VIDEO_SINK.id, GalService.INPUT_SOURCE.id),
            session.services.map { it.id },
        )
        assertNull(session.failure)
    }

    @Test
    fun `we answer a version we cannot speak and then close`() {
        val session = session(GalCredential.serverEngine(context()))

        val reply = session.onMessage(
            GalMessage.Control.VERSION_REQUEST,
            versionRequest(9, 0),
        ).single()

        // Answering rather than dropping means the head unit gets a reason instead of a
        // timeout.
        val (_, status, _) = VersionNegotiation.parseResponse(reply.payload)
        assertEquals(MessageStatus.STATUS_NO_COMPATIBLE_VERSION.number, status)
        assertEquals(SessionState.CLOSED, session.state)
        assertNotNull(session.failure)
    }

    @Test
    fun `a rejected authentication closes the session`() {
        val session = session(GalCredential.serverEngine(context()))
        session.onMessage(GalMessage.Control.VERSION_REQUEST, versionRequest(1, 6))

        session.onMessage(
            GalMessage.Control.AUTH_COMPLETE,
            AuthComplete.newBuilder()
                .setStatus(MessageStatus.STATUS_AUTHENTICATION_FAILURE_CERT_EXPIRED.number)
                .build()
                .toByteArray(),
        )

        assertEquals(SessionState.CLOSED, session.state)
        assertTrue(session.failure!!.contains("-24"), "expected the status in ${session.failure}")
    }

    @Test
    fun `a version below the discovery floor is refused`() {
        val session = session(GalCredential.serverEngine(context()))
        session.onMessage(GalMessage.Control.VERSION_REQUEST, versionRequest(1, 5))

        session.onMessage(
            GalMessage.Control.AUTH_COMPLETE,
            AuthComplete.newBuilder()
                .setStatus(MessageStatus.STATUS_SUCCESS.number)
                .build()
                .toByteArray(),
        )

        assertEquals(SessionState.CLOSED, session.state)
        assertTrue(session.failure!!.contains("discovery floor"))
    }

    @Test
    fun `the discovery label matches gearhead's composition`() {
        // `jog.run()`: MODEL with "$MANUFACTURER " prepended unless MODEL already
        // starts with MANUFACTURER. `a.bJ(model, manufacturer, " ")` returns
        // str2 + str3 + str, i.e. MANUFACTURER + " " + MODEL.
        assertEquals("Google Pixel 8", composeDiscoveryLabel("Pixel 8", "Google"))
        assertEquals("Google Pixel 8 Pro", composeDiscoveryLabel("Pixel 8 Pro", "Google"))
        assertEquals("GooglePixel", composeDiscoveryLabel("GooglePixel", "Google"))
    }

    @Test
    fun `the discovery request pins field 5 alone on the wire`() {
        // Field 5 = length-delimited string: tag 0x2A, then length, then bytes.
        // "Google Pixel 8" is 14 bytes, so the whole request is 16 bytes.
        val expected = byteArrayOf(0x2A, 0x0E) + "Google Pixel 8".toByteArray()
        assertContentEquals(
            expected,
            ServiceDiscoveryRequest.newBuilder()
                .setDeviceBrand(composeDiscoveryLabel("Pixel 8", "Google"))
                .build()
                .toByteArray(),
        )
    }

    @Test
    fun `a ping is answered with the same timestamp`() {
        val session = session(GalCredential.serverEngine(context()))
        val reply = session.onMessage(
            GalMessage.Control.PING_REQUEST,
            PingRequest.newBuilder().setTimestamp(1234567890123L).build().toByteArray(),
        ).single()

        assertEquals(GalMessage.Control.PING_RESPONSE, reply.type)
        assertEquals(1234567890123L, PingResponse.parseFrom(reply.payload).timestamp)
    }

    @Test
    fun `a byebye request is acknowledged and closes the session`() {
        val session = session(GalCredential.serverEngine(context()))
        val reply = session.onMessage(
            GalMessage.Control.BYEBYE_REQUEST,
            byteArrayOf(0x08, 0x01),
        ).single()

        assertEquals(GalMessage.Control.BYEBYE_RESPONSE, reply.type)
        assertEquals(SessionState.CLOSED, session.state)
    }

    @Test
    fun `the channel opens only on a success response`() {
        val session = session(GalCredential.serverEngine(context()))
        val service = Service.newBuilder().setId(GalService.VIDEO_SINK.id).build()
        session.openChannel(service)

        // A success response marks the channel open with no reply of its own.
        assertTrue(
            session.onMessage(
                GalMessage.Control.CHANNEL_OPEN_RESPONSE,
                ChannelOpenResponse.newBuilder().setStatus(0).build().toByteArray(),
            ).isEmpty(),
        )
        assertEquals(setOf(GalService.VIDEO_SINK.id), session.openChannels)
        assertNull(session.failure)
    }

    @Test
    fun `a refused channel open is recorded, not fatal`() {
        // DHU 2.0 answers some opens with an empty MessageError and never sends
        // 0x8 at all; other times 0x8 carries non-SUCCESS. Either way the
        // bring-up continues with the remaining services. Walk to ACTIVE first:
        // opens only happen after discovery completes.
        val context = context()
        val server = GalCredential.serverEngine(context)
        val car = context.createSSLEngine().apply { useClientMode = true }
        val session = session(server)
        session.onMessage(GalMessage.Control.VERSION_REQUEST, versionRequest(1, 6))
        runHandshake(session, car)
        session.onMessage(
            GalMessage.Control.AUTH_COMPLETE,
            AuthComplete.newBuilder()
                .setStatus(MessageStatus.STATUS_SUCCESS.number)
                .build()
                .toByteArray(),
        )
        session.onMessage(
            GalMessage.Control.SERVICE_DISCOVERY_RESPONSE,
            headUnitServices().toByteArray(),
        )
        assertEquals(SessionState.ACTIVE, session.state)

        val service = Service.newBuilder().setId(GalService.VIDEO_SINK.id).build()
        session.openChannel(service)
        assertEquals(listOf(GalService.VIDEO_SINK.id), session.pendingChannels)

        session.onMessage(
            GalMessage.Control.CHANNEL_OPEN_RESPONSE,
            ChannelOpenResponse.newBuilder().setStatus(-4).build().toByteArray(),
        )

        assertTrue(session.openChannels.isEmpty())
        assertEquals(setOf(GalService.VIDEO_SINK.id), session.refusedChannels)
        assertTrue(session.pendingChannels.isEmpty())
        assertNull(session.failure)
        assertEquals(SessionState.ACTIVE, session.state)
    }

    @Test
    fun `sequential opens track one flight at a time`() {
        val session = session(GalCredential.serverEngine(context()))
        val video = Service.newBuilder().setId(GalService.VIDEO_SINK.id).build()
        val input = Service.newBuilder().setId(GalService.INPUT_SOURCE.id).build()
        session.openChannel(video)
        session.openChannel(input)

        // First answer drains the head of the queue.
        session.onMessage(
            GalMessage.Control.CHANNEL_OPEN_RESPONSE,
            ChannelOpenResponse.newBuilder().setStatus(0).build().toByteArray(),
        )
        assertEquals(setOf(GalService.VIDEO_SINK.id), session.openChannels)
        assertEquals(listOf(GalService.INPUT_SOURCE.id), session.pendingChannels)
    }

    @Test
    fun `a message error is observed, not fatal`() {
        // Live DHU behaviour: the head unit 0xff's a message it rejects while the
        // transport stays healthy (pings keep flowing). gearhead's `izu` logs it
        // and carries on; only framing errors tear down. The session must survive
        // so the bring-up can continue on the remaining channels.
        val session = session(GalCredential.serverEngine(context()))
        session.onMessage(GalMessage.Control.VERSION_REQUEST, versionRequest(1, 6))

        val replies = session.onMessage(GalMessage.Control.MESSAGE_ERROR, byteArrayOf())

        assertTrue(replies.isEmpty())
        assertEquals(SessionState.HANDSHAKING, session.state)
        assertNull(session.failure)
    }

    // ---- helpers ----

    private fun versionRequest(major: Int, minor: Int): ByteArray =
        ByteBuffer.allocate(4)
            .putShort(major.toShort())
            .putShort(minor.toShort())
            .array()

    private fun headUnitServices(): ServiceDiscoveryResponse = ServiceDiscoveryResponse
        .newBuilder()
        .setHeadUnitName("Test Head Unit")
        .addServices(
            Service.newBuilder()
                .setId(GalService.VIDEO_SINK.id)
                .setMediaSink(
                    MediaSinkService.newBuilder()
                        .setAvailableType(MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP),
                ),
        )
        .addServices(Service.newBuilder().setId(GalService.INPUT_SOURCE.id))
        .build()

    /**
     * Shuttles message-3 payloads between the session and a client engine until the session
     * says the handshake is done.
     */
    private fun runHandshake(session: GalControlSession, car: SSLEngine) {
        car.beginHandshake()
        repeat(MAX_STEPS) {
            if (session.state != SessionState.HANDSHAKING) return

            // Everything the car wants to say.
            for (record in carRecords(car)) {
                for (out in session.onMessage(GalMessage.Control.SSL_HANDSHAKE, record)) {
                    assertEquals(GalMessage.Control.SSL_HANDSHAKE, out.type)
                    feedCar(car, out.payload)
                }
            }
        }
        fail("handshake did not finish in $MAX_STEPS steps: ${session.state} ${session.failure}")
    }

    private fun carRecords(car: SSLEngine): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        val empty = ByteBuffer.allocate(0)
        while (true) {
            when (car.handshakeStatus) {
                HandshakeStatus.NEED_TASK -> car.delegatedTask?.run() ?: return records
                HandshakeStatus.NEED_WRAP -> {
                    val out = ByteBuffer.allocate(car.session.packetBufferSize)
                    val result = car.wrap(empty, out)
                    if (result.status != Status.OK) fail("car wrap: ${result.status}")
                    out.flip()
                    records += ByteArray(out.remaining()).also { out.get(it) }
                }
                else -> return records
            }
        }
    }

    private fun feedCar(car: SSLEngine, record: ByteArray) {
        val inbound = ByteBuffer.wrap(record)
        while (inbound.hasRemaining()) {
            while (car.handshakeStatus == HandshakeStatus.NEED_TASK) {
                car.delegatedTask?.run() ?: break
            }
            if (car.handshakeStatus != HandshakeStatus.NEED_UNWRAP) return
            val plain = ByteBuffer.allocate(car.session.applicationBufferSize)
            val result = car.unwrap(inbound, plain)
            if (result.status != Status.OK) return
        }
    }

    private companion object {
        const val MAX_STEPS = 50
    }
}
