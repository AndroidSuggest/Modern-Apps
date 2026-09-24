package com.vayunmathur.communicate.data.rcs

import android.util.Log
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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
 * Sockets open on Dispatchers.IO; every failure returns null/false so callers
 * fall back to pager-mode. No Guava — plain suspend functions.
 */
object RcsMsrp {
    private const val TAG = "RcsMsrp"

    /**
     * Send [payload] (CPIM bytes) over an MSRP session bound to [session].
     * Opens a TCP socket to the remote path when needed. Returns true on a
     * 200 response to our SEND.
     */
    suspend fun send(
        session: RcsSession,
        payload: ByteArray,
        contentType: String = "message/cpim",
    ): Boolean = withContext(Dispatchers.IO) {
        if (!RcsFeature.enabled) return@withContext false
        val remotePath = session.msrpRemotePath ?: return@withContext false
        val localPath = session.msrpLocalPath ?: "msrp://local.invalid/${UUID.randomUUID()};tcp"
        runCatching {
            val (host, port) = parseMsrpPath(remotePath) ?: return@runCatching false
            val txid = UUID.randomUUID().toString().replace("-", "").take(12)
            val messageId = UUID.randomUUID().toString()
            val chunk = buildString {
                append("MSRP $txid SEND\r\n")
                append("To-Path: $remotePath\r\n")
                append("From-Path: $localPath\r\n")
                append("Message-ID: $messageId\r\n")
                append("Byte-Range: 1-${payload.size}/${payload.size}\r\n")
                append("Failure-Report: yes\r\n")
                append("Success-Report: no\r\n")
                append("Content-Type: $contentType\r\n")
            }
            val socket = runCatching {
                val s = java.net.Socket()
                s.connect(java.net.InetSocketAddress(host, port), 10_000)
                s.soTimeout = 10_000
                s
            }.getOrNull() ?: return@runCatching false
            socket.use { s ->
                val out = s.getOutputStream()
                out.write(chunk.toByteArray(Charsets.UTF_8))
                out.write("\r\n".toByteArray(Charsets.UTF_8))
                out.write(payload)
                out.write("\r\n-------$txid\$\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
                val reply = s.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                    .orEmpty()
                reply.contains(" 200 ")
            }
        }.getOrElse {
            Log.w(TAG, "MSRP send failed", it)
            false
        }
    }

    /**
     * Parse an `msrp://host[:port]/...` path into (host, port). Default MSRP
     * port 2855 when absent.
     */
    fun parseMsrpPath(path: String): Pair<String, Int>? {
        return runCatching {
            val uri = android.net.Uri.parse(path)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 2855
            host to port
        }.getOrNull()
    }
}
