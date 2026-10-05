package com.vayunmathur.communicate.data.googlevoice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Media/phone/tree helpers for [GoogleVoiceParser] (split for file length).
 * Behavior identical, call sites unchanged.
 */

internal fun JsonArray.normalizedBodyText(hasMedia: Boolean): String {
    val raw = strAt(GoogleVoiceParser.ITEM_BODY).orEmpty()
    return if (hasMedia && raw.trim().isMmsStatusLabel()) "" else raw
}

internal fun String.isMmsStatusLabel(): Boolean = when (trim().lowercase()) {
    "mms sent", "mms received" -> true
    else -> false
}

internal fun JsonArray.hasMediaMetadata(): Boolean =
    getOrNull(GoogleVoiceParser.ITEM_MEDIA_FLAG)?.let(::hasMediaMetadataIn) == true

internal fun hasMediaMetadataIn(el: JsonElement): Boolean = allStrings(el).any {
    it.looksLikeMimeType() || it.looksLikeAttachmentId()
}

internal fun String.looksLikeMimeType(): Boolean {
    val lower = trim().lowercase()
    return lower.startsWith("image/") || lower.startsWith("video/") || lower.startsWith("audio/")
}

internal fun String.looksLikeAttachmentId(): Boolean =
    Regex("^[A-Za-z0-9_.-]+-\\d+$").matches(trim())

internal fun JsonArray.mediaUrlsIn(): List<String> {
    val text = strAt(GoogleVoiceParser.ITEM_BODY).orEmpty()
    val knownNonMedia = buildSet {
        strAt(GoogleVoiceParser.ITEM_ID)?.let(::add)
        strAt(GoogleVoiceParser.ITEM_OWN)?.let(::add)
        text.takeIf { it.isNotBlank() }?.let(::add)
        strAt(GoogleVoiceParser.ITEM_COUNTERPARTY)?.let(::add)
    }
    return mediaUrlStrings(this)
        .filterNot { it in knownNonMedia }
        .distinct()
}

internal fun mediaUrlStrings(el: JsonElement): List<String> {
    val out = mutableListOf<String>()
    fun visit(e: JsonElement) {
        when (e) {
            is JsonArray -> e.forEach { visit(it) }
            is JsonObject -> e.values.forEach { visit(it) }
            is JsonPrimitive -> if (e.isString) {
                val url = e.content.trim()
                if (looksLikeMediaUrl(url)) out.add(url)
            }
        }
    }
    visit(el)
    return out
}

internal fun looksLikeMediaUrl(raw: String): Boolean {
    val lower = raw.lowercase()
    if (!lower.startsWith("https://")) return false
    val isGoogleAttachmentHost = lower.contains("googleusercontent.com") ||
        lower.contains("ggpht.com") ||
        lower.contains("lh3.google.com") ||
        lower.contains("voice.google.com/media")
    if (!isGoogleAttachmentHost) return false
    return lower.contains("=s") ||
        lower.contains("/mms") ||
        lower.contains("/media") ||
        lower.contains("image") ||
        lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".gif") ||
        lower.endsWith(".webp")
}

internal fun recordId(record: JsonArray): String? =
    (record.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonArray.strAt(i: Int): String? =
    (getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonArray.longAt(i: Int): Long? =
    (getOrNull(i) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

internal fun JsonArray.arrAt(i: Int): JsonArray? = getOrNull(i) as? JsonArray
