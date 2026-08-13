package com.vayunmathur.communicate.data.whatsapp

/**
 * Small self-contained equivalents of the messages-module types that the ported WhatsApp engine
 * referenced (`com.vayunmathur.messages.data.MessageSource`,
 * `com.vayunmathur.messages.data.MessageAttachment`,
 * `com.vayunmathur.messages.util.ContactSuggestion`). Duplicated here so `communicate` never
 * depends on the messages module (repo rule: messages must not be modified / depended upon).
 */

/** Backend that surfaced a conversation. WhatsApp-only in communicate, but kept as an enum so the
 *  ported event shapes match the originals verbatim. */
enum class MessageSource {
    WHATSAPP,
}

/** A media/share attachment carried on a WhatsApp message. Mirrors messages' MessageAttachment. */
data class MessageAttachment(
    val url: String? = null,
    val previewUrl: String? = null,
    val mimeType: String? = null,
    /** image | video | audio | sticker | file | share. */
    val attachmentType: String = "file",
    val fileName: String? = null,
    val title: String? = null,
    val actionUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

/** A contact search result. Mirrors messages' ContactSuggestion. */
data class ContactSuggestion(
    val displayName: String,
    val phoneE164: String?,
    val avatarUrl: String?,
    val source: MessageSource? = null,
    val username: String? = null,
)

/** Aggregated reaction on a message (emoji + count). */
@kotlinx.serialization.Serializable
data class Reaction(val emoji: String, val count: Int)
