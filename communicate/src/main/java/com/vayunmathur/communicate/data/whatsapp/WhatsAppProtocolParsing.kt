package com.vayunmathur.communicate.data.whatsapp

import android.util.Log
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.ContactData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.ContextInfoResult
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.LocationData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.Node
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.PollData

// -- Message parsing: type/body extraction, inbound node parsing, WA formatting --

private fun WhatsAppProtocol.formatDisappearingTimer(seconds: Int): String {
    return when {
        seconds >= 86400 * 90 -> "90 days"
        seconds >= 86400 * 7 -> "7 days"
        seconds >= 86400 -> "${seconds / 86400} days"
        seconds >= 3600 -> "${seconds / 3600} hours"
        seconds >= 60 -> "${seconds / 60} minutes"
        else -> "$seconds seconds"
    }
}

private fun WhatsAppProtocol.extractContextInfo(
    e2eMessage: com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message
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
        if (start == -1) break
        val end = remaining.indexOf("```", start + 3)
        if (end == -1) break
        val before = remaining.substring(0, start)
        val code = remaining.substring(start + 3, end)
        remaining = remaining.substring(end + 3)
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

fun WhatsAppProtocol.rerouteLIDSender(senderJid: String, participants: Map<String, String>?): String {
    if (!senderJid.contains("@lid")) return senderJid
    val phoneJid = participants?.get(senderJid)
    return phoneJid ?: senderJid
}

/**
 * Return the effective PollCreationMessage from a message regardless of version — modern
 * WhatsApp sends polls as pollCreationMessageV3 (field 64) or V2 (60), older ones as V1 (49).
 * Returns null if the message isn't a poll creation.
 */
fun WhatsAppProtocol.pollCreation(
    e2eMessage: com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message?,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.PollCreationMessage? = when {
    e2eMessage == null -> null
    e2eMessage.hasPollCreationMessageV3() -> e2eMessage.pollCreationMessageV3
    e2eMessage.hasPollCreationMessageV2() -> e2eMessage.pollCreationMessageV2
    e2eMessage.hasPollCreationMessage() -> e2eMessage.pollCreationMessage
    else -> null
}

/**
 * If [m] is a view-once container (viewOnceMessage / viewOnceMessageV2 /
 * viewOnceMessageV2Extension), return the wrapped inner [Message]; otherwise null. Used to
 * unwrap and flag view-once media on receive. Ref WAWebProtobufsE2E.Message view-once fields.
 */
fun WhatsAppProtocol.unwrapViewOnce(
    m: com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message? = when {
    m.hasViewOnceMessageV2Extension() && m.viewOnceMessageV2Extension.hasMessage() ->
        m.viewOnceMessageV2Extension.message
    m.hasViewOnceMessageV2() && m.viewOnceMessageV2.hasMessage() -> m.viewOnceMessageV2.message
    m.hasViewOnceMessage() && m.viewOnceMessage.hasMessage() -> m.viewOnceMessage.message
    else -> null
}

fun WhatsAppProtocol.getMessageType(e2eMessage: com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message?): String {
    val unwrapped = if (e2eMessage != null) unwrapViewOnce(e2eMessage) else null
    val e2e = unwrapped ?: e2eMessage
    return when {
        e2e == null -> "ignore"
        e2e.hasConversation() || e2e.hasExtendedTextMessage() -> "text"
        e2e.hasImageMessage() -> "image ${e2e.imageMessage.mimetype}"
        e2e.hasStickerMessage() -> "sticker ${e2e.stickerMessage.mimetype}"
        e2e.hasVideoMessage() -> "video ${e2e.videoMessage.mimetype}"
        e2e.hasAudioMessage() -> "audio ${e2e.audioMessage.mimetype}"
        e2e.hasDocumentMessage() -> "document ${e2e.documentMessage.mimetype}"
        e2e.hasContactMessage() -> "contact"
        e2e.hasLocationMessage() -> "location"
        pollCreation(e2e) != null -> "poll"
        e2e.hasReactionMessage() -> {
            if (e2e.reactionMessage.text.isNullOrEmpty()) "reaction remove" else "reaction"
        }
        e2e.hasEditedMessage() -> {
            val inner = e2e.editedMessage?.message
            if (inner != null) getMessageType(inner) else "ignore"
        }
        e2e.hasProtocolMessage() -> {
            when (e2e.protocolMessage.type) {
                com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.REVOKE -> {
                    if (e2e.protocolMessage.hasKey()) "revoke" else "ignore"
                }
                com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.MESSAGE_EDIT -> "edit"
                com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.EPHEMERAL_SETTING -> "ephemeral setting"
                com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.HISTORY_SYNC_NOTIFICATION -> "history_sync"
                com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.APP_STATE_SYNC_KEY_SHARE -> "app_state_key"
                com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.INITIAL_SECURITY_NOTIFICATION_SETTING_SYNC,
                com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.APP_STATE_FATAL_EXCEPTION_NOTIFICATION,
                com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.SHARE_PHONE_NUMBER,
                com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_MESSAGE,
                com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_RESPONSE_MESSAGE -> "ignore"
                else -> "unknown_protocol_${e2e.protocolMessage.type.number}"
            }
        }
        e2e.hasSenderKeyDistributionMessage() -> "ignore"
        else -> "unknown"
    }
}

/**
 * Extract a human-readable body/preview from a decrypted [Message] (used for history-sync
 * backfill, where each WebMessageInfo wraps a Message). Mirrors the body logic in parseMessage.
 */
fun WhatsAppProtocol.extractMessageBody(m: com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message): String {
    var e2e = m
    if (e2e.hasEditedMessage() && e2e.editedMessage.hasMessage()) e2e = e2e.editedMessage.message
    unwrapViewOnce(e2e)?.let { e2e = it }
    return when {
        e2e.hasConversation() -> e2e.conversation
        e2e.hasExtendedTextMessage() -> e2e.extendedTextMessage.text
        e2e.hasImageMessage() -> e2e.imageMessage.caption.ifEmpty { "[Image]" }
        e2e.hasVideoMessage() -> e2e.videoMessage.caption.ifEmpty { "[Video]" }
        e2e.hasAudioMessage() -> "[Audio]"
        e2e.hasDocumentMessage() -> "[Document: ${e2e.documentMessage.title}]"
        e2e.hasStickerMessage() -> "[Sticker]"
        e2e.hasContactMessage() -> "[Contact: ${e2e.contactMessage.displayName}]"
        e2e.hasLocationMessage() -> "[Location]"
        else -> ""
    }
}

/**
 * Parse an inbound <message> node...
 * Signal-decrypted (and unpadded) via the callback; otherwise the raw enc data is treated
 * as already-plaintext padded protobuf (legacy/no-crypto path).
 *
 * [decryptEnc] receives (senderJid, encType, encData) and returns the decrypted, still
 * padded plaintext, or null if decryption failed.
 */
fun WhatsAppProtocol.parseMessage(
    node: Node,
    decryptEnc: ((senderJid: String, encType: String, data: ByteArray) -> ByteArray?)? = null,
): WhatsAppMessage? {
    if (node.tag != "message") return null

    val rawFrom = node.attrs["from"] ?: return null
    // For 1:1 chats WhatsApp now addresses the sender by LID (e.g. 13184…@s.whatsapp.net) and
    // carries the phone number in sender_pn. Normalize to the PN so live messages map to the
    // same conversation as history (which is keyed by phone JID). Groups/broadcast keep `from`.
    val senderPn = node.attrs["sender_pn"]
    val from = if (!senderPn.isNullOrEmpty() && !rawFrom.contains("@g.us") && !rawFrom.contains("broadcast")) {
        senderPn.substringBefore(":").let { if (it.contains("@")) it else "$it@s.whatsapp.net" }
    } else {
        rawFrom
    }
    val id = node.attrs["id"] ?: return null
    val type = node.attrs["type"] ?: "text"
    val timestamp = node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000
    val participant = node.attrs["participant"]

    val encNode = node.getChildByTag("enc")
    if (encNode?.data != null) {
        return try {
            val encType = encNode.attrs["type"] ?: "msg"
            val senderJid = participant ?: from
            val decryptedPadded: ByteArray = if (decryptEnc != null) {
                decryptEnc(senderJid, encType, encNode.data)
                    ?: return WhatsAppMessage(
                        id = id, from = from, to = node.attrs["to"] ?: "", body = "",
                        timestamp = timestamp, type = type, participant = participant,
                    )
            } else {
                encNode.data
            }
            val plaintext = unpadMessage(decryptedPadded)
            var e2eMessage = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message.parseFrom(plaintext)

            // Messages the user sends from another linked device are echoed to us
            // either wrapped in a DeviceSentMessage (carrying the real chat in
            // destinationJid) or, for 1:1, with `from` set to our own JID and the real
            // chat in the `recipient` attr. Unwrap/redirect so these map to the right
            // conversation instead of surfacing as a blank message from ourselves.
            // Ref whatsmeow message.go processProtocolParts / parseMessageSource.
            var chatJid = from
            var fromMe = false
            if (e2eMessage.hasDeviceSentMessage()) {
                fromMe = true
                val dsm = e2eMessage.deviceSentMessage
                if (dsm.destinationJid.isNotEmpty()) chatJid = dsm.destinationJid
                if (dsm.hasMessage()) e2eMessage = dsm.message
            } else {
                val recipient = node.attrs["recipient"]
                if (!recipient.isNullOrEmpty()) {
                    fromMe = true
                    chatJid = recipient
                }
            }

            // Unwrap FutureProofMessage (Go events.go GetInnerMessage)
            if (e2eMessage.hasEditedMessage() && e2eMessage.editedMessage.hasMessage()) {
                e2eMessage = e2eMessage.editedMessage.message
            }

            // Unwrap view-once container (Go convertMediaMessage viewOnce check) and remember
            // it so the UI can render "opened once" media.
            var isViewOnceMsg = false
            val vo = unwrapViewOnce(e2eMessage)
            if (vo != null) {
                isViewOnceMsg = true
                e2eMessage = vo
            }

            val parsedType = getMessageType(e2eMessage)
            // A message carrying only a SenderKeyDistributionMessage types as "ignore", but we
            // MUST still process it — it's what establishes the group sender key so subsequent
            // skmsg group messages can be decrypted. Let it through here; the caller processes
            // the key and then stops (see handleIncomingMessage). Other "ignore" / unknown
            // protocol messages are still dropped.
            val hasSenderKeyDistribution = e2eMessage.hasSenderKeyDistributionMessage()
            if ((parsedType == "ignore" && !hasSenderKeyDistribution) ||
                parsedType.startsWith("unknown_protocol_")
            ) {
                return null
            }

            val body = when {
                e2eMessage.hasConversation() -> e2eMessage.conversation
                e2eMessage.hasExtendedTextMessage() -> e2eMessage.extendedTextMessage.text
                e2eMessage.hasImageMessage() -> e2eMessage.imageMessage.caption.ifEmpty { "[Image]" }
                e2eMessage.hasVideoMessage() -> e2eMessage.videoMessage.caption.ifEmpty { "[Video]" }
                e2eMessage.hasAudioMessage() -> "[Audio]"
                e2eMessage.hasDocumentMessage() -> "[Document: ${e2eMessage.documentMessage.title}]"
                e2eMessage.hasStickerMessage() -> "[Sticker]"
                e2eMessage.hasContactMessage() -> {
                    val c = e2eMessage.contactMessage
                    "[Contact: ${c.displayName}]\n${c.vcard}"
                }
                e2eMessage.hasLocationMessage() -> {
                    val loc = e2eMessage.locationMessage
                    val lat = loc.degreesLatitude
                    val lng = loc.degreesLongitude
                    val name = loc.name.ifEmpty {
                        val latDir = if (lat >= 0) 'N' else 'S'
                        val lngDir = if (lng >= 0) 'E' else 'W'
                        "%.4f° %c %.4f° %c".format(Math.abs(lat), latDir, Math.abs(lng), lngDir)
                    }
                    val mapsUrl = "https://maps.google.com/?q=%.5f,%.5f".format(lat, lng)
                    "Location: $name\n${loc.address}\n$mapsUrl"
                }
                e2eMessage.hasReactionMessage() -> e2eMessage.reactionMessage.text
                pollCreation(e2eMessage) != null -> {
                    val poll = pollCreation(e2eMessage)!!
                    buildString {
                        append("📊 ")
                        append(poll.name)
                        poll.optionsList.forEach { append("\n• "); append(it.optionName) }
                    }
                }
                e2eMessage.hasProtocolMessage() -> {
                    when (parsedType) {
                        "revoke" -> "[Message Deleted]"
                        "edit" -> e2eMessage.protocolMessage.editedMessage?.conversation ?: "[Edited]"
                        "ephemeral setting" -> {
                            val timer = e2eMessage.protocolMessage.ephemeralExpiration
                            if (timer == 0) "[Disappearing Messages Disabled]"
                            else "[Disappearing Messages: ${formatDisappearingTimer(timer)}]"
                        }
                        else -> ""
                    }
                }
                else -> ""
            }
            val mediaUrl = when {
                e2eMessage.hasImageMessage() -> e2eMessage.imageMessage.url
                e2eMessage.hasVideoMessage() -> e2eMessage.videoMessage.url
                e2eMessage.hasAudioMessage() -> e2eMessage.audioMessage.url
                e2eMessage.hasDocumentMessage() -> e2eMessage.documentMessage.url
                e2eMessage.hasStickerMessage() -> e2eMessage.stickerMessage.url
                else -> null
            }
            val isRevoke = parsedType == "revoke"
            val revokeTargetId = if (isRevoke) e2eMessage.protocolMessage.key.id else null
            val isEdit = parsedType == "edit"
            val editTargetId = if (isEdit) e2eMessage.protocolMessage.key.id else null

            val locationData = when {
                e2eMessage.hasLocationMessage() -> LocationData(
                    latitude = e2eMessage.locationMessage.degreesLatitude,
                    longitude = e2eMessage.locationMessage.degreesLongitude,
                    name = e2eMessage.locationMessage.name.ifEmpty { null },
                    address = e2eMessage.locationMessage.address.ifEmpty { null },
                    url = e2eMessage.locationMessage.url.ifEmpty { null },
                )
                else -> null
            }

            val contactData = when {
                e2eMessage.hasContactMessage() -> ContactData(
                    displayName = e2eMessage.contactMessage.displayName,
                    vcard = e2eMessage.contactMessage.vcard,
                )
                else -> null
            }

            val pollData: PollData? = pollCreation(e2eMessage)?.let { poll ->
                PollData(
                    question = poll.name,
                    options = poll.optionsList.map { it.optionName },
                    selectableOptionCount = poll.selectableOptionsCount,
                )
            }

            val groupInviteData: GroupInviteMeta? = null

            val disappearingTimer = if (parsedType == "ephemeral setting") {
                e2eMessage.protocolMessage.ephemeralExpiration.toLong()
            } else null

            val contextInfo = extractContextInfo(e2eMessage)

            // View-once: set when the message arrived wrapped in a view-once container
            // (unwrapped above). HD is not representable in this proto subset — WhatsApp's HD
            // flag lives in fields we don't model — so it stays false (documented, not faked).
            val isViewOnce = isViewOnceMsg

            val isHD = false

            WhatsAppMessage(
                id = id,
                from = chatJid,
                to = node.attrs["to"] ?: "",
                body = body,
                timestamp = timestamp,
                type = type,
                participant = participant,
                mediaUrl = mediaUrl,
                isReaction = e2eMessage.hasReactionMessage(),
                reactionTargetId = if (e2eMessage.hasReactionMessage()) e2eMessage.reactionMessage.key.id else null,
                isRevoke = isRevoke,
                revokeTargetId = revokeTargetId,
                isEdit = isEdit,
                editTargetId = editTargetId,
                messageType = parsedType,
                locationData = locationData,
                contactData = contactData,
                pollData = pollData,
                groupInviteData = groupInviteData,
                disappearingTimer = disappearingTimer,
                isForwarded = contextInfo.isForwarded,
                forwardingScore = contextInfo.forwardingScore,
                replyToId = contextInfo.replyToId,
                mentionedJids = contextInfo.mentionedJids,
                isViewOnce = isViewOnce,
                isHD = isHD,
                isFromMe = fromMe,
                e2eMessage = e2eMessage,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse E2E message", e)
            WhatsAppMessage(id = id, from = from, to = node.attrs["to"] ?: "", body = "", timestamp = timestamp, type = type, participant = participant)
        }
    }

    // Fallback: plaintext body node
    val bodyNode = node.getChildByTag("body")
    val body = bodyNode?.data?.let { String(it, Charsets.UTF_8) } ?: ""

    return WhatsAppMessage(
        id = id,
        from = from,
        to = node.attrs["to"] ?: "",
        body = body,
        timestamp = timestamp,
        type = type,
        participant = participant,
    )
}
