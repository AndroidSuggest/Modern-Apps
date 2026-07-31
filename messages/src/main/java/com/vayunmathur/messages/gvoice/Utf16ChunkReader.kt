package com.vayunmathur.messages.gvoice

import com.vayunmathur.library.network.NetworkDataStream
import java.io.EOFException

/**
 * Reads framed payloads where each frame is `<utf16-length>\n<utf8-bytes>`.
 *
 * Direct port of `pkg/libgv/utf16chunk/chunkreader.go` from
 * mautrix-gvoice. Google's BrowserChannel realtime stream frames each
 * server-pushed event as one of these chunks; the length prefix is in
 * UTF-16 code units, not bytes, so surrogate-pair code points count as 2
 * even though they occupy 4 UTF-8 bytes.
 *
 * Reads from a [NetworkDataStream] (a live streaming response body, or a
 * buffered one via `ByteArray.asNetworkDataStream()`) and exposes a single
 * suspending [readChunk] that returns the next UTF-8 payload, or null on EOF.
 */
class Utf16ChunkReader(private val channel: NetworkDataStream) {

    private val buf = ByteArray(32 * 1024)
    private var ptr: Int = 0

    /** Reads one chunk. Returns null at end-of-stream. */
    suspend fun readChunk(): ByteArray? {
        var n = ptr
        var idx = if (ptr != 0) buf.indexOfNewlineUntil(n) else -1
        while (idx == -1 && n < 10) {
            val readN = try {
                channel.read(buf, ptr, buf.size - ptr)
            } catch (e: EOFException) {
                return null
            }
            if (readN <= 0) {
                if (n == 0) return null
                throw IllegalStateException("EOF before length prefix (have $n bytes)")
            }
            ptr += readN
            n += readN
            idx = buf.indexOfNewlineUntil(n)
        }
        if (idx == -1) error("no newline found in chunk (have $n bytes)")
        var expectedLength = String(buf, 0, idx, Charsets.US_ASCII).trim().toInt()

        val (firstReceivedLength, firstByteCount) =
            utf16Length(buf, idx + 1, n, expectedLength)
        val output = ByteArray(firstByteCount)
        System.arraycopy(buf, idx + 1, output, 0, firstByteCount)

        if (firstReceivedLength >= expectedLength) {
            // Everything fit in the current buffer; shift the remainder
            // forward so the next ReadChunk picks up where we left off.
            val remaining = n - (idx + 1 + firstByteCount)
            if (remaining > 0) System.arraycopy(buf, idx + 1 + firstByteCount, buf, 0, remaining)
            ptr = remaining
            return output
        }

        // Need additional reads to satisfy the full UTF-16 length budget.
        expectedLength -= firstReceivedLength
        val data = java.io.ByteArrayOutputStream(firstByteCount + 4096)
        data.write(output)
        ptr = 0
        while (expectedLength > 0) {
            val readN = channel.read(buf, 0, buf.size)
            if (readN <= 0) error("EOF mid-chunk (need $expectedLength more code units)")
            val (received, consumed) = utf16Length(buf, 0, readN, expectedLength)
            expectedLength -= received
            data.write(buf, 0, consumed)
            if (consumed < readN) {
                val remaining = readN - consumed
                System.arraycopy(buf, consumed, buf, 0, remaining)
                ptr = remaining
            }
        }
        return data.toByteArray()
    }
}

private fun ByteArray.indexOfNewlineUntil(end: Int): Int {
    for (i in 0 until end) if (this[i] == '\n'.code.toByte()) return i
    return -1
}

/**
 * Count UTF-16 code units in [buf] from [start] (inclusive) until
 * [endExclusive] OR until [maxLength] code units have been counted —
 * whichever comes first. Returns (codeUnitsConsumed, bytesConsumed).
 *
 * Code points in the supplementary plane (>= 0x10000) count as 2 code
 * units (a surrogate pair) even though they occupy 4 UTF-8 bytes.
 */
internal fun utf16Length(
    buf: ByteArray,
    start: Int,
    endExclusive: Int,
    maxLength: Int,
): Pair<Int, Int> {
    var length = 0
    var i = start
    while (i < endExclusive) {
        val first = buf[i].toInt() and 0xFF
        val (codePoint, size) = decodeUtf8At(buf, i, endExclusive, first)
        if (codePoint >= 0x10000) length += 2 else length += 1
        i += size
        if (length >= maxLength) break
    }
    return length to (i - start)
}

/** Decode the UTF-8 code point starting at [pos] in [buf]. Returns the
 *  (codePoint, byteLength). Tolerant of invalid bytes (returns U+FFFD). */
private fun decodeUtf8At(
    buf: ByteArray,
    pos: Int,
    endExclusive: Int,
    firstByte: Int,
): Pair<Int, Int> = when {
    firstByte and 0x80 == 0 -> firstByte to 1
    firstByte and 0xE0 == 0xC0 -> {
        if (pos + 1 >= endExclusive) 0xFFFD to 1
        else {
            val b1 = buf[pos + 1].toInt() and 0x3F
            (((firstByte and 0x1F) shl 6) or b1) to 2
        }
    }
    firstByte and 0xF0 == 0xE0 -> {
        if (pos + 2 >= endExclusive) 0xFFFD to 1
        else {
            val b1 = buf[pos + 1].toInt() and 0x3F
            val b2 = buf[pos + 2].toInt() and 0x3F
            (((firstByte and 0x0F) shl 12) or (b1 shl 6) or b2) to 3
        }
    }
    firstByte and 0xF8 == 0xF0 -> {
        if (pos + 3 >= endExclusive) 0xFFFD to 1
        else {
            val b1 = buf[pos + 1].toInt() and 0x3F
            val b2 = buf[pos + 2].toInt() and 0x3F
            val b3 = buf[pos + 3].toInt() and 0x3F
            (((firstByte and 0x07) shl 18) or (b1 shl 12) or (b2 shl 6) or b3) to 4
        }
    }
    else -> 0xFFFD to 1
}
