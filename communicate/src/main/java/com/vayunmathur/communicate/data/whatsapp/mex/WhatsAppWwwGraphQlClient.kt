package com.vayunmathur.communicate.data.whatsapp.mex

import android.content.Context
import android.os.Build
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.library.network.NetworkClient

/**
 * WWW GraphQL HTTP transport — the fallback used when the XMPP/`w:mex` socket is not connected
 * (w2.md §4.1 / §5.3). Posts the persisted-query envelope to `graph.whatsapp.com/graphql` over the
 * repo's own HTTP stack ([NetworkClient], no OkHttp).
 *
 * The body is built JVM-pure by [MexEnvelope.buildWwwBody]:
 * `{"access_token":…,"doc_id":<int64>,"lang":"en_US","Content-Type":"application/json","variables":{…}}`.
 * Unauthenticated calls use the consumer client token from §4.1; there is no `Authorization` header
 * (auth lives in the body).
 *
 * Dev-only, reached via [WhatsAppMex]; gated at the repository layer by `WhatsAppFeature.enabled`.
 */
object WhatsAppWwwGraphQlClient {

    private const val TAG = "WAMex"
    private const val URL = "https://graph.whatsapp.com/graphql"

    /** Consumer/wearos/orbit/vr client token (w2.md §4.1 `GraphqlRequestBase.kt:618-636`). */
    private const val CLIENT_TOKEN = "WA|1015890928915437|3201f239340c1c8ec6262a6dad04200e"

    /**
     * POST the given [docId] + [variablesJson] to the WWW GraphQL endpoint and parse the
     * `{"data":…,"errors":…}` envelope into a [MexResult]. Transport failures (network, non-2xx)
     * surface as [MexResult.transport].
     */
    suspend fun post(context: Context, docId: String, variablesJson: String): MexResult {
        val body = MexEnvelope.buildWwwBody(CLIENT_TOKEN, docId, variablesJson)
        val headers = mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to userAgent(),
        )
        return try {
            val resp = NetworkClient.performRequest(
                url = URL,
                method = "POST",
                headers = headers,
                body = body,
                useSystemTrust = true,
            )
            if (!resp.isSuccess) {
                WhatsAppDiag.log(TAG, "www[$docId]: HTTP ${resp.status}: ${resp.body.take(200)}")
                return MexResult.transport("http:${resp.status}")
            }
            MexResult.fromEnvelope(resp.body)
        } catch (t: Throwable) {
            WhatsAppDiag.log(TAG, "www[$docId]: request failed: ${t.message}")
            MexResult.transport("network:${t.message}")
        }
    }

    /** WhatsApp/<ver> Android/<osrel> Device/<manufacturer>-<model> — same shape as registration. */
    private fun userAgent(): String {
        val device = "${Build.MANUFACTURER}-${Build.MODEL}".replace(' ', '_')
        return "WhatsApp/${WhatsAppProtocol.WA_VERSION_NAME} Android/${Build.VERSION.RELEASE} Device/$device"
    }
}
