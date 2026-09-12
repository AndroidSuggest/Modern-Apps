package com.vayunmathur.auto.protocol

import java.io.File
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status
import kotlin.test.assertEquals
import kotlin.test.fail

/** Shared TLS scaffolding for the protocol tests. */
object TestTls {

    private val assetDir = File("src/main/assets/gal")

    /**
     * The credential exactly as shipped, read from `src/main/assets` rather than a test
     * copy, so these tests fail if the shipped files are wrong.
     */
    fun context(): SSLContext = GalCredential.create(
        certPem = File(assetDir, "client-cert.pem").readText(),
        keyPem = File(assetDir, "client-key.pem").readText(),
        rootPem = File(assetDir, "root.pem").readText(),
    )

    /**
     * A stand-in head unit: a TLS client presenting the same GAL-signed certificate, which
     * is what a real head unit does and what openauto does on that side.
     */
    fun carEngine(context: SSLContext): SSLEngine =
        context.createSSLEngine().apply { useClientMode = true }

    /** Drives two engines against each other in memory until both settle. */
    fun handshake(server: SSLEngine, client: SSLEngine) {
        server.beginHandshake()
        client.beginHandshake()

        val packetSize = maxOf(server.session.packetBufferSize, client.session.packetBufferSize)
        val appSize =
            maxOf(server.session.applicationBufferSize, client.session.applicationBufferSize)
        val empty = ByteBuffer.allocate(0)

        repeat(MAX_STEPS) {
            if (settled(server) && settled(client)) return
            var progressed = false
            for ((from, to) in listOf(client to server, server to client)) {
                runTasks(from)
                if (from.handshakeStatus != HandshakeStatus.NEED_WRAP) continue

                val wire = ByteBuffer.allocate(packetSize)
                val wrapped = from.wrap(empty, wire)
                assertEquals(Status.OK, wrapped.status, "wrap failed")
                wire.flip()
                progressed = true

                while (wire.hasRemaining()) {
                    runTasks(to)
                    val plain = ByteBuffer.allocate(appSize)
                    val unwrapped = to.unwrap(wire, plain)
                    assertEquals(Status.OK, unwrapped.status, "unwrap failed")
                    if (to.handshakeStatus == HandshakeStatus.NEED_WRAP) break
                }
            }
            if (!progressed) {
                fail("handshake stalled: ${server.handshakeStatus} / ${client.handshakeStatus}")
            }
        }
        fail("handshake did not settle within $MAX_STEPS steps")
    }

    private fun runTasks(engine: SSLEngine) {
        while (engine.handshakeStatus == HandshakeStatus.NEED_TASK) {
            engine.delegatedTask?.run() ?: break
        }
    }

    private fun settled(engine: SSLEngine) =
        engine.handshakeStatus == HandshakeStatus.NOT_HANDSHAKING ||
            engine.handshakeStatus == HandshakeStatus.FINISHED

    private const val MAX_STEPS = 50
}
