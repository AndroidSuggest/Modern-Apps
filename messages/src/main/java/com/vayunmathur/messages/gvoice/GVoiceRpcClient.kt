package com.vayunmathur.messages.gvoice

import android.util.Base64
import android.util.Log
import com.google.protobuf.Message
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.library.network.SimpleResponse
import com.vayunmathur.library.network.Urls
import com.vayunmathur.library.network.asNetworkDataStream
import com.vayunmathur.messages.gmessages.PbLite
import java.security.MessageDigest

/**
 * HTTP transport for the Google Voice protocol.
 *
 * Mirrors `pkg/libgv/request.go`:
 *  - All RPC bodies are pblite-encoded JSON by default; binary protobuf
 *    when explicitly requested.
 *  - Per-host header bundles (`Sec-Fetch-Site`, `X-Client-Version`,
 *    `X-ClientDetails`, `X-Goog-Api-Key`, etc) vary by destination
 *    domain — see [buildHeaders].
 *  - Cookies are sent on every request; the `SAPISID` cookie also
 *    materializes as an `Authorization: SAPISIDHASH …` header (same
 *    algorithm libgm uses).
 *  - Retries network errors and 5xx up to [MAX_RETRIES] with linear
 *    backoff.
 */
class GVoiceRpcClient(
    /** Mutable cookie jar; updated atomically by the session manager
     *  when Set-Cookie headers come back. */
    @Volatile private var cookies: Map<String, String>,
) {
    var onCookiesChanged: ((Map<String, String>) -> Unit)? = null

    fun updateCookies(newCookies: Map<String, String>) {
        cookies = newCookies
    }

    fun close() = Unit

    /** POST [body] as pblite. Returns the decoded response of type [T]. */
    suspend fun <T : Message> postPbLite(
        url: String,
        body: Message,
        responseTemplate: T,
    ): T {
        val resp = postRetrying(url, PbLite.encode(body).toByteArray(Charsets.UTF_8), pbLite = true)
        return decodeResponse(resp, responseTemplate)
    }

    /**
     * POST a raw JSON literal as a pblite body. Used by the BrowserChannel
     * subscribe path which sends a hardcoded magic JSON literal that has
     * no protobuf representation in the bridge either.
     */
    suspend fun <T : Message> postRawPbLite(
        url: String,
        jsonBody: String,
        responseTemplate: T,
    ): T {
        val resp = postRetrying(url, jsonBody.toByteArray(Charsets.UTF_8), pbLite = true)
        return decodeResponse(resp, responseTemplate)
    }

    /** POST raw bytes as protobuf binary. */
    suspend fun <T : Message> postBinary(
        url: String,
        body: Message,
        responseTemplate: T,
    ): T {
        val resp = postRetrying(url, body.toByteArray(), pbLite = false)
        return decodeResponse(resp, responseTemplate)
    }

    /** POST a form-encoded body. Returns the raw response so the caller
     *  can inspect headers / body shape (BrowserChannel needs this). */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap(),
    ): RawResponse {
        val body = form.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        return postRetrying(
            url,
            body.toByteArray(Charsets.UTF_8),
            contentType = "application/x-www-form-urlencoded",
            pbLite = false,
            extraHeaders = extraHeaders,
        )
    }

    /**
     * GET a streaming response — used by the BrowserChannel long-poll.
     * [onResponse] gets the response head plus the still-open body stream;
     * the connection stays open for the duration of the callback.
     *
     * `Accept-Encoding: identity` keeps the utf16 chunk framing readable as
     * it arrives instead of wrapped in a compressed envelope.
     */
    suspend fun <T> getStreaming(
        url: String,
        extraQuery: Map<String, String> = emptyMap(),
        onResponse: suspend (SimpleResponse, NetworkDataStream?) -> T,
    ): T {
        val finalUrl = buildUrl(url, extraQuery)
        var result: T? = null
        NetworkClient.stream(
            url = finalUrl,
            method = "GET",
            headers = buildHeaders(finalUrl, accept = "*/*") +
                mapOf("Accept-Encoding" to "identity"),
            connectTimeoutMs = 15_000L,
            readTimeoutMs = 6 * 60 * 1000L,
        ) { stream, response ->
            refreshCookies(response.headers, response.url)
            result = onResponse(response, stream)
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * GET a raw resource (e.g. media attachment). Returns the raw bytes
     * and the Content-Type header value.
     */
    suspend fun getRaw(
        url: String,
        extraQuery: Map<String, String> = emptyMap(),
    ): Pair<ByteArray, String> {
        val finalUrl = buildUrl(url, extraQuery)
        val resp = NetworkClient.execute(
            url = finalUrl,
            method = "GET",
            headers = buildHeaders(finalUrl, accept = "*/*"),
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        if (!resp.isSuccess) {
            error("HTTP ${resp.status} on GET $url")
        }
        refreshCookies(resp.headers, resp.url)
        val mime = resp.header("Content-Type")?.substringBefore(';')?.trim() ?: "application/octet-stream"
        return resp.bytes to mime
    }

    // ----------------------------------------------------------------
    // POST core + retry
    // ----------------------------------------------------------------

    private suspend fun postRetrying(
        url: String,
        body: ByteArray,
        pbLite: Boolean = true,
        contentType: String? =
            if (pbLite) "application/json+protobuf" else "application/x-protobuf",
        extraHeaders: Map<String, String> = emptyMap(),
    ): RawResponse {
        val finalUrl = buildUrl(url, emptyMap())
        val headers = buildHeaders(finalUrl, accept = "*/*") +
            (if (contentType != null) mapOf("Content-Type" to contentType) else emptyMap()) +
            extraHeaders
        var attempt = 0
        while (true) {
            val resp = try {
                NetworkClient.execute(
                    url = finalUrl,
                    method = "POST",
                    headers = headers,
                    body = body,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = READ_TIMEOUT_MS,
                )
            } catch (t: Throwable) {
                if (attempt > MAX_RETRIES) throw t
                attempt++
                Log.w(TAG, "POST $url network error attempt=$attempt: ${t.message}")
                kotlinx.coroutines.delay((attempt * 2_000L))
                continue
            }
            // Retry only on 5xx; 4xx is the caller's problem.
            if (resp.status in 500..599 && attempt <= MAX_RETRIES) {
                attempt++
                Log.w(TAG, "POST $url ${resp.status} attempt=$attempt")
                kotlinx.coroutines.delay((attempt * 2_000L))
                continue
            }
            refreshCookies(resp.headers, resp.url)
            return resp
        }
    }

    /**
     * Build a URL, appending the standard `key=...` (+ optional `alt=proto`)
     * query parameters that libgv adds for the API + Contacts domains.
     */
    private fun buildUrl(url: String, extraQuery: Map<String, String>): String {
        val host = Urls.host(url)
        val params = LinkedHashMap<String, String>()
        if (host.endsWith(VoiceEndpoints.ApiDomain) && host != VoiceEndpoints.WaaDomain) {
            params["key"] = VoiceEndpoints.ApiKey
            if (host == VoiceEndpoints.ApiDomain || host == VoiceEndpoints.ContactsDomain) {
                params["alt"] = "proto"
            }
        }
        params.putAll(extraQuery)
        return Urls.appendQuery(url, params)
    }

    /**
     * The per-host header bundle the bridge documents. Includes the cookie
     * jar + (when SAPISID is present) the SAPISIDHASH authorization.
     * Content-Type is NOT set here (matches Go's prepareHeaders); the caller
     * merges it in.
     */
    private fun buildHeaders(url: String, accept: String): Map<String, String> = buildMap {
        val host = Urls.host(url)
        put("Sec-Ch-Ua", VoiceEndpoints.SecChUa)
        put("Sec-Ch-Ua-Platform", VoiceEndpoints.SecChPlatform)
        put("Sec-Ch-Ua-Mobile", "?0")
        put("User-Agent", VoiceEndpoints.UserAgent)
        put("X-Goog-AuthUser", "0")
        if (host == VoiceEndpoints.ApiDomain) {
            put("X-Client-Version", VoiceEndpoints.ClientVersion)
            put("X-ClientDetails", VoiceEndpoints.ClientDetails)
            put("X-JavaScript-User-Agent", VoiceEndpoints.JavaScriptUserAgent)
            put("X-Requested-With", "XMLHttpRequest")
            put("X-Goog-Encode-Response-If-Executable", "base64")
        }
        if (host == VoiceEndpoints.ContactsDomain) {
            put("X-Goog-Api-Key", VoiceEndpoints.ApiKey)
            put("X-Goog-Encode-Response-If-Executable", "base64")
        }
        if (host == VoiceEndpoints.WaaDomain) {
            put("X-Goog-Api-Key", VoiceEndpoints.WaaApiKey)
            put("X-User-Agent", VoiceEndpoints.WaaXUserAgent)
        }
        put("Sec-Fetch-Dest", "empty")
        put("Sec-Fetch-Mode", "cors")
        put("Sec-Fetch-Site", if (host.endsWith(".${VoiceEndpoints.ApiDomain}")) "same-site" else "same-origin")
        put("Accept", accept)
        put("Accept-Language", "en-US,en;q=0.5")
        if (host == VoiceEndpoints.UploadDomain) {
            put("Origin", "https://${VoiceEndpoints.UploadDomain}")
            put("Referer", "https://${VoiceEndpoints.UploadDomain}/")
        } else {
            put("Origin", VoiceEndpoints.Origin)
            put("Referer", "${VoiceEndpoints.Origin}/")
        }
        // Cookies + SAPISIDHASH.
        val cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
        if (cookieHeader.isNotEmpty()) put("Cookie", cookieHeader)
        cookies["SAPISID"]?.let { sapisid ->
            put("Authorization", sapisidHash(VoiceEndpoints.Origin, sapisid))
        }
    }

    // ----------------------------------------------------------------
    // Cookie refresh from Set-Cookie response headers
    // ----------------------------------------------------------------

    private val ignoredCookieNames = setOf("__Secure-1PSIDCC", "__Secure-3PSIDCC", "SIDCC")

    private fun refreshCookies(headers: Map<String, List<String>>, url: String) {
        val setCookies = headers.entries
            .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value
        if (setCookies.isNullOrEmpty()) return

        val host = Urls.host(url)
        if (!host.endsWith(VoiceEndpoints.ApiDomain)) return

        val updated = cookies.toMutableMap()
        var significantChange = false

        for (header in setCookies) {
            val parts = header.split(';').map { it.trim() }
            val nameValue = parts.firstOrNull() ?: continue
            val eqIdx = nameValue.indexOf('=')
            if (eqIdx < 0) continue
            val name = nameValue.substring(0, eqIdx).trim()
            val value = nameValue.substring(eqIdx + 1).trim()

            val isExpired = parts.any { part ->
                val lower = part.lowercase()
                when {
                    lower.startsWith("max-age=") ->
                        (lower.removePrefix("max-age=").trim().toIntOrNull() ?: 1) <= 0
                    lower.startsWith("expires=") -> try {
                        val dateStr = part.substringAfter('=').trim()
                        val fmt = java.text.SimpleDateFormat(
                            "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US
                        )
                        val expiry = fmt.parse(dateStr)
                        expiry != null && expiry.before(java.util.Date())
                    } catch (_: Exception) { false }
                    else -> false
                }
            }
            if (isExpired) {
                if (updated.remove(name) != null) significantChange = true
                continue
            }
            if (updated[name] != value) {
                updated[name] = value
                if (name !in ignoredCookieNames) significantChange = true
            }
        }
        if (significantChange) {
            cookies = updated
            onCookiesChanged?.invoke(updated.toMap())
        }
    }

    // ----------------------------------------------------------------
    // Response decoding
    // ----------------------------------------------------------------

    private suspend fun <T : Message> decodeResponse(resp: RawResponse, template: T): T {
        if (!resp.isSuccess) {
            error("HTTP ${resp.status}: ${resp.text.take(500).ifEmpty { "<no body>" }}")
        }
        val plainMime = resp.header("Content-Type")?.substringBefore(';')?.trim().orEmpty().lowercase()
        val safetyMime = resp.header("X-Goog-Safety-Content-Type")
            ?.substringBefore(';')?.trim().orEmpty().lowercase()
        val realMime = safetyMime.ifEmpty { plainMime }

        // utf16-chunk framing on the safety mime. Voice uses this on
        // some endpoints to defeat naive XSSI-style attacks.
        val raw: ByteArray = if (realMime == "text/plain") {
            Utf16ChunkReader(resp.bytes.asNetworkDataStream()).readChunk()
                ?: error("empty utf16chunk body")
        } else {
            resp.bytes
        }

        @Suppress("UNCHECKED_CAST")
        return when (realMime) {
            "application/x-protobuf" -> {
                // Tolerate the base64-wrapped-protobuf variant Voice
                // sometimes serves (real MIME protobuf, declared MIME
                // text/plain because of the safety header).
                val bytes = if (plainMime == "text/plain") {
                    Base64.decode(raw, Base64.DEFAULT)
                } else raw
                template.parserForType.parseFrom(bytes) as T
            }
            "application/json+protobuf", "text/plain" -> {
                val builder = template.newBuilderForType()
                PbLite.decode<T>(String(raw, Charsets.UTF_8), builder)
            }
            else -> error("unknown response content-type: $realMime")
        }
    }

    companion object {
        private const val TAG = "GVoice/Rpc"
        private const val MAX_RETRIES = 10
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 120_000L

        /** Same algorithm as libgm's SAPISIDHASH. Reproduced inline to
         *  avoid a cross-module import. */
        internal fun sapisidHash(origin: String, sapisid: String): String {
            val ts = System.currentTimeMillis() / 1000L
            val toHash = "$ts $sapisid $origin"
            val md = MessageDigest.getInstance("SHA-1")
            val hash = md.digest(toHash.toByteArray(Charsets.UTF_8))
            val hex = hash.joinToString("") { "%02x".format(it) }
            return "SAPISIDHASH ${ts}_$hex"
        }
    }
}
