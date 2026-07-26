package com.vayunmathur.library.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Android-only HTTP engine backed by HttpURLConnection.
 *
 * - withContext(IO) opens (URL(url).openConnection() as HttpURLConnection)
 * - connectTimeout 30000, readTimeout 60000
 * - custom verbs via reflection getDeclaredField("method")
 * - headers Map<String, *> where Iterable expands to multiple header lines
 * - body String/ByteArray with setFixedLengthStreamingMode, chunked fallback
 * - when body == null -> doOutput=false, no Content-Type forced (critical for SABR)
 * - manual redirect 301/302/303/307/308 up to 5 hops
 * - Content-Encoding br via org.brotli.dec.BrotliInputStream, gzip via GZIPInputStream
 */
internal object HttpUrlEngine {

    const val CONNECT_TIMEOUT = 30_000
    const val READ_TIMEOUT = 60_000
    const val MAX_REDIRECTS = 5

    data class InternalResult(
        val status: Int,
        val statusMessage: String,
        val headers: Map<String, List<String>>,
        val bodyBytes: ByteArray,
        val finalUrl: String,
    )

    fun openConnection(
        urlString: String,
        method: String,
        headers: Map<String, *>,
        bodyBytes: ByteArray?,
        timeoutMs: Long?,
    ): HttpURLConnection {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs?.toInt() ?: CONNECT_TIMEOUT
            readTimeout = timeoutMs?.toInt() ?: READ_TIMEOUT
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            doOutput = bodyBytes != null
        }

        try {
            conn.requestMethod = method
        } catch (_: java.net.ProtocolException) {
            var clazz: Class<*>? = conn.javaClass
            var success = false
            while (clazz != null && !success) {
                try {
                    val f = clazz.getDeclaredField("method")
                    f.isAccessible = true
                    f.set(conn, method)
                    success = true
                } catch (_: Exception) {
                    clazz = clazz.superclass
                }
            }
            if (!success) {
                try {
                    val delegateField = conn.javaClass.getDeclaredField("delegate")
                    delegateField.isAccessible = true
                    val delegate = delegateField.get(conn)
                    val mf = delegate.javaClass.getDeclaredField("method")
                    mf.isAccessible = true
                    mf.set(delegate, method)
                } catch (e: Exception) {
                    throw java.net.ProtocolException("Cannot set custom method $method: ${e.message}")
                }
            }
        }

        headers.forEach { (k, v) ->
            when (v) {
                is Iterable<*> -> v.forEach { elem ->
                    if (elem != null) conn.addRequestProperty(k, elem.toString())
                }
                else -> if (v != null) conn.setRequestProperty(k, v.toString())
            }
        }

        if (bodyBytes != null) {
            try {
                conn.setFixedLengthStreamingMode(bodyBytes.size)
            } catch (_: Exception) {
                try { conn.setChunkedStreamingMode(0) } catch (_: Exception) {}
            }
            conn.outputStream.use { it.write(bodyBytes) }
        }

        return conn
    }

    fun extractHeaders(conn: HttpURLConnection): Map<String, List<String>> {
        return conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! }
    }

    fun maybeDecompress(conn: HttpURLConnection, raw: InputStream?): InputStream? {
        if (raw == null) return null
        val encoding = conn.getHeaderField("Content-Encoding")
            ?: conn.getHeaderField("content-encoding")
            ?: return raw
        val lower = encoding.lowercase()
        return when {
            lower.contains("br") -> try { org.brotli.dec.BrotliInputStream(raw) } catch (_: Throwable) { raw }
            lower.contains("gzip") -> try { GZIPInputStream(raw) } catch (_: Exception) { raw }
            else -> raw
        }
    }

    fun toBodyBytes(body: Any?): ByteArray? = when (body) {
        null -> null
        is ByteArray -> body
        is String -> body.toByteArray(Charsets.UTF_8)
        else -> body.toString().toByteArray(Charsets.UTF_8)
    }

    suspend fun internalExecute(
        url: String,
        method: String,
        headers: Map<String, *>,
        bodyBytes: ByteArray?,
        followRedirects: Boolean,
        timeoutMs: Long?,
    ): InternalResult = withContext(Dispatchers.IO) {
        var currentUrl = url
        var currentMethod = method
        var currentBody = bodyBytes
        var redirects = 0
        var result: InternalResult? = null

        while (result == null) {
            val conn = openConnection(currentUrl, currentMethod, headers, currentBody, timeoutMs)
            val status = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                throw IOException("Failed to connect to $currentUrl: ${e.message}", e)
            }
            val msg = conn.responseMessage ?: ""
            val respHeaders = extractHeaders(conn)
            val finalUrl = conn.url.toString()

            if (followRedirects && status in 301..308 && status != 304 && redirects < MAX_REDIRECTS) {
                val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                if (loc != null) {
                    currentUrl = URL(URL(currentUrl), loc).toString()
                    if (status == 303) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    redirects++
                    try { conn.inputStream?.close() } catch (_: Exception) {}
                    conn.disconnect()
                    continue
                }
            }

            var stream: InputStream? = try {
                if (status >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
            } catch (_: Exception) { null }

            stream = maybeDecompress(conn, stream)
            val bytes = try { stream?.readBytes() ?: ByteArray(0) } catch (_: Exception) { ByteArray(0) }
            try { stream?.close() } catch (_: Exception) {}
            conn.disconnect()

            result = InternalResult(status, msg, respHeaders, bytes, finalUrl)
        }

        result!!
    }
}
