package com.vayunmathur.communicate.data.whatsapp.mex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure (JVM-only, kotlinx.serialization) builders for the two MEX/GraphQL wire envelopes. Kept free
 * of any Android dependency so the exact wire shapes can be asserted in JVM unit tests.
 *
 *  - [buildQueryJson] — the `<query>` text node payload for MEX-over-XMPP (w2.md §5.2):
 *      `{"queryId":"<docId>","variables":{…}}` (queryId is a STRING).
 *  - [buildWwwBody] — the WWW GraphQL HTTP body (w2.md §4.1 / §5.3):
 *      `{"access_token":…,"doc_id":<int64>,"lang":…,"Content-Type":"application/json","variables":{…}}`
 *      (doc_id is a NUMBER; `Content-Type` is intentionally replicated as a JSON key, not only a
 *      header).
 *
 * [variablesJson] is a raw JSON object string (e.g. `{"input":{…}}`). Blank/empty is normalized to
 * `{}` so an argument-less operation still sends `variables:{}`.
 */
object MexEnvelope {

    private val json = Json { encodeDefaults = true }

    private fun parseVariables(variablesJson: String): JsonElement {
        val trimmed = variablesJson.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return try {
            json.parseToJsonElement(trimmed)
        } catch (t: Throwable) {
            JsonObject(emptyMap())
        }
    }

    /** MEX-over-XMPP `<query>` payload: `{"queryId":"<docId>","variables":{…}}`. */
    fun buildQueryJson(docId: String, variablesJson: String): String {
        val obj = buildJsonObject {
            put("queryId", docId)
            put("variables", parseVariables(variablesJson))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * WWW GraphQL HTTP body. [docId] is emitted as a JSON number (int64) when it parses as a Long,
     * else as a string (defensive — the server expects a number).
     */
    fun buildWwwBody(
        accessToken: String,
        docId: String,
        variablesJson: String,
        lang: String = "en_US",
    ): String {
        val obj = buildJsonObject {
            put("access_token", accessToken)
            val asLong = docId.toLongOrNull()
            if (asLong != null) put("doc_id", asLong) else put("doc_id", docId)
            put("lang", lang)
            put("Content-Type", "application/json")
            put("variables", parseVariables(variablesJson))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
