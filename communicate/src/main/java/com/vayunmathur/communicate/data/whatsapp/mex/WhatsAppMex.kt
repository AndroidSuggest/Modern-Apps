package com.vayunmathur.communicate.data.whatsapp.mex

import android.content.Context
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient

/**
 * MEX transport router (w2.md §5.1). Prefers the `w:mex` XMPP path over the live Noise socket and
 * falls back to the WWW GraphQL HTTP client when disconnected — the same MEX-preferred / WWW-fallback
 * split the official client uses (minus the Pando C++ path, which is out of scope).
 *
 * All calls resolve the same persisted-query [operationName] → doc_id; an uncaptured operation
 * returns a typed `no_persisted_id:<op>` [MexResult] on either path (never crashes).
 *
 * Dev-only: callers go through `CommunicateRepository`, which gates every entry point on
 * `WhatsAppFeature.enabled`.
 */
object WhatsAppMex {

    /**
     * Execute [operationName] with the given `variables` JSON. Uses XMPP when
     * [WhatsAppClient.isConnected]; otherwise resolves the doc_id and posts to WWW GraphQL.
     */
    suspend fun call(
        context: Context,
        operationName: String,
        variablesJson: String,
        type: String = "get",
    ): MexResult {
        return if (WhatsAppClient.isConnected()) {
            WhatsAppClient.mexCall(operationName, variablesJson, type)
        } else {
            val docId = MexPersistedQueryProvider.docIdFor(context, operationName)
                ?: return MexResult.transport("no_persisted_id:$operationName")
            WhatsAppWwwGraphQlClient.post(context, docId, variablesJson)
        }
    }
}
