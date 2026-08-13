package com.vayunmathur.communicate.telephony

/**
 * Minimal reader for inbound MMS PDUs: the WAP-push `M-Notification.ind` (to extract the
 * content-location we must download from) and the downloaded `M-Retrieve.conf` (headers +
 * multipart body → sender + text + media parts). Dependency-free; every entry point is defensive
 * and returns partial results rather than throwing. Best-effort / carrier-dependent.
 */
object MmsPduReader {
    // Field ids (with the 0x80 "well-known short field" bit set).
    private const val F_MESSAGE_TYPE = 0x8C
    private const val F_TRANSACTION_ID = 0x98
    private const val F_MMS_VERSION = 0x8D
    private const val F_FROM = 0x89
    private const val F_SUBJECT = 0x96
    private const val F_MESSAGE_CLASS = 0x8A
    private const val F_MESSAGE_SIZE = 0x8E
    private const val F_EXPIRY = 0x88
    private const val F_CONTENT_LOCATION = 0x83
    private const val F_CONTENT_TYPE = 0x84
    private const val F_DATE = 0x85
    private const val F_MESSAGE_ID = 0x8B
    private const val F_PRIORITY = 0x8F
    private const val F_DELIVERY_REPORT = 0x86
    private const val F_READ_REPORT = 0x90

    data class Notification(val contentLocation: String?, val transactionId: String?)

    data class RetrievedPart(val contentType: String, val data: ByteArray, val text: String?)
    data class Retrieved(
        val from: String?,
        val subject: String?,
        val text: String?,
        val parts: List<RetrievedPart>,
        val dateSeconds: Long,
    )

    /** Parse an M-Notification.ind to get the content-location URL to download. */
    fun parseNotification(pdu: ByteArray): Notification {
        val r = Reader(pdu)
        var contentLocation: String? = null
        var transactionId: String? = null
        while (r.hasRemaining()) {
            val field = r.readByte() and 0xFF
            when (field) {
                F_MESSAGE_TYPE, F_MMS_VERSION, F_MESSAGE_CLASS, F_PRIORITY,
                F_DELIVERY_REPORT, F_READ_REPORT -> r.readByte()
                F_TRANSACTION_ID -> transactionId = r.readTextString()
                F_MESSAGE_ID -> r.readTextString()
                F_SUBJECT -> r.readEncodedString()
                F_FROM -> r.skipValueLength()
                F_MESSAGE_SIZE, F_DATE -> r.skipLongInteger()
                F_EXPIRY -> r.skipValueLength()
                F_CONTENT_LOCATION -> contentLocation = r.readTextString()
                else -> {
                    // Unknown field: we can't know its length; stop parsing gracefully.
                    break
                }
            }
        }
        return Notification(contentLocation, transactionId)
    }

    /** Parse a downloaded M-Retrieve.conf into sender + text + media parts. */
    fun parseRetrieved(pdu: ByteArray): Retrieved {
        val r = Reader(pdu)
        var from: String? = null
        var subject: String? = null
        var dateSeconds = System.currentTimeMillis() / 1000L
        // Header loop — stops when we reach Content-Type (immediately followed by the body).
        loop@ while (r.hasRemaining()) {
            val field = r.readByte() and 0xFF
            when (field) {
                F_MESSAGE_TYPE, F_MMS_VERSION, F_MESSAGE_CLASS, F_PRIORITY,
                F_DELIVERY_REPORT, F_READ_REPORT -> r.readByte()
                F_TRANSACTION_ID, F_MESSAGE_ID -> r.readTextString()
                F_SUBJECT -> subject = r.readEncodedString()
                F_FROM -> from = r.readFromAddress()
                F_DATE -> dateSeconds = r.readLongInteger()
                F_MESSAGE_SIZE -> r.skipLongInteger()
                F_EXPIRY -> r.skipValueLength()
                F_CONTENT_LOCATION -> r.readTextString()
                F_CONTENT_TYPE -> { r.readContentType(); break@loop }
                else -> break@loop
            }
        }
        val parts = runCatching { r.readMultipart() }.getOrDefault(emptyList())
        val text = parts.firstOrNull { it.contentType == "text/plain" }?.text
        return Retrieved(from, subject, text, parts, dateSeconds)
    }

    private class Reader(val buf: ByteArray) {
        var pos = 0
        fun hasRemaining() = pos < buf.size
        fun readByte(): Int = buf[pos++].toInt()

        fun readTextString(): String {
            if (pos < buf.size && (buf[pos].toInt() and 0xFF) == 0x7F) pos++ // Quote
            val start = pos
            while (pos < buf.size && buf[pos].toInt() != 0) pos++
            val s = String(buf, start, pos - start, Charsets.UTF_8)
            if (pos < buf.size) pos++ // consume null
            return s
        }

        /** Encoded-string-value: either a plain text-string or Value-length Charset Text-string. */
        fun readEncodedString(): String {
            val first = buf[pos].toInt() and 0xFF
            if (first in 0x20..0x7F || first == 0x7F) return readTextString()
            val len = readValueLength()
            val end = pos + len
            // Optional charset short-integer.
            if (pos < end && (buf[pos].toInt() and 0x80) != 0) pos++
            val s = readTextString()
            pos = end.coerceAtMost(buf.size)
            return s
        }

        /** From-value: Value-length (Address-present-token Encoded-string | Insert-address-token). */
        fun readFromAddress(): String? {
            val len = readValueLength()
            val end = (pos + len).coerceAtMost(buf.size)
            if (pos < end) {
                val token = buf[pos++].toInt() and 0xFF
                if (token == 0x80) { // address-present
                    val s = readEncodedString()
                    pos = end
                    return s.substringBefore("/TYPE=")
                }
            }
            pos = end
            return null
        }

        fun readValueLength(): Int {
            val first = buf[pos++].toInt() and 0xFF
            return if (first < 31) first else readUintvar()
        }

        fun skipValueLength() {
            val len = readValueLength()
            pos = (pos + len).coerceAtMost(buf.size)
        }

        fun readLongInteger(): Long {
            val len = buf[pos++].toInt() and 0xFF
            if (len > 30) return 0 // not a short-length; bail
            var v = 0L
            repeat(len) { v = (v shl 8) or (buf[pos++].toLong() and 0xFF) }
            return v
        }

        fun skipLongInteger() { readLongInteger() }

        fun readUintvar(): Int {
            var result = 0
            while (pos < buf.size) {
                val b = buf[pos++].toInt() and 0xFF
                result = (result shl 7) or (b and 0x7F)
                if (b and 0x80 == 0) break
            }
            return result
        }

        /** Content-Type value: constrained-media (text/short-int) or general-form (length-prefixed). */
        fun readContentType(): String {
            val first = buf[pos].toInt() and 0xFF
            return when {
                first >= 0x80 -> { pos++; wellKnownContentType(first and 0x7F) } // short-integer
                first in 0x20..0x7E -> readTextString() // text media type
                else -> {
                    // General form: value-length, then media type (short-int or text), then params.
                    val len = readValueLength()
                    val end = (pos + len).coerceAtMost(buf.size)
                    val ct = if ((buf[pos].toInt() and 0x80) != 0) {
                        wellKnownContentType((buf[pos++].toInt() and 0x7F))
                    } else {
                        readTextString()
                    }
                    pos = end
                    ct
                }
            }
        }

        /** Multipart body: nEntries then each entry = HeadersLen DataLen ContentType *Header Data. */
        fun readMultipart(): List<RetrievedPart> {
            val out = ArrayList<RetrievedPart>()
            if (!hasRemaining()) return out
            val nEntries = readUintvar()
            repeat(nEntries) {
                if (!hasRemaining()) return out
                val headersLen = readUintvar()
                val dataLen = readUintvar()
                val headerEnd = pos + headersLen
                val ct = readContentType()
                // Skip the rest of the part headers (content-location/id/etc.).
                pos = headerEnd.coerceAtMost(buf.size)
                val dataEnd = (pos + dataLen).coerceAtMost(buf.size)
                val data = buf.copyOfRange(pos, dataEnd)
                pos = dataEnd
                val text = if (ct == "text/plain") String(data, Charsets.UTF_8) else null
                out.add(RetrievedPart(ct, data, text))
            }
            return out
        }
    }

    /** A tiny subset of WSP content-type assigned numbers we may encounter. */
    private fun wellKnownContentType(code: Int): String = when (code) {
        0x03 -> "text/plain"
        0x1D -> "image/gif"
        0x1E -> "image/jpeg"
        0x1F -> "image/tiff"
        0x20 -> "image/png"
        0x21 -> "application/vnd.wap.multipart.mixed"
        0x22 -> "application/vnd.wap.multipart.related"
        else -> "application/octet-stream"
    }
}
