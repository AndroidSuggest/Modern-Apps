package com.vayunmathur.communicate.data.rcs

import android.content.Context
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal MSRP (RFC 4975) over the negotiated session socket.
 *
 * TestRcsApp's `MsrpManager`/`MsrpSession` ride a raw TCP socket to the
 * negotiated `a=path`; chunks are `MSRP <txid> SEND` with To-Path/From-Path,
 * Byte-Range, Message-ID and Content-Type headers. This port covers the
 * SEND + 200-response subset needed for CPM chat payloads (CPIM text and
 * IMDN reports travel inside sessions once established; pager-mode remains
 * the pre-session path).
 *
 * ACTIVE-only: we always connect out, never listen (same as TestRcsApp —
 * `MsrpManager` has no accept loop). Sockets open on Dispatchers.IO; every
 * failure returns null/false so callers fall back to pager-mode. No Guava —
 * plain suspend functions.
 */
object RcsMsrp {
    private const val TAG = "RcsMsrp"

    /**
     * Process-wide fallback for inbound MSRP chunks arriving on transient
     * connections (e.g. FT-over-MSRP transfer sockets). The sync service sets
     * this to route into the inbox; unset (null) drops.
     */
    @Volatile
    var onInboundFallback: ((conversationId: String, contentType: String, body: ByteArray) -> Unit)? = null

    /**
     * A persistent MSRP connection for one session: one socket, reused across
     * SENDs, with a reader loop feeding inbound chunks to [onChunk].
     * Create via [connect]; [close] on BYE / teardown.
     */
    class MsrpConnection internal constructor(
        private val socket: java.net.Socket,
        private val localPath: String,
        private val remotePath: String,
    ) {
        @Volatile private var closed = false
        private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        internal fun launchReader(block: suspend () -> Unit) {
            readerScope.launch { block() }
        }

        /** Last I/O time (any direction); drives the keepalive (§2.5). */
        @Volatile internal var lastActivityMs: Long = System.currentTimeMillis()

        /**
         * Send one CPIM payload as chunked MSRP SENDs (§2.2): splits large
         * payloads into `Byte-Range` slices sharing one Message-ID, awaiting
         * a 2xx response per chunk. Returns true when every chunk was acked.
         * Thread-safe (single-writer lock). Acks are completed by the reader
         * loop ([pendingAcks]) — never read here, the stream is single-reader.
         */
        private val writeLock = Any()

        suspend fun sendCpim(payload: ByteArray, contentType: String = "message/cpim"): Boolean {
            if (closed || payload.isEmpty()) return false
            return runCatching {
                val chunks = RcsMsrpFraming.splitSend(
                    toPath = remotePath,
                    fromPath = localPath,
                    payload = payload,
                    contentType = contentType,
                )
                for (chunk in chunks) {
                    val ack = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    synchronized(writeLock) {
                        if (closed) return false
                        pendingAcks[chunk.txid] = ack
                        runCatching {
                            val out = socket.getOutputStream()
                            out.write(RcsMsrpFraming.serializeChunk(chunk))
                            out.flush()
                            lastActivityMs = System.currentTimeMillis()
                        }.onFailure {
                            pendingAcks.remove(chunk.txid)
                            throw it
                        }
                    }
                    val ok = try {
                        kotlinx.coroutines.withTimeout(10_000L) { ack.await() }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        false
                    }
                    pendingAcks.remove(chunk.txid)
                    if (!ok) return false
                }
                true
            }.getOrElse {
                Log.w(TAG, "MSRP chunk send failed", it)
                false
            }
        }

        /**
         * Chunk ack futures, keyed by SEND txid. Completed by the reader loop
         * when the matching response arrives; senders await with timeout.
         */
        internal val pendingAcks =
            java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

        /** Inbound REPORT notifications: (messageId, ns, code, reason). */
        internal val inboundReports =
            java.util.concurrent.ConcurrentLinkedQueue<ReportNotice>()

        internal data class ReportNotice(
            val messageId: String,
            val namespace: Int,
            val code: Int,
            val reason: String,
        )

        /** Send a keepalive empty SEND (§2.5). Best-effort. */
        internal fun sendKeepalive(): Boolean {
            if (closed) return false
            return runCatching {
                synchronized(writeLock) {
                    val out = socket.getOutputStream()
                    out.write(RcsMsrpFraming.buildKeepalive(remotePath, localPath))
                    out.flush()
                    lastActivityMs = System.currentTimeMillis()
                }
                true
            }.getOrDefault(false)
        }

        /** Emit a REPORT for [messageId] (§2.1). Best-effort. */
        internal fun sendReport(
            messageId: String,
            statusCode: Int,
            reason: String,
            byteRange: String,
        ): Boolean {
            if (closed) return false
            return runCatching {
                synchronized(writeLock) {
                    val out = socket.getOutputStream()
                    out.write(
                        RcsMsrpFraming.buildReport(
                            toPath = remotePath,
                            fromPath = localPath,
                            messageId = messageId,
                            statusCode = statusCode,
                            reason = reason,
                            byteRange = byteRange,
                        ),
                    )
                    out.flush()
                    lastActivityMs = System.currentTimeMillis()
                }
                true
            }.getOrDefault(false)
        }

        /** Start the idle keepalive job (§2.5): empty SEND when quiet >60s. */
        internal fun launchKeepalive() {
            readerScope.launch {
                while (!closed) {
                    kotlinx.coroutines.delay(30_000L)
                    if (closed) break
                    if (System.currentTimeMillis() - lastActivityMs >= 60_000L) {
                        sendKeepalive()
                    }
                }
            }
        }

        fun close() {
            closed = true
            runCatching { socket.close() }
            readerScope.cancel()
        }

        internal fun isClosed(): Boolean = closed || socket.isClosed
    }

    /**
     * Wrap an accepted (passive-side) socket as a session connection: the
     * listener already consumed the first SEND head ([head]) for To-Path
     * routing, so the reader replays it before the live stream. [localPath]
     * is our advertised path (matches the peer's To-Path); [remotePath] is
     * the peer's From-Path, used for our SENDs back on this same socket
     * (RFC 4975 §5.4 — the passive side reuses the accepted connection).
     * [socket] may already be a TLS `SSLSocket` (secure sessions) — the
     * reader only needs the byte stream.
     */
    fun wrapAccepted(
        socket: java.net.Socket,
        head: RcsMsrpListen.AcceptedHead,
        localPath: String,
        remotePath: String,
        onChunk: (contentType: String, body: ByteArray) -> Unit,
    ): MsrpConnection {
        val conn = MsrpConnection(socket, localPath, remotePath)
        conn.launchReader { readLoop(conn, socket, onChunk, head.replayBytes()) }
        conn.launchKeepalive()
        return conn
    }

    /**
     * Open a persistent active connection for [session] and start the reader
     * loop on Dispatchers.IO. [onChunk] receives complete `message/cpim`
     * bodies (content type + bytes). Returns null when the session has no
     * usable remote path or when neither side can connect (peer is
     * `setup=active` AND we have no listen socket — see [RcsMsrpListen]).
     *
     * The socket rides the IMS PDN when available ([RcsImsNetwork]) so
     * carrier MSRP peers (which only route IMS-subnet addresses) are
     * reachable; falls back to a plain socket otherwise.
     */
    suspend fun connect(
        session: RcsSession,
        context: Context,
        onChunk: (contentType: String, body: ByteArray) -> Unit,
    ): MsrpConnection? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext null
        val remotePath = session.msrpRemotePath ?: return@withContext null
        if (session.msrpSetup == MsrpSetup.ACTIVE && !RcsMsrpListen.isListening()) {
            // Peer connects to us, but we have no listen socket — nothing to do.
            // (When listening, the accept loop owns this direction; connect-out
            // is skipped and the session completes on accept.)
            Log.w(TAG, "Peer is active and no listen socket; cannot establish media")
            return@withContext null
        }
        if (session.msrpSetup == MsrpSetup.ACTIVE) {
            // Listen-side session: media arrives on the accepted socket.
            return@withContext null
        }
        val localPath = session.msrpLocalPath ?: "msrp://local.invalid/${UUID.randomUUID()};tcp"
        runCatching {
            val (host, port) = parseMsrpPath(remotePath) ?: return@runCatching null
            val socket = if (session.msrpSecure) {
                // TLS client: pin the peer against the fingerprint from its
                // SDP answer (stashed at response time — see session lookup).
                // Falls back to plaintext connect when no fingerprint is
                // known (downgrade, never a hard failure here).
                val peerFp = peerFingerprintFor(session)
                if (peerFp != null) {
                    RcsMsrpTls.clientSocket(context, host, port, peerFp)
                        ?: return@runCatching null
                } else {
                    Log.w(TAG, "Secure session without peer fingerprint; downgrading to plaintext")
                    RcsImsNetwork.createSocket(context, host, port) ?: return@runCatching null
                }
            } else {
                RcsImsNetwork.createSocket(context, host, port) ?: return@runCatching null
            }
            val conn = MsrpConnection(socket, localPath, remotePath)
            // Reader loop: parse SEND chunks, auto-200 them, deliver bodies.
            // Owned by the connection; dies on close.
            conn.launchReader { readLoop(conn, socket, onChunk) }
            conn.launchKeepalive()
            conn
        }.getOrElse {
            Log.w(TAG, "MSRP connect failed", it)
            null
        }
    }

    /**
     * Peer's TLS fingerprint for an outgoing secure session, from the SDP
     * answer stashed at response time. Null when unknown (caller downgrades).
     */
    private fun peerFingerprintFor(session: RcsSession): String? =
        RcsSessionManager.peerFingerprint(session.callId)

    private fun readLoop(
        conn: MsrpConnection,
        socket: java.net.Socket,
        onChunk: (String, ByteArray) -> Unit,
        replayPrefix: ByteArray? = null,
    ) {
        val live = runCatching { socket.getInputStream() }.getOrNull() ?: return
        // Accepted sockets already had their first SEND head consumed for
        // To-Path routing — replay it ahead of the live stream.
        val stream = if (replayPrefix != null && replayPrefix.isNotEmpty()) {
            java.io.SequenceInputStream(java.io.ByteArrayInputStream(replayPrefix), live)
        } else {
            live
        }
        val input = stream.bufferedReader(Charsets.UTF_8)
        val pending = StringBuilder()
        // Reassembly buffer for multi-chunk SENDs, keyed by Message-ID.
        val reassembly = mutableMapOf<String, ByteArrayOutputStream2>()
        try {
            while (!conn.isClosed()) {
                val line = runCatching { input.readLine() }.getOrNull() ?: break
                if (line.startsWith("MSRP ")) {
                    pending.clear()
                    pending.append(line).append("\r\n")
                    val headers = mutableMapOf<String, String>()
                    var contentLength = -1
                    // Header block.
                    while (true) {
                        val h = runCatching { input.readLine() }.getOrNull() ?: break
                        if (h.isEmpty()) break
                        pending.append(h).append("\r\n")
                        val name = h.substringBefore(":").trim()
                        val value = h.substringAfter(":").trim()
                        headers[name.lowercase()] = value
                        if (name.equals("Content-Length", ignoreCase = true)) {
                            contentLength = value.toIntOrNull() ?: -1
                        }
                    }
                    val txid = line.split(" ").getOrNull(1).orEmpty()
                    val method = line.split(" ").getOrNull(2).orEmpty()
                    if (method.equals("SEND", ignoreCase = true)) {
                        // Body: Content-Length bytes when present, else to end-marker.
                        val bodyBytes = if (contentLength >= 0) {
                            readFixed(input, contentLength)
                        } else {
                            readToEndMarker(input, txid)
                        }
                        // Consume the end-marker line.
                        runCatching { input.readLine() }
                        conn.lastActivityMs = System.currentTimeMillis()
                        // Keepalive empty SEND (§2.5): ack quietly, no delivery.
                        val range = headers["byte-range"]
                        if (range == RcsMsrpFraming.KEEPALIVE_RANGE) {
                            sendResponse(socket, txid, 200, "OK", headers["from-path"].orEmpty())
                            continue
                        }
                        // Auto-200 the peer SEND (RFC 4975 §7.1: To-Path echoes
                        // the sender's From-Path, From-Path is our path).
                        val peerFrom = headers["from-path"].orEmpty()
                        sendResponse(socket, txid, 200, "OK", peerFrom)
                        val msgId = headers["message-id"].orEmpty()
                        val contentType = headers["content-type"] ?: "message/cpim"
                        if (msgId.isNotEmpty() && bodyBytes != null) {
                            val buf = reassembly.getOrPut(msgId) { ByteArrayOutputStream2() }
                            buf.write(bodyBytes)
                            // Single-chunk fast path: Byte-Range 1-N/N delivers now.
                            if (range == null || isCompleteRange(range)) {
                                reassembly.remove(msgId)
                                val complete = buf.toBytes()
                                if (complete.isNotEmpty()) onChunk(contentType, complete)
                            }
                        } else if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                            onChunk(contentType, bodyBytes)
                        }
                    } else if (method.equals("REPORT", ignoreCase = true)) {
                        // Inbound REPORT (§2.1): consume through the end-marker,
                        // queue the notice for the sync service (delivery
                        // correlation), no auto-response needed.
                        var guard = 0
                        while (guard++ < 32) {
                            val l = runCatching { input.readLine() }.getOrNull() ?: break
                            if (l.startsWith("-------$txid")) break
                        }
                        conn.lastActivityMs = System.currentTimeMillis()
                        val msgId = headers["message-id"].orEmpty()
                        val status = headers["status"]?.let { RcsMsrpFraming.parseStatus(it) }
                        if (msgId.isNotEmpty() && status != null) {
                            conn.inboundReports.add(
                                MsrpConnection.ReportNotice(
                                    messageId = msgId,
                                    namespace = status.first,
                                    code = status.second,
                                    reason = status.third,
                                ),
                            )
                        }
                    } else if (method.equals("AUTH", ignoreCase = true)) {
                        // MSRP auth is not negotiated on this line (§2.3):
                        // explicit 501, never silent. Consume the frame first.
                        var guard = 0
                        while (guard++ < 32) {
                            val l = runCatching { input.readLine() }.getOrNull() ?: break
                            if (l.startsWith("-------$txid")) break
                        }
                        val peerFrom = headers["from-path"].orEmpty()
                        sendAuthNotImplemented(socket, txid, peerFrom)
                    } else {
                        // Responses to our SENDs: complete the chunk ack future
                        // (§2.2). Consume through the end-marker.
                        val parsed = RcsMsrpFraming.parseResponseStart(line)
                        var guard = 0
                        while (guard++ < 32) {
                            val l = runCatching { input.readLine() }.getOrNull() ?: break
                            if (l.startsWith("-------$txid")) break
                        }
                        conn.lastActivityMs = System.currentTimeMillis()
                        if (parsed != null) {
                            conn.pendingAcks.remove(parsed.first)
                                ?.complete(parsed.second in 200..299)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Socket closed — reader exits.
        } finally {
            reassembly.clear()
            conn.close()
        }
    }

    private fun isCompleteRange(range: String): Boolean {
        // "1-N/N" with equal total and end, or "1-0/0" empty handshake.
        val m = Regex("(\\d+)-(\\d+)/(\\d+)").find(range.trim()) ?: return true
        val (_, end, total) = m.destructured
        return end == total
    }

    private fun readFixed(input: java.io.BufferedReader, n: Int): ByteArray? {
        if (n < 0 || n > 8 * 1024 * 1024) return null
        return runCatching {
            val chars = CharArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(chars, read, n - read)
                if (r < 0) break
                read += r
            }
            String(chars, 0, read).toByteArray(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun readToEndMarker(input: java.io.BufferedReader, txid: String): ByteArray? {
        return runCatching {
            val sb = StringBuilder()
            while (true) {
                val line = input.readLine() ?: break
                if (line.startsWith("-------$txid")) break
                sb.append(line).append("\r\n")
            }
            sb.toString().toByteArray(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun sendResponse(
        socket: java.net.Socket,
        txid: String,
        code: Int,
        reason: String,
        toPath: String = "",
    ) {
        runCatching {
            val out = socket.getOutputStream()
            out.write("MSRP $txid $code $reason\r\n".toByteArray(Charsets.UTF_8))
            if (toPath.isNotBlank()) out.write("To-Path: $toPath\r\n".toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
            out.write("-------$txid\$\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    /** 501 to an MSRP AUTH attempt (§2.3: auth is never negotiated here). */
    private fun sendAuthNotImplemented(socket: java.net.Socket, txid: String, toPath: String) {
        runCatching {
            val out = socket.getOutputStream()
            out.write(RcsMsrpFraming.buildErrorResponse(toPath, txid, 501, "Not Implemented"))
            out.flush()
        }
        Log.i(TAG, "Rejected MSRP AUTH with 501 (tx=$txid)")
    }

    /** Minimal byte buffer (avoids java.io.ByteArrayOutputStream import weight). */
    private class ByteArrayOutputStream2 {
        private var buf = ByteArray(1024)
        private var size = 0

        fun write(bytes: ByteArray) {
            ensure(bytes.size)
            bytes.copyInto(buf, size)
            size += bytes.size
        }

        fun toBytes(): ByteArray = buf.copyOf(size)

        private fun ensure(extra: Int) {
            if (size + extra <= buf.size) return
            var next = buf.size * 2
            while (next < size + extra) next *= 2
            buf = buf.copyOf(next)
        }
    }

    /**
     * Send [payload] (CPIM bytes) over an MSRP session bound to [session].
     * Opens a socket to the remote path when needed (IMS PDN preferred, TLS
     * when [RcsSession.msrpSecure]), chunked per §2.2. Returns true when
     * every chunk got a 2xx.
     *
     * One-shot fallback; prefer [connect] for established sessions.
     */
    suspend fun send(
        session: RcsSession,
        context: Context,
        payload: ByteArray,
        contentType: String = "message/cpim",
    ): Boolean = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext false
        val remotePath = session.msrpRemotePath ?: return@withContext false
        val localPath = session.msrpLocalPath ?: "msrp://local.invalid/${UUID.randomUUID()};tcp"
        runCatching {
            val (host, port) = parseMsrpPath(remotePath) ?: return@runCatching false
            val chunks = RcsMsrpFraming.splitSend(
                toPath = remotePath,
                fromPath = localPath,
                payload = payload,
                contentType = contentType,
            )
            if (chunks.isEmpty()) return@runCatching false
            val socket = if (session.msrpSecure) {
                val peerFp = peerFingerprintFor(session) ?: return@runCatching false
                RcsMsrpTls.clientSocket(context, host, port, peerFp)
                    ?: return@runCatching false
            } else {
                RcsImsNetwork.createSocket(context, host, port)
                    ?: return@runCatching false
            }
            socket.use { s ->
                s.soTimeout = 10_000
                val out = s.getOutputStream()
                val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
                for (chunk in chunks) {
                    out.write(RcsMsrpFraming.serializeChunk(chunk))
                    out.flush()
                    // Await this chunk's response (responses are FIFO).
                    var ok = false
                    var guard = 0
                    while (guard++ < 16) {
                        val line = runCatching { input.readLine() }.getOrNull() ?: break
                        val parsed = RcsMsrpFraming.parseResponseStart(line) ?: continue
                        if (parsed.first != chunk.txid) continue
                        var end = 0
                        while (end++ < 32) {
                            val l = runCatching { input.readLine() }.getOrNull() ?: break
                            if (l.startsWith("-------${chunk.txid}")) break
                        }
                        ok = parsed.second in 200..299
                        break
                    }
                    if (!ok) return@runCatching false
                }
                true
            }
        }.getOrElse {
            Log.w(TAG, "MSRP send failed", it)
            false
        }
    }

    /**
     * Parse an `msrp(s)://host[:port]/...` path into (host, port). Default
     * MSRP port 2855 when absent. Scheme-agnostic (covers `msrps://`); use
     * [isSecurePath] for the TLS decision.
     */
    fun parseMsrpPath(path: String): Pair<String, Int>? {
        return runCatching {
            val uri = android.net.Uri.parse(path)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 2855
            host to port
        }.getOrNull()
    }

    /** True when [path] uses the `msrps://` scheme (TLS media, RFC 4976). */
    fun isSecurePath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching { android.net.Uri.parse(path).scheme }.getOrNull()
            .equals("msrps", ignoreCase = true)
    }
}
