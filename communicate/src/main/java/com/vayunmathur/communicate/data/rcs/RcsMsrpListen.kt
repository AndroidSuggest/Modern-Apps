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
    private const val TAG = "RcsMsrpListen"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Active listen socket, or null when not listening. */
    @Volatile
    private var listen: RcsImsNetwork.ListenSocket? = null

    /**
     * Pending listen paths: our advertised `a=path` → conversation id, for
     * routing accepted sockets to sessions. Entries are consumed on first
     * match; stale entries are dropped by [stop] / session teardown.
     */
    private val pendingPaths = java.util.concurrent.ConcurrentHashMap<String, String>()

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
        Log.i(TAG, "Listening on ${bound.localIp}:${bound.localPort}")
        scope.launch { acceptLoop(bound, onAccepted) }
        true
    }

    /**
     * Advertise a listen path for [conversationId]: returns the `a=path`
     * value to put in our SDP offer/answer, binding the listen socket first
     * when needed. Null when listening is unavailable (caller falls back to
     * active-only offers).
     */
    suspend fun advertisePath(
        context: Context,
        conversationId: String,
        onAccepted: (String, java.net.Socket, AcceptedHead) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext null
        if (!ensureListening(context, onAccepted)) return@withContext null
        val bound = listen ?: return@withContext null
        val path = bound.msrpPath("sess-${UUID.randomUUID().toString().take(8)}")
        pendingPaths[path] = conversationId
        path
    }

    /** Drop the pending path for [conversationId] (session torn down). */
    fun dropPending(conversationId: String) {
        pendingPaths.entries.removeIf { it.value == conversationId }
    }

    /** Close the listen socket + accept loop; drop pending paths. */
    fun stop() {
        runCatching { listen?.close() }
        listen = null
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
     * Route one accepted socket: read the first SEND's To-Path, match it
     * against pending listen paths, hand the socket + head to the session.
     * Closes unmatched/failed sockets with a 481 when the framing allows.
     */
    private fun routeAccepted(
        socket: java.net.Socket,
        onAccepted: (String, java.net.Socket, AcceptedHead) -> Unit,
    ) {
        runCatching {
            socket.soTimeout = 15_000
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            // Peek the first MSRP request head (bounded: 32 lines max).
            val headLines = mutableListOf<String>()
            var line = runCatching { input.readLine() }.getOrNull()
            var guard = 0
            while (line != null && line.isNotEmpty() && guard++ < 32) {
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
            val conversationId = toPath?.let { pendingPaths.remove(it) }
            if (conversationId == null) {
                // Unknown path: 481 per RFC 4975 §7.1, then close.
                val txid = first.split(" ").getOrNull(1).orEmpty()
                if (txid.isNotEmpty()) {
                    runCatching {
                        val out = socket.getOutputStream()
                        out.write("MSRP $txid 481 Session Does Not Exist\r\n".toByteArray(Charsets.UTF_8))
                        out.write("To-Path: $toPath\r\n\r\n".toByteArray(Charsets.UTF_8))
                        out.write("-------$txid\$\r\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
                runCatching { socket.close() }
                Log.w(TAG, "Rejected inbound MSRP for unknown path $toPath")
                return
            }
            // Matched: hand over the live socket + consumed head for replay.
            socket.soTimeout = 0
            onAccepted(conversationId, socket, AcceptedHead(headLines))
        }.onFailure {
            Log.w(TAG, "Accept routing failed", it)
            runCatching { socket.close() }
        }
    }
}
