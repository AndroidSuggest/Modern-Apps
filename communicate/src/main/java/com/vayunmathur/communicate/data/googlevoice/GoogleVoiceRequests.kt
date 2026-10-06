package com.vayunmathur.communicate.data.googlevoice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray

/**
 * Request body builders for [GoogleVoiceParser] (split for file length).
 * Extension functions on [GoogleVoiceParser]; behavior identical, call sites unchanged.
 */

/** `api2thread/list`: `[folder,pageSize,window,null,null,[null,1,1,1]]`. */
internal fun GoogleVoiceParser.buildListBody(folder: GvFolder, pageSize: Int = 20, window: Int = 15): String =
    buildJsonArray {
        add(JsonPrimitive(folder.id))
        add(JsonPrimitive(pageSize))
        add(JsonPrimitive(window))
        add(JsonNull)
        add(JsonNull)
        addJsonArray {
            add(JsonNull); add(JsonPrimitive(1)); add(JsonPrimitive(1)); add(JsonPrimitive(1))
        }
    }.toString()

/** `api2thread/search`: `["<query>",200,null,null,null,[null,1,1,1]]`. */
internal fun GoogleVoiceParser.buildSearchBody(query: String, limit: Int = 200): String =
    buildJsonArray {
        add(JsonPrimitive(query))
        add(JsonPrimitive(limit))
        add(JsonNull)
        add(JsonNull)
        add(JsonNull)
        addJsonArray {
            add(JsonNull); add(JsonPrimitive(1)); add(JsonPrimitive(1)); add(JsonPrimitive(1))
        }
    }.toString()

/** `account/get`: `[null,{}]`. */
internal fun GoogleVoiceParser.buildAccountBody(): String =
    buildJsonArray {
        add(JsonNull)
        add(JsonObject(emptyMap()))
    }.toString()

/**
 * `api2thread/sendsms`, real positional shape recovered from the HAR:
 * `[null,null,null,null, <text>, <threadId|null>, <[recipient]|null>, null, [<clientTxnId>], <media|null>,
 * ["!<botToken>"...]]`
 *
 * ⚠️ The trailing `"!…"` entry is a Google bot-defense (WAA/botguard) token minted by the
 * site's obfuscated JS; it cannot be produced natively, and the server rejects sends without
 * it (HTTP 400 INVALID_ARGUMENT). We build the correct prefix; [botToken] must be supplied by
 * a WebView that ran Google's JS for the send to actually succeed.
 */
internal fun GoogleVoiceParser.buildSendSmsBody(
    recipient: String,
    text: String,
    threadRemoteId: String?,
    clientTxnId: Long = kotlin.random.Random.nextLong(1, Long.MAX_VALUE),
    botToken: String? = null,
): String = buildJsonArray {
    add(JsonNull); add(JsonNull); add(JsonNull); add(JsonNull)
    add(JsonPrimitive(text))
    add(threadRemoteId?.let { JsonPrimitive(it) } ?: JsonNull)
    if (threadRemoteId == null) {
        addJsonArray { add(JsonPrimitive(recipient)) }
    } else {
        add(JsonNull)
    }
    add(JsonNull)
    addJsonArray { add(JsonPrimitive(clientTxnId)) }
    add(JsonNull)
    if (botToken != null) {
        addJsonArray { add(JsonPrimitive(botToken)) }
    }
}.toString()

/** Attribute mutations funnelled through `thread/batchupdateattributes`. */
internal fun GoogleVoiceParser.buildBatchUpdateBody(
    remoteId: String,
    action: GoogleVoiceParser.ThreadAction,
): String {
    val id = quote(remoteId)
    return when (action) {
        GoogleVoiceParser.ThreadAction.MarkRead -> "[[[[$id,null,null,1],[null,null,null,1],1]]]"
        GoogleVoiceParser.ThreadAction.MarkUnread -> "[[[[$id,null,null,0],[null,null,null,1],1]]]"
        GoogleVoiceParser.ThreadAction.Archive -> "[[[[$id,null,1],[null,null,1],1]]]"
        GoogleVoiceParser.ThreadAction.Unarchive -> "[[[[$id,null,0],[null,null,1],1]]]"
        else -> "[[[[$id,null,null,1],[null,null,null,1],1]]]"
    }
}

internal fun GoogleVoiceParser.quote(s: String): String = JsonPrimitive(s).toString()
