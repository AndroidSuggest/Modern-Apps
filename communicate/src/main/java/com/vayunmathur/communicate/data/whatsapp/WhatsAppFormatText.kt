package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto

/**
 * Message formatting (split from WhatsAppProtocolParsing.kt for file length).
 * Behavior identical, call sites unchanged.
 */

internal fun WhatsAppProtocol.formatDisappearingTimer(seconds: Int): String {
    return when {
        seconds >= SECONDS_90_DAYS -> "90 days"
        seconds >= SECONDS_PER_WEEK -> "7 days"
        seconds >= SECONDS_PER_DAY -> "${seconds / SECONDS_PER_DAY} days"
        seconds >= SECONDS_PER_HOUR -> "${seconds / SECONDS_PER_HOUR} hours"
        seconds >= SECONDS_PER_MINUTE -> "${seconds / SECONDS_PER_MINUTE} minutes"
        else -> "$seconds seconds"
    }
}

internal fun WhatsAppProtocol.extractContextInfo(
    e2eMessage: WhatsAppE2EProto.Message
): ContextInfoResult {
    val ctx = when {
        e2eMessage.hasExtendedTextMessage() -> e2eMessage.extendedTextMessage.contextInfo
        e2eMessage.hasImageMessage() -> e2eMessage.imageMessage.contextInfo
        e2eMessage.hasVideoMessage() -> e2eMessage.videoMessage.contextInfo
        e2eMessage.hasAudioMessage() -> e2eMessage.audioMessage.contextInfo
        e2eMessage.hasDocumentMessage() -> e2eMessage.documentMessage.contextInfo
        e2eMessage.hasStickerMessage() -> e2eMessage.stickerMessage.contextInfo
        e2eMessage.hasLocationMessage() -> e2eMessage.locationMessage.contextInfo
        e2eMessage.hasContactMessage() -> e2eMessage.contactMessage.contextInfo
        else -> null
    } ?: return ContextInfoResult()

    return ContextInfoResult(
        isForwarded = ctx.isForwarded,
        forwardingScore = ctx.forwardingScore,
        replyToId = ctx.stanzaId.ifEmpty { null },
        mentionedJids = ctx.mentionedJidList.orEmpty(),
    )
}

// WA text formatting regexes (from Go wa-text.go)
private val waBoldRegex = Regex("(?<=[\\s>_~]|^)\\*(.+?)\\*(?=[^a-zA-Z\\d]|$)")
private val waItalicRegex = Regex("(?<=[\\s>~*]|^)_(.+?)_(?=[^a-zA-Z\\d]|$)")
private val waStrikethroughRegex = Regex("(?<=[\\s>_*]|^)~(.+?)~(?=[^a-zA-Z\\d]|$)")
private val waInlineCodeRegex = Regex("(?<=[\\s>_*~]|^)`(.+?)`(?=[^a-zA-Z\\d]|$)")
private val waOrderedListRegex = Regex("(?m)^(\\d{1,2})\\. ")
private val waBulletedListRegex = Regex("(?m)^( *)\\* ")
private val waBlockquoteRegex = Regex("(?m)^> ")
private val waInlineURLRegex = Regex("\\[(.+?)]\\((.+?)\\)")

fun WhatsAppProtocol.convertWAFormattingToHtml(text: String): String {
    val sb = StringBuilder()
    var remaining = text
    while (true) {
        val start = remaining.indexOf("```")
        val end = if (start == -1) -1 else remaining.indexOf("```", start + FENCE_LENGTH)
        if (start == -1 || end == -1) break
        val before = remaining.substring(0, start)
        val code = remaining.substring(start + FENCE_LENGTH, end)
        remaining = remaining.substring(end + FENCE_LENGTH)
        sb.append(formatInlineWA(before))
        if (code.contains('\n')) {
            sb.append("<pre><code>").append(escapeHtml(code)).append("</code></pre>")
        } else {
            sb.append("<code>").append(escapeHtml(code)).append("</code>")
        }
    }
    sb.append(formatInlineWA(remaining))
    return sb.toString()
}

private fun WhatsAppProtocol.escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun WhatsAppProtocol.formatInlineWA(text: String): String {
    var result = escapeHtml(text)

    // Blockquotes (Go parseWAFormattingToHTML blockquote handling)
    result = processBlockquotes(result)

    // Ordered lists (Go orderedListRegex)
    result = processOrderedLists(result)

    // Bulleted lists — must come after bold since * is used for both
    result = processBulletedLists(result)

    result = waBoldRegex.replace(result) { "<b>${it.groupValues[1]}</b>" }
    result = waItalicRegex.replace(result) { "<i>${it.groupValues[1]}</i>" }
    result = waStrikethroughRegex.replace(result) { "<s>${it.groupValues[1]}</s>" }
    result = waInlineCodeRegex.replace(result) { "<code>${it.groupValues[1]}</code>" }

    // Inline URLs (Go inlineURLRegex)
    result = waInlineURLRegex.replace(result) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }

    result = result.replace("\n", "<br>")
    return result
}

private fun WhatsAppProtocol.processBlockquotes(text: String): String {
    val lines = text.split("\n")
    val result = StringBuilder()
    var inBlockquote = false
    for (line in lines) {
        if (line.startsWith("&gt; ")) {
            if (!inBlockquote) {
                result.append("<blockquote>")
                inBlockquote = true
            } else {
                result.append("<br>")
            }
            result.append(line.removePrefix("&gt; "))
        } else {
            if (inBlockquote) {
                result.append("</blockquote>")
                inBlockquote = false
            }
            if (result.isNotEmpty()) result.append("\n")
            result.append(line)
        }
    }
    if (inBlockquote) result.append("</blockquote>")
    return result.toString()
}

private fun WhatsAppProtocol.processOrderedLists(text: String): String {
    val lines = text.split("\n")
    val result = StringBuilder()
    var inList = false
    for (line in lines) {
        val match = Regex("^(\\d{1,2})\\. (.*)").find(line)
        if (match != null) {
            val listNumber = match.groupValues[1].toIntOrNull() ?: 1
            if (!inList) {
                result.append("<ol start=\"$listNumber\">")
                inList = true
            }
            result.append("<li value=\"$listNumber\">").append(match.groupValues[2]).append("</li>")
        } else {
            if (inList) {
                result.append("</ol>")
                inList = false
            }
            if (result.isNotEmpty()) result.append("\n")
            result.append(line)
        }
    }
    if (inList) result.append("</ol>")
    return result.toString()
}

private fun WhatsAppProtocol.processBulletedLists(text: String): String {
    val lines = text.split("\n")
    val result = StringBuilder()
    var inList = false
    for (line in lines) {
        val match = Regex("^(?:\\* |- )(.*)").find(line)
        if (match != null) {
            if (!inList) {
                result.append("<ul>")
                inList = true
            }
            result.append("<li>").append(match.groupValues[1]).append("</li>")
        } else {
            if (inList) {
                result.append("</ul>")
                inList = false
            }
            if (result.isNotEmpty()) result.append("\n")
            result.append(line)
        }
    }
    if (inList) result.append("</ul>")
    return result.toString()
}
