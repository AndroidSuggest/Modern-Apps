package com.vayunmathur.messages.signal.web

import android.content.Context
import android.util.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketResponseMessage
import java.io.IOException
import javax.net.ssl.SSLSocketFactory

object SignalHttpClient {
    private const val TAG = "SignalHttp"

    const val API_HOST = "chat.signal.org"
    const val STORAGE_HOST = "storage.signal.org"
    const val CDN1_HOST = "cdn.signal.org"
    const val CDN2_HOST = "cdn2.signal.org"
    const val CDN3_HOST = "cdn3.signal.org"

    val CDN_HOSTS = listOf(CDN1_HOST, CDN1_HOST, CDN2_HOST, CDN3_HOST)

    // Must match the Signal bridge's User-Agent format. Signal-Server inspects this
    // during device linking and refuses to add a device that identifies as a mobile
    // primary platform (e.g. an "android" UA) as a *linked* device, returning a bare
    // 409 Conflict. The bridge uses "signalmeow/0.1.0 libsignal/<ver> go/<ver>".
    const val USER_AGENT = "signalmeow/0.1.0 libsignal/0.86.5"
    const val SIGNAL_AGENT = "MAU"

    const val CONTENT_TYPE_JSON = "application/json"
    const val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"
    const val CONTENT_TYPE_OCTET_STREAM = "application/octet-stream"
    const val CONTENT_TYPE_OFFSET_OCTET_STREAM = "application/offset+octet-stream"
    const val CONTENT_TYPE_MULTI_RECIPIENT_MESSAGE = "application/vnd.signal-messenger.mrm"

    /**
     * TLS trust pinned to the bundled Signal root (`signal-root.crt.der`).
     * Handed to `library:network` per request; also reused by [SignalWebSocket]
     * so the socket layer trusts exactly the same anchor.
     */
    @Volatile
    var sslSocketFactory: SSLSocketFactory? = null
        private set

    private var initialized = false
    private var httpReqCounter = 0

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val (factory, _) = CertPinning.createSslSocketFactory(context)
        sslSocketFactory = factory
        initialized = true
    }

    /**
     * Fail loudly rather than fall back to the platform trust store. The old
     * `lateinit var client` threw on uninitialised access; a null factory here
     * would instead silently drop the pin, so keep the throw.
     */
    private fun requirePinnedFactory(): SSLSocketFactory =
        sslSocketFactory
            ?: throw IllegalStateException("SignalHttpClient.init(context) was never called")

    suspend fun request(
        host: String = API_HOST,
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String = CONTENT_TYPE_JSON,
        username: String? = null,
        password: String? = null,
        headers: Map<String, String> = emptyMap(),
        overrideUrl: String? = null,
    ): RawResponse {
        val normalizedPath = if (path.isNotEmpty() && !path.startsWith("/")) "/$path" else path
        val url = overrideUrl ?: "https://$host$normalizedPath"

        val requestHeaders = buildMap {
            put("Content-Type", if (contentType.isNotEmpty()) contentType else CONTENT_TYPE_JSON)
            put("Content-Length", (body?.size ?: 0).toString())
            put("User-Agent", USER_AGENT)
            put("X-Signal-Agent", SIGNAL_AGENT)
            putAll(headers)
            if (username != null && password != null) {
                put("Authorization", basicCredentials(username, password))
            }
        }

        httpReqCounter++
        val counter = httpReqCounter
        Log.d(TAG, "[$counter] $method $url")

        val startTime = System.currentTimeMillis()
        val response = NetworkClient.execute(
            url = url,
            method = method,
            headers = requestHeaders,
            body = body,
            sslSocketFactory = requirePinnedFactory(),
        )
        val dur = System.currentTimeMillis() - startTime
        Log.d(TAG, "[$counter] status=${response.status} duration=${dur}ms")
        return response
    }

    suspend fun getAttachment(path: String, cdnNumber: Int): RawResponse {
        val host = cdnHost(cdnNumber)
        val url = "https://$host$path"

        httpReqCounter++
        Log.d(TAG, "[$httpReqCounter] GET attachment $url")

        return NetworkClient.execute(
            url = url,
            method = "GET",
            headers = mapOf(
                "Content-Type" to CONTENT_TYPE_OCTET_STREAM,
                "User-Agent" to USER_AGENT,
            ),
            sslSocketFactory = requirePinnedFactory(),
        )
    }

    fun decodeHttpResponseBody(response: RawResponse): ByteArray {
        if (!response.isSuccess) {
            Log.d(TAG, "Unexpected status code: ${response.status}, body: ${response.text}")
            throw IOException("Unexpected status code: ${response.status} ${response.statusMessage}")
        }
        return response.bytes
    }

    fun decodeWsResponseBody(response: WebSocketResponseMessage?): ByteArray? {
        if (response == null) return null
        if (response.status < 200 || response.status >= 300) {
            Log.w(TAG, "Unexpected WS status=${response.status} message=${response.message} headers=${response.headersList} body=${response.body?.toByteArray()?.decodeToString()}")
            throw IOException("Unexpected response status ${response.status}")
        }
        return response.body?.toByteArray() ?: ByteArray(0)
    }

    /** Replaces okhttp3.Credentials.basic — RFC 7617 basic auth. */
    fun basicCredentials(username: String, password: String): String {
        val raw = "$username:$password".toByteArray(Charsets.UTF_8)
        val encoded = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        return "Basic $encoded"
    }

    fun cdnHost(cdnNumber: Int): String {
        return if (cdnNumber == 0) {
            CDN_HOSTS[0]
        } else if (cdnNumber > 0 && cdnNumber < CDN_HOSTS.size) {
            CDN_HOSTS[cdnNumber]
        } else {
            Log.w(TAG, "Invalid CDN index $cdnNumber")
            CDN_HOSTS[0]
        }
    }
}
