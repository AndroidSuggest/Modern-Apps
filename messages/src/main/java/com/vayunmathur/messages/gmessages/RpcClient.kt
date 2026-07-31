package com.vayunmathur.messages.gmessages

import android.util.Log
import com.google.protobuf.Message
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.library.network.SimpleResponse

/**
 * HTTP transport for the Google-Messages-for-Web RPC protocol.
 *
 * Mirrors `pkg/libgm/http.go`:
 *   - All requests are POSTs to the RPC URLs (`$rpc/.../<Method>`).
 *   - Bodies are either protobuf binary (`application/x-protobuf`) or
 *     pblite JSON-array (`application/json+protobuf`).
 *   - Every request carries the relay headers from
 *     [Endpoints] + `util/func.go.BuildRelayHeaders` (Sec-CH-UA,
 *     User-Agent, X-Goog-API-Key, etc).
 *
 * Long-poll requests use a much longer read timeout than normal RPC, so
 * the two paths pass different per-request timeouts.
 */
class RpcClient {

    fun close() = Unit

    /**
     * POST [body] as binary protobuf and decode the response as the
     * template's message type. Uses the normal client.
     */
    suspend fun <T : Message> postProtobuf(
        url: String,
        body: Message,
        responseTemplate: T,
    ): T {
        val resp = post(url, body.toByteArray(), ContentTypes.Protobuf)
        return decodeBody(resp, responseTemplate)
    }

    /**
     * POST [body] as a pblite JSON array and decode the response as the
     * template's message type. Used for RegisterRefresh.
     */
    suspend fun <T : Message> postPbLiteDecoded(
        url: String,
        body: Message,
        responseTemplate: T,
    ): T {
        val resp = postPbLite(url, body)
        return decodeBody(resp, responseTemplate)
    }

    /**
     * POST [body] as a pblite JSON array. Used for SendMessage and Ack.
     * Most responses we care about are empty-ish OutgoingRPCResponse
     * acks (any failure is conveyed via HTTP status).
     */
    suspend fun postPbLite(
        url: String,
        body: Message,
    ): RawResponse {
        val json = PbLite.encode(body)
        return post(url, json.toByteArray(Charsets.UTF_8), ContentTypes.PbLite)
    }

    /**
     * Open a long-poll: POSTs the request body as pblite and invokes
     * [onResponse] with the response head plus the still-open body
     * stream. The body MUST be consumed incrementally — buffering it
     * would block until the relay closes the connection.
     *
     * The connection is held open for the duration of the callback,
     * then closed cleanly. Returns whatever [onResponse] returned.
     *
     * `Accept-Encoding: identity` keeps the relay from framing the push
     * stream inside a compressed envelope.
     */
    suspend fun <T> openLongPoll(
        url: String,
        body: Message,
        onResponse: suspend (SimpleResponse, NetworkDataStream?) -> T,
    ): T {
        val json = PbLite.encode(body)
        val bytes = json.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "POST $url (${bytes.size} bytes, ${ContentTypes.PbLite}) [long-poll]")
        var result: T? = null
        NetworkClient.stream(
            url = url,
            method = "POST",
            headers = relayHeaders(ContentTypes.PbLite, accept = "*/*") +
                mapOf("accept-encoding" to "identity"),
            body = bytes,
            connectTimeoutMs = 15_000L,
            // Heartbeats arrive about once a minute; anything under that
            // would tear down a healthy stream.
            readTimeoutMs = 6 * 60 * 1000L,
        ) { stream, response ->
            result = onResponse(response, stream)
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * GET [url] and decode the response as the template's message type. Used for the
     * messages-for-web /web/config endpoint (see [Endpoints.ConfigUrl]). Mirrors libgm
     * client.go fetchConfig: same-origin fetch headers, no x-user-agent / origin.
     */
    suspend fun <T : Message> getDecoded(
        url: String,
        responseTemplate: T,
        accept: String = "*/*",
    ): T {
        Log.d(TAG, "GET $url")
        val resp = NetworkClient.execute(
            url = url,
            method = "GET",
            headers = linkedMapOf(
                "sec-ch-ua" to Endpoints.SecUA,
                "x-goog-api-key" to Endpoints.GoogleApiKey,
                "sec-ch-ua-mobile" to Endpoints.SecUAMobile,
                "user-agent" to Endpoints.UserAgent,
                "sec-ch-ua-platform" to "\"${Endpoints.UAPlatform}\"",
                "accept" to accept,
                "sec-fetch-site" to "same-origin",
                "sec-fetch-mode" to "cors",
                "sec-fetch-dest" to "empty",
                "referer" to "https://messages.google.com/",
                "accept-language" to "en-US,en;q=0.9",
            ),
            connectTimeoutMs = 15_000L,
            readTimeoutMs = 30_000L,
        )
        return decodeBody(resp, responseTemplate)
    }

    private suspend fun post(
        url: String,
        body: ByteArray,
        contentType: String,
    ): RawResponse {
        Log.d(TAG, "POST $url (${body.size} bytes, $contentType)")
        return NetworkClient.execute(
            url = url,
            method = "POST",
            headers = relayHeaders(contentType, accept = "*/*"),
            body = body,
            connectTimeoutMs = 15_000L,
            readTimeoutMs = 120_000L,
        )
    }

    /**
     * Port of util.BuildRelayHeaders. Each `Sec-Fetch-*` header is what
     * real Chrome sends for the cross-site fetch; Google's anti-abuse
     * layer reads these to distinguish browser traffic from random
     * clients, so the set and its order are kept verbatim.
     */
    private fun relayHeaders(contentType: String, accept: String): Map<String, String> =
        linkedMapOf(
            "sec-ch-ua" to Endpoints.SecUA,
            "x-user-agent" to Endpoints.XUserAgent,
            "x-goog-api-key" to Endpoints.GoogleApiKey,
            "sec-ch-ua-mobile" to Endpoints.SecUAMobile,
            "user-agent" to Endpoints.UserAgent,
            "sec-ch-ua-platform" to "\"${Endpoints.UAPlatform}\"",
            "accept" to accept,
            "origin" to "https://messages.google.com",
            "sec-fetch-site" to "cross-site",
            "sec-fetch-mode" to "cors",
            "sec-fetch-dest" to "empty",
            "referer" to "https://messages.google.com/",
            "accept-language" to "en-US,en;q=0.9",
            "content-type" to contentType,
        )

    private fun <T : Message> decodeBody(resp: RawResponse, template: T): T {
        require(resp.isSuccess) { "HTTP ${resp.status} ${resp.statusMessage}" }
        val ct = resp.header("Content-Type").orEmpty().lowercase()
        val bytes = resp.bytes
        @Suppress("UNCHECKED_CAST")
        return when {
            ct.contains("x-protobuf") -> template.parserForType.parseFrom(bytes) as T
            ct.contains("json") || ct.startsWith("text/plain") -> {
                val builder = template.newBuilderForType()
                PbLite.decode<T>(String(bytes, Charsets.UTF_8), builder)
            }
            else -> error("unknown response content-type: $ct")
        }
    }

    companion object {
        private const val TAG = "GMessages/RpcClient"
    }
}
