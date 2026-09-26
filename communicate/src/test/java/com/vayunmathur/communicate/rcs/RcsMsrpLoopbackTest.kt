package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.rcs.RcsMsrpFraming
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MSRP loopback integration test (§8.4): a real `ServerSocket` on loopback
 * plays the peer — accepts one TCP connection, reads one SEND, answers 200 —
 * while the framing layer builds the chunk. No Android framework, no IMS PDN:
 * exercises wire serialization + response matching end-to-end.
 *
 * Plaintext only; TLS-in-test needs cert generation + handshake orchestration
 * that belongs in a Robolectric harness (see plan §8.4).
 */
class RcsMsrpLoopbackTest {
    @Test
    fun sendAckRoundTrip() {
        val server = ServerSocket(0)
        val port = server.localPort
        val received = AtomicReference<String>()
        val done = CountDownLatch(1)
        val peer = Thread {
            runCatching {
                server.use { srv ->
                    srv.soTimeout = 15_000
                    val socket = srv.accept()
                    socket.use { s ->
                        s.soTimeout = 15_000
                        val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                        val headLines = mutableListOf<String>()
                        var line = input.readLine()
                        var guard = 0
                        while (line != null && line.isNotEmpty() && guard++ < 32) {
                            headLines.add(line)
                            line = input.readLine()
                        }
                        val txid = headLines.firstOrNull()
                            ?.split(" ")?.getOrNull(1).orEmpty()
                        // Read body through the end marker.
                        val body = StringBuilder()
                        while (true) {
                            val l = input.readLine() ?: break
                            if (l.startsWith("-------$txid")) break
                            body.append(l).append("\r\n")
                        }
                        received.set(headLines.joinToString("\r\n") + "\n" + body.toString())
                        val out = s.getOutputStream()
                        out.write(
                            RcsMsrpFraming.buildErrorResponse(
                                toPath = "",
                                txid = txid,
                                code = 200,
                                reason = "OK",
                            ),
                        )
                        out.flush()
                    }
                }
            }.onFailure { received.set("ERROR: $it") }
            done.countDown()
        }
        peer.isDaemon = true
        peer.start()

        // Client side: build one chunk via the framing layer, write it over a
        // plain socket (loopback stands in for the IMS PDN here), await 200.
        val chunks = RcsMsrpFraming.splitSend(
            toPath = "msrp://127.0.0.1:$port/peer;tcp",
            fromPath = "msrp://127.0.0.1:$port/self;tcp",
            payload = "hello loopback".toByteArray(Charsets.UTF_8),
        )
        assertEquals(1, chunks.size)
        val chunk = chunks.single()
        var acked = false
        runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 10_000)
                s.soTimeout = 10_000
                val out = s.getOutputStream()
                out.write(RcsMsrpFraming.serializeChunk(chunk))
                out.flush()
                val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                var guard = 0
                while (guard++ < 16) {
                    val reply = input.readLine() ?: break
                    val parsed = RcsMsrpFraming.parseResponseStart(reply) ?: continue
                    if (parsed.first == chunk.txid) {
                        acked = parsed.second in 200..299
                        break
                    }
                }
            }
        }
        assertTrue(done.await(15, TimeUnit.SECONDS), "peer thread finished")
        val got = received.get()
        assertTrue(got.contains("Byte-Range: 1-14/14"), "peer saw full range: $got")
        assertTrue(got.contains("hello loopback"), "peer saw body: $got")
        assertTrue(acked, "client saw 200 for its txid")
    }
}
