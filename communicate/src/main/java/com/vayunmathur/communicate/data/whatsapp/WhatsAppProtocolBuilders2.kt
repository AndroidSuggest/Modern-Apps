package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.LinkPreview
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.Node
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.QuotedContext

// -- Rich content, presence/ack, poll/location/contact protos, group IQs --

/**
 * Build a [ContextInfo] from optional mentions + quoted-reply. Returns null when neither is
 * present so callers can send a bare `conversation` message. Ref whatsmeow ContextInfo usage.
 */
fun WhatsAppProtocol.buildContextInfo(
    mentionedJids: List<String> = emptyList(),
    quoted: QuotedContext? = null,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ContextInfo? {
    if (mentionedJids.isEmpty() && quoted == null) return null
    val ctx = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ContextInfo.newBuilder()
    for (jid in mentionedJids) ctx.addMentionedJid(jid)
    if (quoted != null) {
        ctx.stanzaId = quoted.stanzaId
        if (quoted.participant.isNotEmpty()) ctx.participant = quoted.participant
        quoted.quotedMessage?.let { ctx.quotedMessage = it }
    }
    return ctx.build()
}

/**
 * Build a text message proto with optional rich content. When there are no mentions, no quoted
 * reply and no link preview, a plain `conversation` message is produced (matching
 * [buildConversationMessage]); otherwise the text is carried in an [ExtendedTextMessage] with
 * the accompanying [ContextInfo] and link-preview fields so the peer renders them. Ref
 * whatsmeow send.go ExtendedTextMessage.
 */
fun WhatsAppProtocol.buildTextProto(
    text: String,
    mentionedJids: List<String> = emptyList(),
    quoted: QuotedContext? = null,
    linkPreview: LinkPreview? = null,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message {
    val ctx = buildContextInfo(mentionedJids, quoted)
    if (ctx == null && linkPreview == null) {
        return buildConversationMessage(text)
    }
    val ext = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ExtendedTextMessage.newBuilder()
        .setText(text)
    if (ctx != null) ext.contextInfo = ctx
    if (linkPreview != null) {
        ext.matchedText = linkPreview.matchedText
        ext.canonicalUrl = linkPreview.canonicalUrl
        linkPreview.title?.let { ext.title = it }
        linkPreview.description?.let { ext.description = it }
        linkPreview.jpegThumbnail?.let { ext.jpegThumbnail = com.google.protobuf.ByteString.copyFrom(it) }
    }
    return com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
        .setExtendedTextMessage(ext.build())
        .build()
}

/**
 * Build an edit as an E2E [Message] proto (ProtocolMessage type MESSAGE_EDIT). Returned as a
 * proto — NOT a wire node — so the caller sends it through the normal Signal encryption +
 * multi-device fan-out (buildEncryptedMessageNode / buildEncryptedGroupMessageNode) with a
 * message-level `edit="1"` attribute. Previously this emitted a plaintext `<enc>` node that
 * bypassed Signal and was therefore undeliverable. Ref whatsmeow send.go BuildEdit.
 */
fun WhatsAppProtocol.buildEditProto(
    chatJid: String,
    targetMessageId: String,
    newText: String,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message {
    val messageKey = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.MessageKey.newBuilder()
        .setFromMe(true)
        .setId(targetMessageId)
        .setRemoteJid(chatJid)
        .build()

    val newContent = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
        .setConversation(newText)
        .build()

    val protocolMessage = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.newBuilder()
        .setType(com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.MESSAGE_EDIT)
        .setKey(messageKey)
        .setEditedMessage(newContent)
        .setTimestampMs(System.currentTimeMillis())
        .build()

    return com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
        .setProtocolMessage(protocolMessage)
        .build()
}

/** Whether a revoke targets a message that the local user sent (empty/own sender = self). */
fun WhatsAppProtocol.isRevokeFromMe(senderJid: String, ownJid: String): Boolean =
    senderJid.isEmpty() || senderJid == ownJid ||
        senderJid.substringBefore("@") == ownJid.substringBefore("@")

/**
 * Build a revoke (delete-for-everyone) as an E2E [Message] proto (ProtocolMessage type REVOKE).
 * Returned as a proto for the normal fan-out path; the caller sets the message-level `edit`
 * attribute ("7" for own messages, "8" for others'). Ref whatsmeow send.go BuildRevoke.
 */
fun WhatsAppProtocol.buildRevokeProto(
    chatJid: String,
    senderJid: String,
    targetMessageId: String,
    ownJid: String,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message {
    val isFromMe = isRevokeFromMe(senderJid, ownJid)
    val messageKey = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.MessageKey.newBuilder()
        .setFromMe(isFromMe)
        .setId(targetMessageId)
        .setRemoteJid(chatJid)
    if (!isFromMe && chatJid.contains("@g.us")) {
        messageKey.setParticipant(senderJid)
    }

    val protocolMessage = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.newBuilder()
        .setType(com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.REVOKE)
        .setKey(messageKey.build())
        .build()

    return com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
        .setProtocolMessage(protocolMessage)
        .build()
}

/**
 * Build a chat presence (typing indicator) node.
 * From whatsmeow/send.go SendChatPresence()
 */
fun WhatsAppProtocol.buildChatPresence(
    chatJid: String,
    isComposing: Boolean,
    isAudio: Boolean = false,
    ownJid: String = "",
): Node {
    val state = if (isComposing) "composing" else "paused"
    val childAttrs = if (isComposing && isAudio) mapOf("media" to "audio") else emptyMap()
    val attrs = mutableMapOf("to" to chatJid)
    if (ownJid.isNotEmpty()) attrs["from"] = ownJid
    return Node(
        tag = "chatstate",
        attrs = attrs,
        content = listOf(
            Node(tag = state, attrs = childAttrs)
        )
    )
}

/**
 * Build a keepalive (ping) IQ node.
 * From whatsmeow/keepalive.go
 */
fun WhatsAppProtocol.buildKeepalive(id: String): Node {
    return Node(
        tag = "iq",
        attrs = mapOf(
            "id" to id,
            "xmlns" to "w:p",
            "type" to "get",
            "to" to "s.whatsapp.net"
        )
    )
}

/**
 * Build an ack node for acknowledging received messages.
 * From whatsmeow/receipt.go sendAck()
 */
fun WhatsAppProtocol.buildAck(
    nodeClass: String,
    nodeId: String,
    from: String,
    participant: String? = null,
    recipient: String? = null,
    type: String? = null,
): Node {
    val attrs = mutableMapOf(
        "class" to nodeClass,
        "id" to nodeId,
        "to" to from
    )
    if (participant != null) attrs["participant"] = participant
    if (recipient != null) attrs["recipient"] = recipient
    if (type != null && nodeClass != "message") attrs["type"] = type
    return Node(tag = "ack", attrs = attrs)
}

/**
 * Build a native PollCreationMessage proto. Mirrors whatsmeow BuildPollCreation: only
 * optionName is set per option (option hashes are computed later at vote time), and the poll's
 * shared secret is carried in the sibling MessageContextInfo.messageSecret (encKey is left
 * unset). [selectableCount] clamps to 0 (unlimited) when negative or greater than the option
 * count. Returned as a Message proto so the caller routes it through the normal fan-out.
 */
fun WhatsAppProtocol.buildPollCreationProto(
    name: String,
    options: List<String>,
    selectableCount: Int,
    messageSecret: ByteArray,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message {
    val poll = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.PollCreationMessage.newBuilder()
        .setName(name)
        .setSelectableOptionsCount(
            if (selectableCount < 0 || selectableCount > options.size) 0 else selectableCount
        )
    for (opt in options) {
        poll.addOptions(
            com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.PollCreationMessage.Option.newBuilder()
                .setOptionName(opt)
        )
    }
    val ctx = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.MessageContextInfo.newBuilder()
        .setMessageSecret(com.google.protobuf.ByteString.copyFrom(messageSecret))
    return com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
        .setPollCreationMessageV3(poll.build())
        .setMessageContextInfo(ctx.build())
        .build()
}

/**
 * Build a LocationMessage proto (lat/long + optional name/address). Returned as a Message
 * proto so the caller can route it through the normal multi-device Signal fan-out
 * (buildEncryptedMessageNode), matching whatsmeow from-matrix.go location handling.
 */
fun WhatsAppProtocol.buildLocationProto(
    latitude: Double,
    longitude: Double,
    name: String? = null,
    address: String? = null,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message {
    val locBuilder = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.LocationMessage.newBuilder()
        .setDegreesLatitude(latitude)
        .setDegreesLongitude(longitude)
    if (!name.isNullOrEmpty()) locBuilder.setName(name)
    if (!address.isNullOrEmpty()) locBuilder.setAddress(address)

    return com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
        .setLocationMessage(locBuilder.build())
        .build()
}

/**
 * Build a contact/vCard message as an E2E [Message] proto. Returned as a proto for the normal
 * Signal fan-out path (previously emitted a plaintext `<enc>` node that was undeliverable).
 * Ref whatsmeow wa-contact.go.
 */
fun WhatsAppProtocol.buildContactProto(
    displayName: String,
    vcard: String,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message {
    val contactMsg = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ContactMessage.newBuilder()
        .setDisplayName(displayName)
        .setVcard(vcard)
        .build()

    return com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
        .setContactMessage(contactMsg)
        .build()
}

/**
 * Build a disappearing-timer change as an E2E [Message] proto (ProtocolMessage type
 * EPHEMERAL_SETTING). Returned as a proto for the normal Signal fan-out path. Allowed values:
 * 0 (off), 86400 (24h), 604800 (7d), 7776000 (90d). Ref whatsmeow HandleMatrixDisappearingTimer.
 */
fun WhatsAppProtocol.buildDisappearingTimerProto(
    timerSeconds: Long,
): com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message {
    val protocolMsg = com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.newBuilder()
        .setType(com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.ProtocolMessage.Type.EPHEMERAL_SETTING)
        .setEphemeralExpiration(timerSeconds.toInt())
        .build()

    return com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto.Message.newBuilder()
        .setProtocolMessage(protocolMsg)
        .build()
}

/**
 * Build a group info change IQ node.
 * From whatsmeow group.go SetGroupName/SetGroupTopic
 */
fun WhatsAppProtocol.buildGroupInfoChange(
    groupJid: String,
    field: String,
    value: String,
    id: String,
    extraAttrs: Map<String, String> = emptyMap(),
): Node {
    val childAttrs = mutableMapOf<String, String>()
    childAttrs.putAll(extraAttrs)
    return Node(
        tag = "iq",
        attrs = mapOf(
            "id" to id,
            "type" to "set",
            "xmlns" to "w:g2",
            "to" to groupJid,
        ),
        content = listOf(
            Node(tag = field, attrs = childAttrs, content = listOf(), data = value.toByteArray(Charsets.UTF_8))
        ),
    )
}

/**
 * Build a "set group topic/description" IQ.
 * From whatsmeow group.go SetGroupTopic: <description id=newID [prev=previousID]
 * [delete=true]> with a <body> child carrying the topic (no body when empty).
 */
fun WhatsAppProtocol.buildSetGroupTopic(
    groupJid: String,
    topic: String,
    newId: String,
    previousId: String? = null,
): Node {
    val descAttrs = mutableMapOf("id" to newId)
    if (!previousId.isNullOrEmpty()) descAttrs["prev"] = previousId
    val content = if (topic.isEmpty()) {
        descAttrs["delete"] = "true"
        emptyList()
    } else {
        listOf(Node(tag = "body", data = topic.toByteArray(Charsets.UTF_8)))
    }
    return Node(
        tag = "iq",
        attrs = mapOf(
            "id" to newId,
            "type" to "set",
            "xmlns" to "w:g2",
            "to" to groupJid,
        ),
        content = listOf(Node(tag = "description", attrs = descAttrs, content = content)),
    )
}

/**
 * Build a "leave group" IQ.
 * From whatsmeow group.go LeaveGroup: iq to the group server (g.us) with
 * <leave><group id=<groupJid>/></leave>.
 */
fun WhatsAppProtocol.buildLeaveGroup(groupJid: String, id: String): Node {
    return Node(
        tag = "iq",
        attrs = mapOf(
            "id" to id,
            "type" to "set",
            "xmlns" to "w:g2",
            "to" to "g.us",
        ),
        content = listOf(
            Node(
                tag = "leave",
                content = listOf(Node(tag = "group", attrs = mapOf("id" to groupJid))),
            ),
        ),
    )
}

/**
 * Build a create-group IQ node (whatsmeow group.go CreateGroup):
 * `<iq to="g.us" type="set" xmlns="w:g2" id=...><create subject=".." key="..">
 *   <participant jid="..."/>…</create></iq>`.
 * [key] is a client-chosen unique id echoed back in the response so we can correlate.
 */
fun WhatsAppProtocol.buildCreateGroup(
    subject: String,
    participantJids: List<String>,
    id: String,
    key: String,
): Node {
    val participants = participantJids.map { jid ->
        Node(tag = "participant", attrs = mapOf("jid" to jid))
    }
    return Node(
        tag = "iq",
        attrs = mapOf(
            "id" to id,
            "type" to "set",
            "xmlns" to "w:g2",
            "to" to "g.us",
        ),
        content = listOf(
            Node(
                tag = "create",
                attrs = mapOf("subject" to subject, "key" to key),
                content = participants,
            ),
        ),
    )
}

/**
 * Build a group participant change IQ node.
 * From whatsmeow group.go UpdateGroupParticipants
 */
fun WhatsAppProtocol.buildGroupParticipantChange(
    groupJid: String,
    participantJids: List<String>,
    action: String,
    id: String,
): Node {
    val participants = participantJids.map { jid ->
        Node(tag = "participant", attrs = mapOf("jid" to jid))
    }
    return Node(
        tag = "iq",
        attrs = mapOf(
            "id" to id,
            "type" to "set",
            "xmlns" to "w:g2",
            "to" to groupJid,
        ),
        content = listOf(
            Node(tag = action, content = participants)
        ),
    )
}
