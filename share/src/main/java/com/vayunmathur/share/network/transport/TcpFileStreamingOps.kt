package com.vayunmathur.share.network.transport

import com.vayunmathur.share.domain.protocol.PendingFile
import com.vayunmathur.share.domain.protocol.ShareState
import com.vayunmathur.share.platform.ReceivedFileStore
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * File-streaming half of [TcpTransport]: announce staged files, wait for the
 * peer to accept, then stream each file through the session's open/write/close
 * calls. Split from TcpTransport.kt (TooManyFunctions split); behavior identical.
 */

internal fun TcpTransport.stageFiles(conn: Connection, files: List<File>): List<PendingFile>? {
    val staged = files.map {
        PendingFile(
            name = it.name,
            sizeBytes = it.length(),
            // A real type, so the peer shows an image as an image rather than a document.
            mimeType = ReceivedFileStore.mimeTypeOf(it),
        )
    }
    conn.expectedTotalBytes.value = staged.sumOf { it.sizeBytes }
    if (conn.session.setFilesToSend(staged) < 0 || conn.session.queueIntroduction() < 0) {
        conn.error.value = "Failed to announce files"
        conn.state.value = ShareState.Failed
        return null
    }
    return staged
}

/** Poll until the peer accepts (Rust flips to Transferring) or the deadline passes. */
internal suspend fun TcpTransport.awaitAccept(conn: Connection): Boolean {
    val deadline = System.currentTimeMillis() + ACCEPT_WAIT_MS
    while (currentCoroutineContext().isActive) {
        conn.updateStateFromSession()
        val state = conn.state.value
        if (state == ShareState.Transferring) return true
        if (state == ShareState.Failed) return false
        if (System.currentTimeMillis() > deadline) {
            conn.error.value = "peer never accepted the transfer"
            conn.state.value = ShareState.Failed
            return false
        }
        delay(POLL_INTERVAL_MS)
    }
    return false
}

/**
 * Stream one [file] through the session's open/write/close calls.
 * False when the transfer failed or was cancelled — the caller stops.
 */
internal fun TcpTransport.streamFile(conn: Connection, file: File): Boolean {
    val session = conn.session
    val rc = session.openFile(file.name, file.length())
    if (rc < 0) {
        conn.error.value = "openFile failed ($rc) for ${file.name}"
        conn.state.value = ShareState.Failed
        return false
    }
    file.inputStream().use { input ->
        if (!writeStream(conn, input)) return false
    }
    session.closeFile()
    drainAll(conn)
    conn.updateStateFromSession()
    return conn.state.value != ShareState.Failed
}

/** Copy [input] into the session chunk by chunk. False on failure. */
internal fun TcpTransport.writeStream(conn: Connection, input: java.io.InputStream): Boolean {
    val chunkBuf = ByteArray(STREAM_CHUNK_SIZE)
    while (true) {
        val n = input.read(chunkBuf)
        if (n <= 0) return true
        val chunk = if (n == chunkBuf.size) chunkBuf.copyOf() else chunkBuf.copyOf(n)
        val wrc = conn.session.writeChunk(chunk)
        if (wrc < 0) {
            conn.error.value = "writeChunk failed ($wrc)"
            conn.state.value = ShareState.Failed
            return false
        }
        conn.bytesSent.value += n
        // Flush after each chunk so the pump's next drain picks it up.
        drainAll(conn)
        conn.updateStateFromSession()
        if (conn.state.value == ShareState.Failed) return false
    }
}
