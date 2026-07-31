package com.vayunmathur.appstore.data.play

import com.aurora.gplayapi.data.models.PlayResponse
import com.aurora.gplayapi.network.IHttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Implementation of gplayapi's IHttpClient using HttpURLConnection.
 * Mirrors Aurora Store's HttpClient.kt behavior but avoids OkHttp.
 *
 * Uses manual redirect handling (301-308, up to 5 hops) similar to
 * library:network HttpUrlEngine, and ensures POST with empty body does not
 * force an unwanted Content-Type.
 */
class PlayHttpClient : IHttpClient {

    companion object {
        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 30_000
        private const val MAX_REDIRECTS = 5
    }

    private val _responseCode = MutableStateFlow(0)
    override val responseCode: StateFlow<Int> get() = _responseCode.asStateFlow()

    override fun get(url: String, headers: Map<String, String>): PlayResponse {
        return execute(url, "GET", headers, null)
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val fullUrl = if (params.isNotEmpty()) {
            val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            if (url.contains("?")) "$url&$query" else "$url?$query"
        } else url
        return execute(fullUrl, "GET", headers, null)
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
        paramString: String
    ): PlayResponse {
        val fullUrl = if (paramString.isNotEmpty()) {
            if (paramString.startsWith("?")) "$url$paramString" else "$url?$paramString"
        } else url
        return execute(fullUrl, "GET", headers, null)
    }

    override fun getAuth(url: String): PlayResponse {
        return execute(url, "GET", emptyMap(), null)
    }

    override fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        // POST with query params encoded in URL (as Aurora does)
        val fullUrl = if (params.isNotEmpty()) {
            val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            if (url.contains("?")) "$url&$query" else "$url?$query"
        } else url
        return execute(fullUrl, "POST", headers, ByteArray(0), contentType = null)
    }

    override fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray
    ): PlayResponse {
        return execute(url, "POST", headers, body)
    }

    override fun postAuth(url: String, body: ByteArray): PlayResponse {
        return execute(url, "POST", emptyMap(), body, contentType = "application/json")
    }

    private fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        rawBody: ByteArray?,
        contentType: String? = null
    ): PlayResponse {
        return try {
            var currentUrl = url
            var currentMethod = method
            var currentBody: ByteArray? = rawBody
            var currentContentType: String? = contentType
            var redirects = 0
            var lastConn: HttpURLConnection? = null
            var result: PlayResponse? = null

            while (result == null) {
                val conn = openConnection(currentUrl, currentMethod, headers, currentBody, currentContentType)
                lastConn = conn
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    conn.disconnect()
                    throw e
                }

                // Manual redirect handling
                if (code in 301..308 && code != 304 && redirects < MAX_REDIRECTS) {
                    val loc = conn.getHeaderField("Location") ?: conn.getHeaderField("location")
                    if (loc != null) {
                        currentUrl = URL(URL(currentUrl), loc).toString()
                        if (code == 303) {
                            currentMethod = "GET"
                            currentBody = null
                            currentContentType = null
                        }
                        redirects++
                        try { conn.inputStream?.close() } catch (_: Exception) {}
                        conn.disconnect()
                        continue
                    }
                }

                val ct = conn.getHeaderField("Content-Type")
                val responseMessage = try { conn.responseMessage } catch (_: Exception) { "" } ?: ""
                _responseCode.value = code

                val bytes = try {
                    val stream = if (code >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
                    stream?.readBytes() ?: ByteArray(0)
                } catch (_: Exception) {
                    ByteArray(0)
                } finally {
                    try { lastConn?.inputStream?.close() } catch (_: Exception) {}
                    try { lastConn?.errorStream?.close() } catch (_: Exception) {}
                    lastConn?.disconnect()
                }

                val isSuccessful = code in 200..299
                val errStr = if (!isSuccessful) {
                    responseMessage.ifEmpty { "Error $code" }
                } else ""

                val errBytes = if (!isSuccessful) bytes else ByteArray(0)
                val respBytes = if (isSuccessful) bytes else ByteArray(0)

                result = PlayResponse(
                    responseBytes = respBytes,
                    errorBytes = errBytes,
                    errorString = errStr,
                    isSuccessful = isSuccessful,
                    code = code,
                    type = ct
                )
            }
            result!!
        } catch (e: Exception) {
            PlayResponse(
                isSuccessful = false,
                code = -1,
                errorString = e.message ?: "Network error",
                errorBytes = ByteArray(0),
                responseBytes = ByteArray(0)
            )
        }
    }

    private fun openConnection(
        urlString: String,
        method: String,
        headers: Map<String, String>,
        bodyBytes: ByteArray?,
        contentType: String?,
    ): HttpURLConnection {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            doOutput = bodyBytes != null
        }

        // Set method, with reflection fallback for custom verbs
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
        }

        headers.forEach { (k, v) ->
            conn.setRequestProperty(k, v)
        }

        // Content-Type handling: explicit param wins, otherwise default protobuf for POST
        val effectiveContentType = when {
            contentType != null -> contentType
            method == "POST" && bodyBytes != null -> "application/x-protobuf"
            else -> null
        }
        if (effectiveContentType != null) {
            conn.setRequestProperty("Content-Type", effectiveContentType)
        }

        if (bodyBytes != null) {
            try {
                conn.setFixedLengthStreamingMode(bodyBytes.size)
            } catch (_: Exception) {
                try { conn.setChunkedStreamingMode(0) } catch (_: Exception) {}
            }
            try {
                conn.outputStream.use { it.write(bodyBytes) }
            } catch (e: Exception) {
                // If output fails, propagate
                throw e
            }
        }

        return conn
    }
}
