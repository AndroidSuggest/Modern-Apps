package com.vayunmathur.travel.network

import com.vayunmathur.library.network.NetworkClient
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Base URL for the `/api/travel` endpoints on the self-hosted proxy. */
internal const val TRAVEL_BASE = "https://api.vayunmathur.com/api/travel"

/** Max characters of an upstream error body surfaced to the user. */
internal const val MAX_ERROR_SNIPPET_LENGTH = 300

internal val travelJson = Json { ignoreUnknownKeys = true }

internal fun travelEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** POST [body] to [url] and decode the JSON result, surfacing upstream errors. */
internal suspend inline fun <reified T> travelPostJson(url: String, body: Any, fallback: String): T {
    val res = NetworkClient.performRequest(
        url = url,
        method = "POST",
        headers = mapOf("Content-Type" to "application/json"),
        body = if (body is String && body.isEmpty()) null else body,
    )
    if (!res.isSuccess) {
        throw IllegalStateException(
            travelExtractError(res.body).ifBlank { "$fallback (HTTP ${res.status})." }
        )
    }
    return travelJson.decodeFromString(res.body)
}

/**
 * Pull a human-readable message out of an error body. Handles both a Duffel
 * `{ "errors": [{ "title", "message" }] }` payload and the proxy's plain
 * "… upstream <status>: <body>" text, falling back to the raw body.
 */
internal fun travelExtractError(body: String): String = runCatching {
    val start = body.indexOf('{')
    val jsonPart = if (start >= 0) body.substring(start) else body
    val errors = travelJson.parseToJsonElement(jsonPart).jsonObject["errors"]?.jsonArray
    val first = errors?.firstOrNull()?.jsonObject
    val title = first?.get("title")?.jsonPrimitive?.content
    val message = first?.get("message")?.jsonPrimitive?.content
    listOfNotNull(title, message).distinct().joinToString(": ").ifBlank { body.trim() }
}.getOrDefault(body.trim()).take(MAX_ERROR_SNIPPET_LENGTH)
