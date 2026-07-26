package com.vayunmathur.library.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android-only HTTP client – HttpURLConnection only, no Ktor/OkHttp.
 *
 * Public API binary-compatible:
 *  SimpleResponse(status,statusMessage,body,headers,url){ isSuccess, contentLength }
 *  NetworkDataStream { suspend read(buffer), isClosedForRead }
 *  performRequestBytes / Full / performRequest / stream / getContentLength / performRequestInputStream
 *  callJson / getJson via kotlinx-serialization-json
 */
data class SimpleResponse(
    val status: Int,
    val statusMessage: String,
    val body: String,
    val headers: Map<String, List<String>>,
    val url: String,
) {
    val isSuccess: Boolean get() = status in 200..299
    val contentLength: Long? get() = headers.entries.firstOrNull {
        it.key.equals("Content-Length", ignoreCase = true)
    }?.value?.firstOrNull()?.toLongOrNull()
}

interface NetworkDataStream {
    suspend fun read(buffer: ByteArray): Int
    val isClosedForRead: Boolean
}

object NetworkClient {

    // Published API – accessible from public inline functions.
    @PublishedApi
    internal val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    // ------------------------------------------------------------------
    // Bytes / Full / SimpleResponse
    // ------------------------------------------------------------------

    suspend fun performRequestBytes(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
    ): Pair<Int, ByteArray> {
        val r = HttpUrlEngine.internalExecute(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, timeoutMs = null,
        )
        return r.status to r.bodyBytes
    }

    suspend fun performRequestBytesFull(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
    ): Triple<Int, Map<String, List<String>>, ByteArray> {
        val r = HttpUrlEngine.internalExecute(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, timeoutMs = null,
        )
        return Triple(r.status, r.headers, r.bodyBytes)
    }

    suspend fun performRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
    ): SimpleResponse {
        val r = HttpUrlEngine.internalExecute(
            url, method, headers, HttpUrlEngine.toBodyBytes(body),
            followRedirects = true, timeoutMs = null,
        )
        return SimpleResponse(r.status, r.statusMessage, r.bodyBytes.toString(Charsets.UTF_8), r.headers, r.finalUrl)
    }

    // ------------------------------------------------------------------
    // Streaming variants
    // ------------------------------------------------------------------

    suspend fun stream(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        block: suspend (stream: NetworkDataStream?, response: SimpleResponse) -> Unit,
    ): SimpleResponse {
        var currentUrl = url
        var currentMethod = method
        var redirects = 0
        var lastConn: HttpURLConnection? = null
        var finalStatus = 0
        var finalMessage = ""
        var finalHeaders: Map<String, List<String>> = emptyMap()
        var finalUrl = url

        withContext(Dispatchers.IO) {
            while (true) {
                val conn = HttpUrlEngine.openConnection(currentUrl, currentMethod, headers, null, null)
                lastConn = conn
                finalStatus = conn.responseCode
                finalMessage = conn.responseMessage ?: ""
                finalHeaders = HttpUrlEngine.extractHeaders(conn)
                finalUrl = conn.url.toString().let { if (it == currentUrl) currentUrl else it }

                if (finalStatus in 301..308 && finalStatus != 304 && redirects < HttpUrlEngine.MAX_REDIRECTS) {
                    val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                    if (loc != null) {
                        currentUrl = URL(URL(currentUrl), loc).toString()
                        if (finalStatus == 303) currentMethod = "GET"
                        redirects++
                        conn.disconnect()
                        continue
                    }
                }
                break
            }
        }

        val simple = SimpleResponse(finalStatus, finalMessage, "", finalHeaders, finalUrl)
        val conn = lastConn!!

        if (simple.isSuccess || simple.status == 206) {
            var raw: InputStream? = withContext(Dispatchers.IO) {
                var s: InputStream? = try {
                    if (finalStatus >= 400) conn.errorStream else conn.inputStream
                } catch (_: Exception) { null }
                HttpUrlEngine.maybeDecompress(conn, s)
            }

            if (raw != null) {
                var closed = false
                val streamObj = object : NetworkDataStream {
                    override val isClosedForRead: Boolean get() = closed
                    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) {
                        try {
                            val n = raw.read(buffer)
                            if (n == -1) closed = true
                            n
                        } catch (_: Exception) {
                            closed = true
                            -1
                        }
                    }
                }
                try {
                    block(streamObj, simple)
                } finally {
                    withContext(Dispatchers.IO) {
                        try { raw.close() } catch (_: Exception) {}
                        conn.disconnect()
                    }
                }
            } else {
                withContext(Dispatchers.IO) { conn.disconnect() }
                block(null, simple)
            }
        } else {
            withContext(Dispatchers.IO) { conn.disconnect() }
            block(null, simple)
        }
        return simple
    }

    /**
     * Used by youpipe SABR – returns code, headers, InputStream that disconnects on close.
     * Manual redirect handling; no forced Content-Type when body == null.
     */
    suspend fun performRequestInputStream(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
        timeoutMs: Long? = null,
    ): Triple<Int, Map<String, List<String>>, InputStream> {
        return withContext(Dispatchers.IO) {
            var currentUrl = url
            var currentMethod = method
            var currentBody = HttpUrlEngine.toBodyBytes(body)
            var redirects = 0
            var out: Triple<Int, Map<String, List<String>>, InputStream>? = null

            while (out == null) {
                val conn = HttpUrlEngine.openConnection(currentUrl, currentMethod, headers, currentBody, timeoutMs)
                val status = try {
                    conn.responseCode
                } catch (e: java.io.IOException) {
                    conn.disconnect()
                    throw e
                }
                val respHeaders = HttpUrlEngine.extractHeaders(conn)

                if (status in 301..308 && status != 304 && redirects < HttpUrlEngine.MAX_REDIRECTS) {
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

                stream = HttpUrlEngine.maybeDecompress(conn, stream)

                val finalStream: InputStream = stream?.let { s ->
                    object : InputStream() {
                        override fun read(): Int = s.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int = s.read(b, off, len)
                        override fun close() {
                            try { s.close() } catch (_: Exception) {}
                            conn.disconnect()
                        }
                    }
                } ?: ByteArrayInputStream(ByteArray(0)).also { conn.disconnect() }

                out = Triple(status, respHeaders, finalStream)
            }

            out!!
        }
    }

    suspend fun getContentLength(url: String, headers: Map<String, *> = emptyMap<String, Any>()): Long? {
        return withContext(Dispatchers.IO) {
            var currentUrl = url
            var redirects = 0
            var lenResult: Long? = null
            var done = false

            while (!done) {
                val conn = HttpUrlEngine.openConnection(currentUrl, "HEAD", headers, null, null)
                try {
                    val status = conn.responseCode
                    val len = conn.getHeaderField("Content-Length")?.toLongOrNull()
                        ?: conn.getHeaderField("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                    val respHeaders = HttpUrlEngine.extractHeaders(conn)

                    if (status in 301..308 && status != 304 && redirects < HttpUrlEngine.MAX_REDIRECTS) {
                        val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                        if (loc != null) {
                            currentUrl = URL(URL(currentUrl), loc).toString()
                            redirects++
                            conn.disconnect()
                            continue
                        }
                    }
                    conn.disconnect()
                    lenResult = len ?: respHeaders.entries.firstOrNull {
                        it.key.equals("Content-Length", ignoreCase = true)
                    }?.value?.firstOrNull()?.toLongOrNull()
                    done = true
                } catch (_: Exception) {
                    conn.disconnect()
                    lenResult = null
                    done = true
                }
            }

            lenResult
        }
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    suspend inline fun <reified T> callJson(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null,
    ): T {
        val simple = performRequest(url, method, headers, body)

        if (simple.status == 204 || simple.body.isEmpty() || simple.contentLength == 0L) {
            @Suppress("UNCHECKED_CAST")
            when (T::class) {
                Boolean::class -> return (simple.status in 200..299) as T
                Unit::class -> return Unit as T
            }
        }

        if (!simple.isSuccess) {
            throw java.io.IOException("HTTP ${simple.status}: ${simple.body.take(500)}")
        }

        return if (simple.body.isBlank()) {
            @Suppress("UNCHECKED_CAST")
            when (T::class) {
                Boolean::class -> (true as T)
                Unit::class -> (Unit as T)
                else -> jsonConfig.decodeFromString(simple.body.ifBlank { "null" })
            }
        } else {
            jsonConfig.decodeFromString(simple.body)
        }
    }

    suspend inline fun <reified T> getJson(
        url: String,
        headers: Map<String, *> = emptyMap<String, Any>(),
    ): T = callJson(url, "GET", headers)
}
