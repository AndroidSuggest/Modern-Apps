package com.vayunmathur.communicate.data.rcs

import java.util.UUID

/**
 * Pure MSRP framing (RFC 4975): SEND chunk splitting, REPORT requests,
 * response matching, AUTH stubs, keepalive shape.
 *
 * Extracted from `RcsMsrp` for unit-testability — no sockets, no coroutines,
 * no Android. The socket object owns I/O; everything byte-shaping lives here.
 */
object RcsMsrpFraming {
    /** Default SEND chunk size for outgoing chunked transfers. */
    const val CHUNK_SIZE = 2048

    /** Keepalive: empty chunk range + no body (RFC 4975 §7.4). */
    const val KEEPALIVE_RANGE = "1-0/0"

    /** One outgoing SEND chunk: headers + body slice. */
    data class OutChunk(
        val txid: String,
        val messageId: String,
        /** Full head bytes (headers + blank line), ready to write. */
        val head: ByteArray,
        /** Body slice bytes. */
        val body: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is OutChunk) return false
            return txid == other.txid && messageId == other.messageId &&
                head.contentEquals(other.head) && body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var result = txid.hashCode()
            result = 31 * result + messageId.hashCode()
            result = 31 * result + head.contentHashCode()
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    /**
     * Split [payload] into SEND chunks of at most [chunkSize] bytes sharing
     * [messageId], with `Byte-Range: start-end/total` (§2.2). Single chunk
     * for small payloads (identical to the legacy one-shot shape).
     */
    fun splitSend(
        toPath: String,
        fromPath: String,
        payload: ByteArray,
        contentType: String = "message/cpim",
        messageId: String = UUID.randomUUID().toString(),
        chunkSize: Int = CHUNK_SIZE,
        failureReport: Boolean = true,
        successReport: Boolean = false,
    ): List<OutChunk> {
        if (payload.isEmpty()) return emptyList()
        val total = payload.size
        val out = mutableListOf<OutChunk>()
        var offset = 0
        while (offset < total) {
            val end = minOf(offset + chunkSize, total)
            val slice = payload.copyOfRange(offset, end)
            val txid = UUID.randomUUID().toString().replace("-", "").take(12)
            val head = buildString {
                append("MSRP $txid SEND\r\n")
                append("To-Path: $toPath\r\n")
                append("From-Path: $fromPath\r\n")
                append("Message-ID: $messageId\r\n")
                append("Byte-Range: ${offset + 1}-$end/$total\r\n")
                append("Failure-Report: ${if (failureReport) "yes" else "no"}\r\n")
                append("Success-Report: ${if (successReport) "yes" else "no"}\r\n")
                append("Content-Type: $contentType\r\n")
            }.toByteArray(Charsets.UTF_8) + "\r\n".toByteArray(Charsets.UTF_8)
            out += OutChunk(txid, messageId, head, slice)
            offset = end
        }
        return out
    }

    /**
     * Serialize one chunk to wire bytes (head + body + end-marker), the exact
     * bytes the socket layer writes.
     */
    fun serializeChunk(chunk: OutChunk): ByteArray =
        chunk.head + chunk.body +
            "\r\n-------${chunk.txid}\$\r\n".toByteArray(Charsets.UTF_8)

    /**
     * Build a REPORT request (§2.1, RFC 4975 §7.1/§8): [status] is a
     * `namespace + code` pair, e.g. `000 200 OK`. [byteRange] echoes the
     * failed/acked range.
     */
    fun buildReport(
        toPath: String,
        fromPath: String,
        messageId: String,
        statusNamespace: Int = 0,
        statusCode: Int,
        reason: String,
        byteRange: String,
    ): ByteArray {
        val txid = UUID.randomUUID().toString().replace("-", "").take(12)
        return buildString {
            append("MSRP $txid REPORT\r\n")
            append("To-Path: $toPath\r\n")
            append("From-Path: $fromPath\r\n")
            append("Message-ID: $messageId\r\n")
            append("Byte-Range: $byteRange\r\n")
            append("Status: ${"%03d".format(statusNamespace)} $statusCode $reason\r\n")
        }.toByteArray(Charsets.UTF_8) +
            "\r\n-------$txid\$\r\n".toByteArray(Charsets.UTF_8)
    }

    /** Build a keepalive empty SEND (§2.5): no body, `Byte-Range: 1-0/0`. */
    fun buildKeepalive(toPath: String, fromPath: String): ByteArray {
        val txid = UUID.randomUUID().toString().replace("-", "").take(12)
        return buildString {
            append("MSRP $txid SEND\r\n")
            append("To-Path: $toPath\r\n")
            append("From-Path: $fromPath\r\n")
            append("Byte-Range: $KEEPALIVE_RANGE\r\n")
            append("Failure-Report: no\r\n")
            append("Success-Report: no\r\n")
        }.toByteArray(Charsets.UTF_8) +
            "\r\n-------$txid\$\r\n".toByteArray(Charsets.UTF_8)
    }

    /** Build a minimal error response (413/415/481/501…) to [txid]. */
    fun buildErrorResponse(toPath: String, txid: String, code: Int, reason: String): ByteArray =
        buildString {
            append("MSRP $txid $code $reason\r\n")
            if (toPath.isNotBlank()) append("To-Path: $toPath\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8) +
            "-------$txid\$\r\n".toByteArray(Charsets.UTF_8)

    /** Parse an MSRP response start line into (txid, code, reason), or null. */
    fun parseResponseStart(line: String): Triple<String, Int, String>? {
        if (!line.startsWith("MSRP ")) return null
        val parts = line.split(" ")
        if (parts.size < 3) return null
        // MSRP <txid> SEND/REPORT (request) vs MSRP <txid> <code> (response):
        // requests have an all-alpha method token.
        val third = parts[2]
        if (third.all { it.isLetter() }) return null
        val code = third.toIntOrNull() ?: return null
        return Triple(parts[1], code, parts.drop(3).joinToString(" "))
    }

    /** True when [line] is an MSRP request (SEND/REPORT/AUTH/…). */
    fun isRequest(line: String): Boolean {
        if (!line.startsWith("MSRP ")) return false
        val third = line.split(" ").getOrNull(2).orEmpty()
        return third.isNotEmpty() && third.all { it.isLetter() }
    }

    /** Parse a Status header value into (namespace, code, reason), or null. */
    fun parseStatus(value: String): Triple<Int, Int, String>? {
        val parts = value.trim().split(" ", limit = 3)
        if (parts.size < 2) return null
        val ns = parts[0].toIntOrNull() ?: return null
        val code = parts[1].toIntOrNull() ?: return null
        return Triple(ns, code, parts.getOrElse(2) { "" })
    }
}
