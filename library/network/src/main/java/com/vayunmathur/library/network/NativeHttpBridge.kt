package com.vayunmathur.library.network

import kotlinx.coroutines.runBlocking

/**
 * HTTP for the Rust crates, over [NetworkClient].
 *
 * Keeping the transport here means native code inherits the app's proxy, cookie and TLS
 * behaviour, and no crate has to link its own copy of rustls (which cost ~1.3 MB per `.so`).
 *
 * The shape is chosen for being called from Rust in a loop rather than for looking idiomatic in
 * Kotlin. Everything crosses as primitives and two `ByteArray`s, so Rust performs one JNI call
 * and no object graph walking:
 *
 *  - **One static method.** Rust caches the class and method id once, so there is no `FindClass`
 *    per request.
 *  - **No result object.** The reply is a single packed `ByteArray`. The previous bridge returned
 *    a `NativeHttpResponse` whose four fields Rust then read back individually — four extra JNI
 *    round trips per request.
 *  - **No exceptions.** A failure is status 0 with the message as the body, so Rust never has to
 *    check for a pending exception between calls.
 *
 * Wire format is defined in `library/jni-http/src/main/rust/src/frame.rs`; the two must change
 * together. All integers are big-endian.
 *
 * ```text
 * headers (in and out)  repeated: u16 nameLen, name, u16 valueLen, value
 *
 * reply                 u16 status          (0 = the request never completed)
 *                       u32 urlLen,   final URL after redirects
 *                       u32 hdrLen,   header block as above
 *                       u32 bodyLen,  body bytes
 * ```
 */
object NativeHttpBridge {

    private const val GET = 0
    private const val POST = 1
    private const val HEAD = 2

    /**
     * Called from Rust. Never throws.
     *
     * @param method one of [GET], [POST], [HEAD].
     * @param headers packed request headers, may be empty.
     * @param body request body, or null.
     * @return the packed reply frame.
     */
    @JvmStatic
    fun request(method: Int, url: String, headers: ByteArray?, body: ByteArray?): ByteArray =
        try {
            val response = runBlocking {
                NetworkClient.execute(
                    url = url,
                    method = when (method) {
                        POST -> "POST"
                        HEAD -> "HEAD"
                        else -> "GET"
                    },
                    headers = unpackHeaders(headers),
                    body = body,
                )
            }
            packReply(response.status, response.url, response.headers, response.bytes)
        } catch (e: Exception) {
            // Status 0 tells Rust the request never completed; the body carries the reason.
            packReply(
                status = 0,
                url = url,
                headers = emptyMap(),
                body = (e.message ?: e::class.simpleName ?: "request failed").toByteArray(),
            )
        }

    /** Packed pairs → the multimap [NetworkClient] expects. */
    private fun unpackHeaders(packed: ByteArray?): Map<String, List<String>> {
        if (packed == null || packed.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<String>>()
        var i = 0
        while (i + 2 <= packed.size) {
            val nameLen = readU16(packed, i); i += 2
            if (i + nameLen > packed.size) break
            val name = String(packed, i, nameLen, Charsets.UTF_8); i += nameLen
            if (i + 2 > packed.size) break
            val valueLen = readU16(packed, i); i += 2
            if (i + valueLen > packed.size) break
            val value = String(packed, i, valueLen, Charsets.UTF_8); i += valueLen
            out.getOrPut(name) { mutableListOf() }.add(value)
        }
        return out
    }

    private fun packReply(
        status: Int,
        url: String,
        headers: Map<String, List<String>>,
        body: ByteArray,
    ): ByteArray {
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        val headerBytes = packHeaders(headers)
        val out = java.io.ByteArrayOutputStream(14 + urlBytes.size + headerBytes.size + body.size)
        writeU16(out, status)
        writeU32(out, urlBytes.size); out.write(urlBytes)
        writeU32(out, headerBytes.size); out.write(headerBytes)
        writeU32(out, body.size); out.write(body)
        return out.toByteArray()
    }

    private fun packHeaders(headers: Map<String, List<String>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((name, values) in headers) {
            // HttpURLConnection uses a null key for the status line; Rust has no use for it.
            if (name.isEmpty()) continue
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > 0xFFFF) continue
            for (value in values) {
                val valueBytes = value.toByteArray(Charsets.UTF_8)
                if (valueBytes.size > 0xFFFF) continue
                writeU16(out, nameBytes.size); out.write(nameBytes)
                writeU16(out, valueBytes.size); out.write(valueBytes)
            }
        }
        return out.toByteArray()
    }

    private fun readU16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun writeU16(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeU32(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}
