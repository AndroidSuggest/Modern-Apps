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
 * Passive MSRP listen side (RFC 4975 §5 + RFC 4145 `setup:passive`).
 *
 * Until now we were ACTIVE-only: we always connected out and nulled the
 * remote path when a peer answered `setup:active` (see `RcsMsrp.connect`).
 * This closes that gap: a `ServerSocket` bound to the IMS PDN
 * ([RcsImsNetwork.listenSocket]) lets peers connect TO us, so `setup:active`
 * offers/answers become usable sessions instead of pager-mode fallbacks.
 *
 * Negotiation (RFC 4145, MSRP binding):
 * - Outgoing INVITE: offer `setup:actpass` + our listen `a=path`. A peer
 *   answering `active` connects to us; a peer answering `passive` keeps the
 *   old connect-out path (we answer `active`... i.e. we connect out).
 * - Incoming INVITE offering `setup:active` (+ a usable remote path): answer
 *   `setup:passive` with our listen `a=path` and accept their TCP connection.
 * - Incoming INVITE offering `passive`/`actpass`: keep the v1 behavior
 *   (answer `active`, connect out).
 *
 * Accept matching: the first SEND on an accepted socket carries To-Path (our
 * advertised path) + From-Path (the peer). We match To-Path against pending
 * listen paths to route the socket to its session. Unmatched sockets get a
 * 481 and are closed — never left dangling.
 *
 * Lifecycle: [ensureListening] binds on demand (one socket per process,
 * shared across sessions — paths differ per session id); [stop] closes the
 * socket + accept scope. Owned by `RcsSyncService` alongside the MSRP
 * connection map. Never throws; null/false on any failure.
 */
object RcsMsrpListen {
    private const val TLS_HANDSHAKE_BYTE = 0x16
    private const val HEAD_GUARD = 32
    private const val TAG = "RcsMsrpListen"
    private const val ACCEPT_TIMEOUT_MS = 15_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Active listen socket, or null when not listening. */
    @Volatile
    private var listen: RcsImsNetwork.ListenSocket? = null

    /**
     * Pending listen paths: our advertised `a=path` → listen target, for
     * routing accepted sockets to sessions. Entries are consumed on first
     * match; stale entries are dropped by [stop] / session teardown.
     */
    private data class PendingListen(
        val conversationId: String,
        /** True when the session negotiated TLS (accept must handshake). */
        val secure: Boolean,
        /** Peer's SDP fingerprint when known (verified at accept). */
        var peerFingerprint: String? = null,
    )

    private val pendingPaths = java.util.concurrent.ConcurrentHashMap<String, PendingListen>()

    /** True when a listen socket is currently bound. */
    fun isListening(): Boolean =
        RcsFeature.enabled && listen?.let { !it.server.isClosed } == true

    /** Advertised local IP for SDP `c=` lines, or null when not listening. */
    fun listenIp(): String? = listen?.takeIf { !it.server.isClosed }?.localIp

    /** Advertised local port for SDP `m=` lines, or null when not listening. */
    fun listenPort(): Int? = listen?.takeIf { !it.server.isClosed }?.localPort

    /**
     * Ensure the listen socket is bound. Idempotent — returns true when
     * already listening. Starts the accept loop on first bind.
     *
     * [onAccepted] receives the matched conversation id, the live socket, and
     * the already-consumed request head ([AcceptedHead]) so the MSRP reader
     * can replay it before the live stream.
     */
    suspend fun ensureListening(
        context: Context,
        onAccepted: (conversationId: String, socket: java.net.Socket, head: AcceptedHead) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext false
        if (isListening()) return@withContext true
        val bound = RcsImsNetwork.listenSocket(context) ?: return@withContext false
        listen = bound
        appContext = context.applicationContext
        Log.i(TAG, "Listening on ${bound.localIp}:${bound.localPort}")
        scope.launch { acceptLoop(bound, onAccepted) }
        true
    }

    /**
     * Advertise a listen path for [conversationId]: returns the `a=path`
     * value to put in our SDP offer/answer, binding the listen socket first
     * when needed. Null when listening is unavailable (caller falls back to
     * active-only offers).
     *
     * [secure] marks a TLS session (accept must TLS-handshake);
     * [peerFingerprint] pins the peer when already known (incoming offers).
     * For outgoing offers the peer fingerprint arrives with the SDP answer —
     * record it via [notePeerFingerprint].
     */
    suspend fun advertisePath(
        context: Context,
        conversationId: String,
        secure: Boolean = false,
        peerFingerprint: String? = null,
        onAccepted: (String, java.net.Socket, AcceptedHead) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext null
        if (!ensureListening(context, onAccepted)) return@withContext null
        val bound = listen ?: return@withContext null
        val path = bound.msrpPath("sess-${UUID.randomUUID().toString().take(8)}")
        pendingPaths[path] = PendingListen(conversationId, secure, peerFingerprint)
        path
    }

    /**
     * Record the peer's SDP fingerprint for a pending listen path (arrives
     * with the SDP answer to our outgoing offer). Best-effort no-op when no
     * path is pending.
     */
    fun notePeerFingerprint(conversationId: String, fingerprint: String) {
        pendingPaths.values.firstOrNull { it.conversationId == conversationId }
            ?.let { it.peerFingerprint = fingerprint }
    }

    /** Drop the pending path for [conversationId] (session torn down). */
    fun dropPending(conversationId: String) {
        pendingPaths.entries.removeIf { it.value.conversationId == conversationId }
    }

    /** Close the listen socket + accept loop; drop pending paths. */
    fun stop() {
        runCatching { listen?.close() }
        listen = null
        appContext = null
        pendingPaths.clear()
    }

    private suspend fun acceptLoop(
        bound: RcsImsNetwork.ListenSocket,
        onAccepted: (String, java.net.Socket, AcceptedHead) -> Unit,
    ) = withContext(Dispatchers.IO) {
        while (!bound.server.isClosed && RcsFeature.enabled) {
            val socket = runCatching { bound.server.accept() }.getOrNull() ?: break
            runCatching { socket.soTimeout = 0 }
            scope.launch { routeAccepted(socket, onAccepted) }
        }
        if (listen === bound) listen = null
        Log.i(TAG, "Accept loop ended")
    }

    /**
     * Already-consumed request head from an accepted socket: the MSRP start
     * line + header lines + the blank-line terminator. The body + end-marker
     * are still in the live stream. [replayBytes] re-serializes the head so
     * the MSRP reader sees the full first SEND.
     */
    data class AcceptedHead(val lines: List<String>) {
        fun replayBytes(): ByteArray = buildString {
            lines.forEach { append(it).append("\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
    }

    /**
     * Route one accepted socket.
     *
     * TLS detection comes first: a TLS ClientHello record starts with 0x16,
     * while plaintext MSRP starts with 'M'. Exactly one byte is peeked (then
     * replayed), so:
     * - 0x16 → TLS server handshake first, then the first SEND is read from
     *   the decrypted stream and matched — the matched pending path must be
     *   a secure one, and the peer fingerprint is verified when known.
     * - otherwise → plaintext head peek as before; the matched path must be
     *   a plaintext one (a peer skipping TLS on a secure path is rejected).
     *
     * Unmatched/failed sockets get a 481 when the framing allows, then close.
     */
    private suspend fun routeAccepted(
        socket: java.net.Socket,
        onAccepted: (String, java.net.Socket, AcceptedHead) -> Unit,
    ) {
        runCatching {
            socket.soTimeout = ACCEPT_TIMEOUT_MS
            val raw = socket.getInputStream()
            val firstByte = raw.read()
            if (firstByte < 0) {
                runCatching { socket.close() }
                return
            }
            if (firstByte == TLS_HANDSHAKE_BYTE) {
                routeTls(socket, raw, firstByte, onAccepted)
                return
            }
            val input = java.io.SequenceInputStream(
                java.io.ByteArrayInputStream(byteArrayOf(firstByte.toByte())),
                raw,
            ).bufferedReader(Charsets.UTF_8)
            routePlain(socket, input, onAccepted)
        }.onFailure {
            Log.w(TAG, "Accept routing failed", it)
            runCatching { socket.close() }
        }
    }

    /** Plaintext branch: peek the first SEND head, match To-Path, hand over. */
    private fun routePlain(
        socket: java.net.Socket,
        input: java.io.BufferedReader,
        onAccepted: (String, java.net.Socket, AcceptedHead) -> Unit,
    ) {
        // Peek the first MSRP request head (bounded: 32 lines max).
        val headLines = mutableListOf<String>()
        var line = runCatching { input.readLine() }.getOrNull()
        var guard = 0
        while (line != null && line.isNotEmpty() && guard++ < HEAD_GUARD) {
            headLines.add(line)
            line = runCatching { input.readLine() }.getOrNull()
        }
        if (line == null && headLines.isEmpty()) {
            runCatching { socket.close() }
            return
        }
        val first = headLines.firstOrNull() ?: run {
            runCatching { socket.close() }
            return
        }
        if (!first.startsWith("MSRP ")) {
            runCatching { socket.close() }
            return
        }
        val toPath = headLines.firstOrNull { it.startsWith("To-Path:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        val pending = toPath?.let { pendingPaths.remove(it) }
        if (pending == null) {
            send481(socket, first, toPath)
            runCatching { socket.close() }
            Log.w(TAG, "Rejected inbound MSRP for unknown path $toPath")
            return
        }
        if (pending.secure) {
            // Peer skipped TLS on a secure path — reject, don't downgrade
            // silently (downgrade negotiation belongs in SDP, not here).
            Log.w(TAG, "Rejected plaintext SEND on secure path $toPath")
            runCatching { socket.close() }
            return
        }
        // Matched: hand over the live socket + consumed head for replay
        // (the live stream already consumed through the blank line, so the
        // reader replays the full head ahead of it).
        socket.soTimeout = 0
        onAccepted(pending.conversationId, socket, AcceptedHead(headLines))
    }

    /**
     * TLS branch: complete the server handshake over [tcp] (whose first
     * ClientHello byte was consumed into [consumed]), then read + match the
     * first SEND from the decrypted stream. The matched path must be secure;
     * the peer fingerprint is verified when known.
     */
    private suspend fun routeTls(
        tcp: java.net.Socket,
        raw: java.io.InputStream,
        consumed: Int,
        onAccepted: (String, java.net.Socket, AcceptedHead) -> Unit,
    ) {
        // Rebuild the full stream: consumed byte + remainder, then handshake.
        val replayed = java.io.SequenceInputStream(
            java.io.ByteArrayInputStream(byteArrayOf(consumed.toByte())),
            raw,
        )
        // Temporarily swap the socket's input for the handshake: SSLSocket
        // wraps the TCP socket directly, so feed it a stream that starts
        // with the consumed byte. Achieve this by layering TLS over a
        // delegating socket whose getInputStream returns the replayed bytes.
        val peekSocket = object : java.net.Socket() {
            override fun getInputStream(): java.io.InputStream = replayed
            override fun getOutputStream(): java.io.OutputStream = tcp.getOutputStream()
            override fun getInetAddress(): java.net.InetAddress? = tcp.inetAddress
            override fun getPort(): Int = tcp.port
            override fun close() = tcp.close()
            override fun isClosed(): Boolean = tcp.isClosed
            override fun isConnected(): Boolean = tcp.isConnected
        }
        val ssl = acceptTls(peekSocket) ?: run {
            runCatching { tcp.close() }
            Log.w(TAG, "TLS accept failed")
            return
        }
        // Handshake done — now read the first SEND head from TLS plaintext.
        val input = ssl.getInputStream().bufferedReader(Charsets.UTF_8)
        val headLines = mutableListOf<String>()
        var line = runCatching { input.readLine() }.getOrNull()
        var guard = 0
        while (line != null && line.isNotEmpty() && guard++ < HEAD_GUARD) {
            headLines.add(line)
            line = runCatching { input.readLine() }.getOrNull()
        }
        val first = headLines.firstOrNull()
        if (first == null || !first.startsWith("MSRP ")) {
            runCatching { ssl.close() }
            return
        }
        val toPath = headLines.firstOrNull { it.startsWith("To-Path:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        val pending = toPath?.let { pendingPaths.remove(it) }
        if (pending == null) {
            send481(ssl, first, toPath)
            runCatching { ssl.close() }
            Log.w(TAG, "Rejected inbound MSRP-TLS for unknown path $toPath")
            return
        }
        if (!pending.secure) {
            // Peer did TLS on a plaintext path: accept the media anyway (TLS
            // is strictly stronger; the SDP answer just didn't advertise it).
            Log.i(TAG, "Peer used TLS on plaintext path $toPath — accepting")
        }
        if (!RcsMsrpTls.verifyServerSide(ssl, pending.peerFingerprint)) {
            Log.w(TAG, "TLS peer fingerprint mismatch for ${pending.conversationId}")
            runCatching { ssl.close() }
            return
        }
        ssl.soTimeout = 0
        onAccepted(pending.conversationId, ssl, AcceptedHead(headLines))
    }

    /** 481 Session Does Not Exist, per RFC 4975 §7.1. */
    private fun send481(socket: java.net.Socket, firstLine: String, toPath: String?) {
        val txid = firstLine.split(" ").getOrNull(1).orEmpty()
        if (txid.isEmpty()) return
        runCatching {
            val out = socket.getOutputStream()
            out.write("MSRP $txid 481 Session Does Not Exist\r\n".toByteArray(Charsets.UTF_8))
            out.write("To-Path: $toPath\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write("-------$txid\$\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    /**
     * TLS server handshake over an accepted [tcp] socket: present our cert,
     * request the peer's. Returns the established `SSLSocket`, or null on
     * handshake failure. Fingerprint verification happens in [routeTls]
     * after To-Path matching (the expected value is per-session).
     */
    private suspend fun acceptTls(
        tcp: java.net.Socket,
    ): javax.net.ssl.SSLSocket? = withContext(Dispatchers.IO) {
        val app = appContext ?: return@withContext null
        val sslContext = RcsMsrpTls.serverContext(app) ?: return@withContext null
        runCatching {
            val ssl = sslContext.socketFactory.createSocket(
                tcp,
                tcp.inetAddress?.hostAddress,
                tcp.port,
                true,
            ) as javax.net.ssl.SSLSocket
            ssl.useClientMode = false
            ssl.wantClientAuth = true
            ssl.soTimeout = ACCEPT_TIMEOUT_MS
            ssl.startHandshake()
            ssl.soTimeout = 0
            Log.i(TAG, "TLS accept established")
            ssl
        }.getOrElse {
            Log.w(TAG, "TLS accept handshake failed", it)
            runCatching { tcp.close() }
            null
        }
    }

    /** App context for the TLS server context (set by ensureListening). */
    @Volatile
    private var appContext: Context? = null
}
