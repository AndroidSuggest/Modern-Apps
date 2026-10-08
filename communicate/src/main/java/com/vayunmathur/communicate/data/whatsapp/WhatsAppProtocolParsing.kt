package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.library.log.Log
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.Node
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto

// -- Message parsing: type/body extraction, inbound node parsing, WA formatting --

internal const val SECONDS_PER_MINUTE = 60
internal const val SECONDS_PER_HOUR = 3600
internal const val SECONDS_PER_DAY = 86400
internal const val SECONDS_PER_WEEK = SECONDS_PER_DAY * 7
internal const val SECONDS_90_DAYS = SECONDS_PER_DAY * 90
internal const val FENCE_LENGTH = 3


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
    e2eMessage: WhatsAppE2EProto.Message?,
): WhatsAppE2EProto.PollCreationMessage? = when {
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
    m: WhatsAppE2EProto.Message,
): WhatsAppE2EProto.Message? = when {
    m.hasViewOnceMessageV2Extension() && m.viewOnceMessageV2Extension.hasMessage() ->
        m.viewOnceMessageV2Extension.message
    m.hasViewOnceMessageV2() && m.viewOnceMessageV2.hasMessage() -> m.viewOnceMessageV2.message
    m.hasViewOnceMessage() && m.viewOnceMessage.hasMessage() -> m.viewOnceMessage.message
    else -> null
}

fun WhatsAppProtocol.getMessageType(e2eMessage: WhatsAppE2EProto.Message?): String {
    val unwrapped = if (e2eMessage != null) unwrapViewOnce(e2eMessage) else null
    val e2e = unwrapped ?: e2eMessage
    return when {
        e2e == null -> "ignore"
        e2e.hasConversation() || e2e.hasExtendedTextMessage() -> "text"
        e2e.hasContactMessage() -> "contact"
        e2e.hasLocationMessage() -> "location"
        pollCreation(e2e) != null -> "poll"
        else -> mediaMessageType(e2e) ?: extendedMessageType(e2e)
    }
}

/** Media message type, or null when not media. */
private fun WhatsAppProtocol.mediaMessageType(e2e: WhatsAppE2EProto.Message): String? = when {
    e2e.hasImageMessage() -> "image ${e2e.imageMessage.mimetype}"
    e2e.hasStickerMessage() -> "sticker ${e2e.stickerMessage.mimetype}"
    e2e.hasVideoMessage() -> "video ${e2e.videoMessage.mimetype}"
    e2e.hasAudioMessage() -> "audio ${e2e.audioMessage.mimetype}"
    e2e.hasDocumentMessage() -> "document ${e2e.documentMessage.mimetype}"
    else -> null
}

/** Type string for reaction/edit/protocol/sender-key messages. */
private fun WhatsAppProtocol.extendedMessageType(e2e: WhatsAppE2EProto.Message): String {
    return when {
        e2e.hasReactionMessage() -> {
            if (e2e.reactionMessage.text.isNullOrEmpty()) "reaction remove" else "reaction"
        }
        e2e.hasEditedMessage() -> {
            val inner = e2e.editedMessage?.message
            if (inner != null) getMessageType(inner) else "ignore"
        }
        e2e.hasProtocolMessage() -> protocolMessageType(e2e)
        e2e.hasSenderKeyDistributionMessage() -> "ignore"
        else -> "unknown"
    }
}

/** Type string for protocol messages. */
private fun WhatsAppProtocol.protocolMessageType(e2e: WhatsAppE2EProto.Message): String {
    val protocol = e2e.protocolMessage
    return when (protocol.type) {
        WhatsAppE2EProto.ProtocolMessage.Type.REVOKE -> {
            if (protocol.hasKey()) "revoke" else "ignore"
        }
        WhatsAppE2EProto.ProtocolMessage.Type.MESSAGE_EDIT -> "edit"
        WhatsAppE2EProto.ProtocolMessage.Type.EPHEMERAL_SETTING -> "ephemeral setting"
        WhatsAppE2EProto.ProtocolMessage.Type.HISTORY_SYNC_NOTIFICATION -> "history_sync"
        WhatsAppE2EProto.ProtocolMessage.Type.APP_STATE_SYNC_KEY_SHARE -> "app_state_key"
        WhatsAppE2EProto.ProtocolMessage.Type.INITIAL_SECURITY_NOTIFICATION_SETTING_SYNC,
        WhatsAppE2EProto.ProtocolMessage.Type.APP_STATE_FATAL_EXCEPTION_NOTIFICATION,
        WhatsAppE2EProto.ProtocolMessage.Type.SHARE_PHONE_NUMBER,
        WhatsAppE2EProto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_MESSAGE,
        WhatsAppE2EProto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_RESPONSE_MESSAGE -> "ignore"
        else -> "unknown_protocol_${protocol.type.number}"
    }
}

/**
 * Extract a human-readable body/preview from a decrypted [Message] (used for history-sync
 * backfill, where each WebMessageInfo wraps a Message). Mirrors the body logic in parseMessage.
 */
fun WhatsAppProtocol.extractMessageBody(m: WhatsAppE2EProto.Message): String {
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
    val from = normalizeSender(rawFrom, node.attrs["sender_pn"])
    val id = node.attrs["id"] ?: return null
    val type = node.attrs["type"] ?: "text"
    val timestamp = node.attrs["t"]?.toLongOrNull() ?: System.currentTimeMillis() / 1000
    val participant = node.attrs["participant"]

    val encNode = node.getChildByTag("enc")
    if (encNode?.data != null) {
        return parseEncryptedMessage(node, encNode, from, id, type, timestamp, participant, decryptEnc)
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

/**
 * Normalize the sender: for 1:1 chats WhatsApp now addresses the sender by LID
 * (e.g. 13184…@s.whatsapp.net) and carries the phone number in sender_pn.
 * Normalize to the PN so live messages map to the same conversation as history
 * (which is keyed by phone JID). Groups/broadcast keep `from`.
 */
private fun normalizeSender(rawFrom: String, senderPn: String?): String {
    if (!senderPn.isNullOrEmpty() && !rawFrom.contains("@g.us") && !rawFrom.contains("broadcast")) {
        return senderPn.substringBefore(":").let { if (it.contains("@")) it else "$it@s.whatsapp.net" }
    }
    return rawFrom
}

/** Decrypt + unwrap + assemble an E2E message node. */
private fun WhatsAppProtocol.parseEncryptedMessage(
    node: Node,
    encNode: Node,
    from: String,
    id: String,
    type: String,
    timestamp: Long,
    participant: String?,
    decryptEnc: ((senderJid: String, encType: String, data: ByteArray) -> ByteArray?)?,
): WhatsAppMessage? {
    return try {
        val plaintext = decryptPayload(encNode, from, participant, decryptEnc)
            ?: return WhatsAppMessage(
                id = id, from = from, to = node.attrs["to"] ?: "", body = "",
                timestamp = timestamp, type = type, participant = participant,
            )
        var e2eMessage = WhatsAppE2EProto.Message.parseFrom(plaintext)

        // Messages the user sends from another linked device are echoed to us
        // either wrapped in a DeviceSentMessage (carrying the real chat in
        // destinationJid) or, for 1:1, with `from` set to our own JID and the real
        // chat in the `recipient` attr. Unwrap/redirect so these map to the right
        // conversation instead of surfacing as a blank message from ourselves.
        // Ref whatsmeow message.go processProtocolParts / parseMessageSource.
        val (chatJid, fromMe, unwrapped) = unwrapMessageSource(e2eMessage, from, node)
        e2eMessage = unwrapped

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

        buildParsedMessage(node, e2eMessage, chatJid, fromMe, isViewOnceMsg, id, type, timestamp, participant)
    } catch (expected: Exception) {
        Log.error(TAG, "Failed to parse E2E message", expected)
        WhatsAppMessage(
            id = id,
            from = from,
            to = node.attrs["to"] ?: "",
            body = "",
            timestamp = timestamp,
            type = type,
            participant = participant)
    }
}

/** Decrypt the enc payload, or null when undecryptable. */
private fun WhatsAppProtocol.decryptPayload(
    encNode: Node,
    from: String,
    participant: String?,
    decryptEnc: ((senderJid: String, encType: String, data: ByteArray) -> ByteArray?)?,
): ByteArray? {
    val encType = encNode.attrs["type"] ?: "msg"
    val senderJid = participant ?: from
    val decryptedPadded: ByteArray = if (decryptEnc != null) {
        decryptEnc(senderJid, encType, encNode.data ?: return null)
            ?: return null
    } else {
        encNode.data ?: return null
    }
    return unpadMessage(decryptedPadded)
}

/** Unwrap DSM/recipient echo into (chatJid, fromMe, message). */
private fun WhatsAppProtocol.unwrapMessageSource(
    e2eMessage: WhatsAppE2EProto.Message,
    from: String,
    node: Node,
): Triple<String, Boolean, WhatsAppE2EProto.Message> {
    var chatJid = from
    var fromMe = false
    var msg = e2eMessage
    if (msg.hasDeviceSentMessage()) {
        fromMe = true
        val dsm = msg.deviceSentMessage
        if (dsm.destinationJid.isNotEmpty()) chatJid = dsm.destinationJid
        if (dsm.hasMessage()) msg = dsm.message
    } else {
        val recipient = node.attrs["recipient"]
        if (!recipient.isNullOrEmpty()) {
            fromMe = true
            chatJid = recipient
        }
    }
    return Triple(chatJid, fromMe, msg)
}

/** Assemble the final message, or null for ignorable protocol stanzas. */
private fun WhatsAppProtocol.buildParsedMessage(
    node: Node,
    e2eMessage: WhatsAppE2EProto.Message,
    chatJid: String,
    fromMe: Boolean,
    isViewOnceMsg: Boolean,
    id: String,
    type: String,
    timestamp: Long,
    participant: String?,
): WhatsAppMessage? {

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

    val body = messageBodyText(e2eMessage, parsedType)
    val mediaUrl = mediaUrlOf(e2eMessage)
    val isRevoke = parsedType == "revoke"
    val revokeTargetId = if (isRevoke) e2eMessage.protocolMessage.key.id else null
    val isEdit = parsedType == "edit"
    val editTargetId = if (isEdit) e2eMessage.protocolMessage.key.id else null
    val contextInfo = extractContextInfo(e2eMessage)

    // View-once: set when the message arrived wrapped in a view-once container
    // (unwrapped above). HD is not representable in this proto subset — WhatsApp's HD
    // flag lives in fields we don't model — so it stays false (documented, not faked).

    return WhatsAppMessage(
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
        locationData = locationDataOf(e2eMessage),
        contactData = contactDataOf(e2eMessage),
        pollData = pollDataOf(e2eMessage),
        groupInviteData = null,
        disappearingTimer = disappearingTimerOf(e2eMessage, parsedType),
        isForwarded = contextInfo.isForwarded,
        forwardingScore = contextInfo.forwardingScore,
        replyToId = contextInfo.replyToId,
        mentionedJids = contextInfo.mentionedJids,
        isViewOnce = isViewOnceMsg,
        isHD = false,
        isFromMe = fromMe,
        e2eMessage = e2eMessage,
    )
}

/** Location payload, when present. */
private fun locationDataOf(e2eMessage: WhatsAppE2EProto.Message): LocationData? = when {
    e2eMessage.hasLocationMessage() -> LocationData(
        latitude = e2eMessage.locationMessage.degreesLatitude,
        longitude = e2eMessage.locationMessage.degreesLongitude,
        name = e2eMessage.locationMessage.name.ifEmpty { null },
        address = e2eMessage.locationMessage.address.ifEmpty { null },
        url = e2eMessage.locationMessage.url.ifEmpty { null },
    )
    else -> null
}

/** Contact payload, when present. */
private fun contactDataOf(e2eMessage: WhatsAppE2EProto.Message): ContactData? = when {
    e2eMessage.hasContactMessage() -> ContactData(
        displayName = e2eMessage.contactMessage.displayName,
        vcard = e2eMessage.contactMessage.vcard,
    )
    else -> null
}

/** Poll payload, when present. */
private fun WhatsAppProtocol.pollDataOf(e2eMessage: WhatsAppE2EProto.Message): PollData? =
    pollCreation(e2eMessage)?.let { poll ->
        PollData(
            question = poll.name,
            options = poll.optionsList.map { it.optionName },
            selectableOptionCount = poll.selectableOptionsCount,
        )
    }

/** Disappearing-timer value for ephemeral-setting messages. */
private fun disappearingTimerOf(e2eMessage: WhatsAppE2EProto.Message, parsedType: String): Long? =
    if (parsedType == "ephemeral setting") {
        e2eMessage.protocolMessage.ephemeralExpiration.toLong()
    } else {
        null
    }

/** Display body text for a parsed E2E message. */
private fun WhatsAppProtocol.messageBodyText(
    e2eMessage: WhatsAppE2EProto.Message,
    parsedType: String,
): String = when {
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
    e2eMessage.hasLocationMessage() -> locationBodyText(e2eMessage)
    e2eMessage.hasReactionMessage() -> e2eMessage.reactionMessage.text
    pollCreation(e2eMessage) != null -> pollBodyText(e2eMessage)
    e2eMessage.hasProtocolMessage() -> protocolBodyText(e2eMessage, parsedType)
    else -> ""
}

/** Location message body with maps link. */
private fun locationBodyText(e2eMessage: WhatsAppE2EProto.Message): String {
    val loc = e2eMessage.locationMessage
    val lat = loc.degreesLatitude
    val lng = loc.degreesLongitude
    val name = loc.name.ifEmpty {
        val latDir = if (lat >= 0) 'N' else 'S'
        val lngDir = if (lng >= 0) 'E' else 'W'
        "%.4f\u00b0 %c %.4f\u00b0 %c".format(Math.abs(lat), latDir, Math.abs(lng), lngDir)
    }
    val mapsUrl = "https://maps.google.com/?q=%.5f,%.5f".format(lat, lng)
    return "Location: $name\n${loc.address}\n$mapsUrl"
}

/** Poll creation body. */
private fun WhatsAppProtocol.pollBodyText(e2eMessage: WhatsAppE2EProto.Message): String {
    val poll = pollCreation(e2eMessage)!!
    return buildString {
        append("\ud83d\udcca ")
        append(poll.name)
        poll.optionsList.forEach { append("\n\u2022 "); append(it.optionName) }
    }
}

/** Protocol-message body (revoke/edit/ephemeral). */
private fun WhatsAppProtocol.protocolBodyText(
    e2eMessage: WhatsAppE2EProto.Message,
    parsedType: String,
): String = when (parsedType) {
    "revoke" -> "[Message Deleted]"
    "edit" -> e2eMessage.protocolMessage.editedMessage?.conversation ?: "[Edited]"
    "ephemeral setting" -> {
        val timer = e2eMessage.protocolMessage.ephemeralExpiration
        if (timer == 0) "[Disappearing Messages Disabled]"
        else "[Disappearing Messages: ${formatDisappearingTimer(timer)}]"
    }
    else -> ""
}

/** Media URL for attachment messages, or null. */
private fun mediaUrlOf(e2eMessage: WhatsAppE2EProto.Message): String? = when {
    e2eMessage.hasImageMessage() -> e2eMessage.imageMessage.url
    e2eMessage.hasVideoMessage() -> e2eMessage.videoMessage.url
    e2eMessage.hasAudioMessage() -> e2eMessage.audioMessage.url
    e2eMessage.hasDocumentMessage() -> e2eMessage.documentMessage.url
    e2eMessage.hasStickerMessage() -> e2eMessage.stickerMessage.url
    else -> null
}
