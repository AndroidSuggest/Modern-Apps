package com.vayunmathur.taxi.network.lyft

import android.content.Context
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Shared session state for the Lyft API clients: token storage, the JSON codec, the system TLS
 * factory for external payment processors, auth headers, and error formatting.
 */
internal class LyftApiSession(context: Context) {
    val tokens = LyftTokenStore(context.applicationContext)
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * TLS factory over the platform (system) CA store, for the external payment processors
     * (Stripe/Braintree). The shared [com.vayunmathur.library.network.NetworkClient] is
     * initialised with a reduced trust bundle that only covers Lyft's own hosts, and its
     * `useSystemTrust` flag falls back to that bundle rather than to system trust — so passing
     * an explicit system factory is the only way to reach public processor endpoints without
     * touching the shared network library.
     */
    val systemTrustFactory: SSLSocketFactory by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?) // null keystore => platform default CAs
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }

    suspend fun accessToken(): String? {
        if (!tokens.isExpired()) return tokens.accessToken()
        val refresh = tokens.refreshToken() ?: return null
        val fresh = LyftAuth.refresh(refresh) ?: return null
        tokens.save(fresh)
        return fresh.accessToken
    }

    fun authHeaders(token: String): Map<String, String> =
        LyftAuth.commonHeaders() + mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/json, application/x-protobuf",
        )

    fun authJsonHeaders(token: String): Map<String, String> =
        authHeaders(token) + mapOf("Content-Type" to "application/json")

    /**
     * Lyft's server deserializes request bodies with a reflection-based proto-JSON codec that is
     * told which proto message to map the JSON onto via a `messageType` Content-Type parameter.
     * The APK builds it in `defpackage/tvu.d`:
     *   `application/json;messageType=<proto full name>`   (name from `fkh0.a()`, the DTO's
     *   super-constructor arg, e.g. `pb.api.endpoints.charge_accounts.Update…Request`).
     * Paths that map to a single message (e.g. `/v2/offerings`) tolerate a bare `application/json`,
     * but `/charge-accounts-multi-provider` is shared by Create (POST) and Update (PUT), so without
     * `messageType` the server can't resolve the body and returns 422. We must send it.
     */
    fun authProtoJsonHeaders(token: String, messageType: String): Map<String, String> =
        authHeaders(token) + mapOf("Content-Type" to "application/json;messageType=$messageType")

    fun httpError(resp: RawResponse): String =
        "Lyft returned HTTP ${resp.status}: ${resp.text.take(ERROR_BODY_PREVIEW_MAX)}"

    companion object {
        const val HTTP_NOT_FOUND = 404

        /** How much of an error body is surfaced — enough to identify it, never tokens. */
        const val ERROR_BODY_PREVIEW_MAX = 300
    }
}
