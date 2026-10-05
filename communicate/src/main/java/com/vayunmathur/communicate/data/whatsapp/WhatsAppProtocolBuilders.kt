package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.Node
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol.ParticipantEnc
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto

// -- Message node builders (from whatsmeow/send.go) --

private const val PEER_DATA_HISTORY_SYNC_ON_DEMAND = 3
private const val SHIFT_BYTE3 = 24
private const val SHIFT_BYTE2 = 16
private const val SHIFT_BYTE1 = 8
private const val MS_PER_SECOND = 1000L

/**
 * Build a text message node with E2E encrypted protobuf payload.
 * The enc node contains the Signal-encrypted E2E.Message protobuf.
 */
/**
 * Build the E2E protobuf plaintext for a conversation (text) message.
 * The returned bytes are the unencrypted, unpadded waE2E.Message; the caller pads and
 * Signal-encrypts them before placing into an <enc> node.
 */
fun WhatsAppProtocol.buildConversationPlaintext(text: String): ByteArray {
    return buildConversationMessage(text).toByteArray()
}

/** Build the waE2E.Message proto object for a conversation (text) message. */
fun WhatsAppProtocol.buildConversationMessage(text: String): WhatsAppE2EProto.Message {
    return WhatsAppE2EProto.Message.newBuilder()
        .setConversation(text)
        .build()
}

/**
 * Build a HISTORY_SYNC_ON_DEMAND peer-data-operation request. Sent E2E to our own account to
 * ask the primary to stream older messages for a chat. Ref whatsmeow BuildHistorySyncRequest.
 * Note: oldestMsgTimestampMs is actually seconds despite the field name.
 */
fun WhatsAppProtocol.buildHistoryOnDemandRequest(
    chatJid: String,
    oldestMsgId: String,
    oldestMsgFromMe: Boolean,
    oldestMsgTimestampSec: Long,
    count: Int,
): WhatsAppE2EProto.Message {
    val req = WhatsAppE2EProto.HistorySyncOnDemandRequest.newBuilder()
        .setChatJid(chatJid)
        .setOldestMsgId(oldestMsgId)
        .setOldestMsgFromMe(oldestMsgFromMe)
        .setOnDemandMsgCount(count)
        .setOldestMsgTimestampMs(oldestMsgTimestampSec)
    val pdo =
        WhatsAppE2EProto.PeerDataOperationRequestMessage.newBuilder()
        .setPeerDataOperationRequestType(PEER_DATA_HISTORY_SYNC_ON_DEMAND) // HISTORY_SYNC_ON_DEMAND
        .setHistorySyncOnDemandRequest(req)
    val proto = WhatsAppE2EProto.ProtocolMessage.newBuilder()
        .setType(WhatsAppE2EProto.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_MESSAGE)
        .setPeerDataOperationRequestMessage(pdo)
    return WhatsAppE2EProto.Message.newBuilder()
        .setProtocolMessage(proto)
        .build()
}

/**
 * Wrap a message in a DeviceSentMessage for fan-out to the sender's own other devices.
 * Ref whatsmeow send.go marshalMessage() dsmPlaintext.
 */
fun WhatsAppProtocol.deviceSentPlaintext(
    destinationJid: String,
    message: WhatsAppE2EProto.Message,
): ByteArray {
    return WhatsAppE2EProto.Message.newBuilder()
        .setDeviceSentMessage(
            WhatsAppE2EProto.DeviceSentMessage.newBuilder()
                .setDestinationJid(destinationJid)
                .setMessage(message)
        )
        .build()
        .toByteArray()
}

/**
 * Build the SKDM-bearing plaintext that is 1:1 fanned out to every group device so they
 * can decrypt the group skmsg. Ref whatsmeow send.go sendGroup() skdMessage.
 */
fun WhatsAppProtocol.senderKeyDistributionPlaintext(groupJid: String, axolotlSkdm: ByteArray): ByteArray {
    return WhatsAppE2EProto.Message.newBuilder()
        .setSenderKeyDistributionMessage(
            WhatsAppE2EProto.SenderKeyDistributionMessage.newBuilder()
                .setGroupId(groupJid)
                .setAxolotlSenderKeyDistributionMessage(com.google.protobuf.ByteString.copyFrom(axolotlSkdm))
        )
        .build()
        .toByteArray()
}

/**
 * Build an outgoing message node with a <participants> fan-out: one <to jid><enc> per
 * recipient/own device, plus an optional message-level extra <enc> (the group skmsg) and a
 * <device-identity> node when any per-device enc is a pkmsg. Mirrors whatsmeow
 * send.go prepareMessageNode/sendGroup.
 *
 * [participantEncs] is (wireDeviceJid, encType, ciphertext) per device.
 */
fun WhatsAppProtocol.buildFanOutMessageNode(
    to: String,
    id: String,
    type: String,
    participantEncs: List<ParticipantEnc>,
    includeDeviceIdentity: Boolean,
    deviceIdentity: ByteArray?,
    extraEnc: Node? = null,
    extraEncAttrs: Map<String, String> = emptyMap(),
    messageAttrs: Map<String, String> = emptyMap(),
): Node {
    val toNodes = participantEncs.map { pe ->
        val encAttrs = mutableMapOf("v" to "2", "type" to pe.encType)
        encAttrs.putAll(extraEncAttrs)
        Node(
            tag = "to",
            attrs = mapOf("jid" to pe.deviceJid),
            content = listOf(Node(tag = "enc", attrs = encAttrs, data = pe.ciphertext)),
        )
    }
    val participants = Node(tag = "participants", content = toNodes)
    val content = mutableListOf(participants)
    if (extraEnc != null) content.add(extraEnc)
    if (includeDeviceIdentity && deviceIdentity != null) {
        content.add(Node(tag = "device-identity", data = deviceIdentity))
    }
    val attrs = mutableMapOf("to" to to, "id" to id, "type" to type)
    attrs.putAll(messageAttrs)
    return Node(tag = "message", attrs = attrs, content = content)
}

/**
 * Build a retry receipt sent when an inbound message fails to decrypt, asking the sender to
 * re-encrypt. Ref whatsmeow retry.go sendRetryReceipt. [keysNode] (identity + fresh prekeys +
 * device-identity) should be included on the 2nd+ retry or when forced.
 */
fun WhatsAppProtocol.buildRetryReceipt(
    originalNode: Node,
    registrationId: Int,
    retryCount: Int,
    keysNode: Node?,
): Node {
    val msgId = originalNode.attrs["id"] ?: ""
    val attrs = mutableMapOf(
        "id" to msgId,
        "to" to (originalNode.attrs["from"] ?: ""),
        "type" to "retry",
    )
    originalNode.attrs["recipient"]?.let { attrs["recipient"] = it }
    originalNode.attrs["participant"]?.let { attrs["participant"] = it }
    val regBytes = byteArrayOf(
        (registrationId ushr SHIFT_BYTE3).toByte(),
        (registrationId ushr SHIFT_BYTE2).toByte(),
        (registrationId ushr SHIFT_BYTE1).toByte(),
        registrationId.toByte(),
    )
    val retryNode = Node(
        tag = "retry",
        attrs = mapOf(
            "count" to retryCount.toString(),
            "id" to msgId,
            "t" to (originalNode.attrs["t"] ?: ""),
            "v" to "1",
        ),
    )
    val content = mutableListOf(retryNode, Node(tag = "registration", data = regBytes))
    if (keysNode != null) content.add(keysNode)
    return Node(tag = "receipt", attrs = attrs, content = content)
}

/**
 * Build a group-info (participants) interactive query IQ.
 * Ref whatsmeow group.go getGroupInfo: <iq to=group xmlns=w:g2 type=get><query request=interactive/>.
 */
fun WhatsAppProtocol.buildGroupParticipantsQuery(groupJid: String, id: String): Node {
    return Node(
        tag = "iq",
        attrs = mapOf("id" to id, "type" to "get", "xmlns" to "w:g2", "to" to groupJid),
        content = listOf(Node(tag = "query", attrs = mapOf("request" to "interactive"))),
    )
}

/**
 * Build a usync device-list query for the given users.
 * Ref whatsmeow user.go GetUserDevices/usync: <iq xmlns=usync><usync mode=query context=message>
 * <query><devices version=2/></query><list><user jid=.../></list></usync>.
 */
fun WhatsAppProtocol.buildUsyncDevicesQuery(userJids: List<String>, id: String, sid: String): Node {
    val userNodes = userJids.map { Node(tag = "user", attrs = mapOf("jid" to it)) }
    return Node(
        tag = "iq",
        attrs = mapOf("id" to id, "type" to "get", "xmlns" to "usync", "to" to "s.whatsapp.net"),
        content = listOf(
            Node(
                tag = "usync",
                attrs = mapOf(
                    "sid" to sid,
                    "mode" to "query",
                    "last" to "true",
                    "index" to "0",
                    "context" to "message",
                ),
                content = listOf(
                    Node(
                        tag = "query",
                        content = listOf(Node(tag = "devices", attrs = mapOf("version" to "2"))),
                    ),
                    Node(tag = "list", content = userNodes),
                ),
            )
        ),
    )
}

/**
 * Build a media_conn query IQ to obtain the upload auth token + hosts.
 * Ref whatsmeow mediaconn.go queryMediaConn: <iq xmlns=w:m type=set><media_conn/>.
 */
fun WhatsAppProtocol.buildMediaConnQuery(id: String): Node {
    return Node(
        tag = "iq",
        attrs = mapOf("id" to id, "type" to "set", "xmlns" to "w:m", "to" to "s.whatsapp.net"),
        content = listOf(Node(tag = "media_conn")),
    )
}

/**
 * Build a MEX (`w:mex`) query IQ carrying a persisted-query GraphQL envelope. Ref w2.md §5.2
 * (SMAX `QueryRequest.kt`): `<iq xmlns="w:mex" to="s.whatsapp.net" type=<type> id=…>` with a
 * single `<query query_id="<docId>"[ argo_mode=…]>` child whose text/data node is the JSON
 * envelope `{"queryId":"<docId>","variables":{…}}` (built JVM-pure by
 * [com.vayunmathur.communicate.data.whatsapp.mex.MexEnvelope.buildQueryJson]).
 *
 * The envelope is attached as the `<query>` node's UTF-8 [Node.data] (like
 * [buildGroupInfoChange]); `data` and `content` are mutually exclusive. [argoMode] is omitted
 * when null (we send the plaintext JSON `<result>` envelope rather than Argo binary).
 *
 * @param docId the resolved 17-digit persisted-query id (also embedded inside [queryJson]).
 * @param queryJson the full `{"queryId":…,"variables":…}` envelope string.
 */
fun WhatsAppProtocol.buildMexQuery(
    docId: String,
    queryJson: String,
    id: String,
    type: String = "get",
    argoMode: Long? = null,
): Node {
    val queryAttrs = mutableMapOf("query_id" to docId)
    if (argoMode != null) queryAttrs["argo_mode"] = argoMode.toString()
    return Node(
        tag = "iq",
        attrs = mapOf("id" to id, "type" to type, "xmlns" to "w:mex", "to" to "s.whatsapp.net"),
        content = listOf(
            Node(tag = "query", attrs = queryAttrs, data = queryJson.toByteArray(Charsets.UTF_8)),
        ),
    )
}
/**
 * Build a reaction as an E2E [Message] proto (ReactionMessage). Returned as a proto — NOT a
 * wire node — so the caller sends it through the normal Signal encryption + multi-device
 * fan-out path (buildEncryptedMessageNode / buildEncryptedGroupMessageNode) like any other
 * message. The reaction key must point at the TARGET message: [targetFromMe] reflects whether
 * the target was sent by us, and for group targets from someone else [targetSenderJid] is that
 * sender's JID (participant). Ref whatsmeow send.go BuildReaction + BuildMessageKey.
 * An empty [emoji] removes the reaction.
 */
fun WhatsAppProtocol.buildReactionProto(
    chatJid: String,
    targetMessageId: String,
    emoji: String,
    targetFromMe: Boolean,
    targetSenderJid: String?,
): WhatsAppE2EProto.Message {
    val messageKey = WhatsAppE2EProto.MessageKey.newBuilder()
        .setFromMe(targetFromMe)
        .setId(targetMessageId)
        .setRemoteJid(chatJid)
    if (!targetFromMe && chatJid.contains("@g.us") && !targetSenderJid.isNullOrEmpty()) {
        messageKey.setParticipant(targetSenderJid)
    }

    val reactionMessage = WhatsAppE2EProto.ReactionMessage.newBuilder()
        .setKey(messageKey.build())
        .setText(emoji)
        .setSenderTimestampMs(System.currentTimeMillis())
        .build()

    return WhatsAppE2EProto.Message.newBuilder()
        .setReactionMessage(reactionMessage)
        .build()
}

/**
 * Build a receipt node with configurable type.
 * Supports: "read", "read-self", "played", "" (delivery), "inactive"
 * From whatsmeow/receipt.go
 */
fun WhatsAppProtocol.buildReceipt(
    chatJid: String,
    messageIds: List<String>,
    receiptType: String,
    senderJid: String? = null,
): Node {
    if (messageIds.isEmpty()) throw IllegalArgumentException("No message IDs")

    val attrs = mutableMapOf(
        "id" to messageIds.first(),
        "to" to chatJid
    )
    if (receiptType.isNotEmpty()) {
        attrs["type"] = receiptType
    }
    if (senderJid != null && chatJid.contains("@g.us")) {
        attrs["participant"] = senderJid
    }

    val children = mutableListOf<Node>()
    if (messageIds.size > 1) {
        val items = messageIds.drop(1).map { id ->
            Node(tag = "item", attrs = mapOf("id" to id))
        }
        children.add(Node(tag = "list", content = items))
    }

    return Node(tag = "receipt", attrs = attrs, content = children)
}

/**
 * Build a media message node.
 * From whatsmeow/send.go + upload.go
 */
fun WhatsAppProtocol.buildMediaProto(
    url: String,
    directPath: String,
    mediaKey: ByteArray,
    fileSha256: ByteArray,
    fileEncSha256: ByteArray,
    fileLength: Long,
    mimeType: String,
    caption: String?,
    mediaType: String, // "image", "video", "audio", "document", "sticker"
): WhatsAppE2EProto.Message {
    val e2eBuilder = WhatsAppE2EProto.Message.newBuilder()

    when (mediaType) {
        "image" -> e2eBuilder.setImageMessage(
            buildImageMedia(url, directPath, mediaKey, fileSha256, fileEncSha256, fileLength, mimeType, caption))
        "video" -> e2eBuilder.setVideoMessage(
            buildVideoMedia(url, directPath, mediaKey, fileSha256, fileEncSha256, fileLength, mimeType, caption))
        "audio" -> e2eBuilder.setAudioMessage(
            buildAudioMedia(url, directPath, mediaKey, fileSha256, fileEncSha256, fileLength, mimeType))
        "document" -> e2eBuilder.setDocumentMessage(
            buildDocumentMedia(url, directPath, mediaKey, fileSha256, fileEncSha256, fileLength, mimeType))
        "sticker" -> e2eBuilder.setStickerMessage(
            buildStickerMedia(url, directPath, mediaKey, fileSha256, fileEncSha256, fileLength, mimeType))
    }

    val plaintext = e2eBuilder.build()
    return plaintext
}

/** Image media message. */
private fun buildImageMedia(
    url: String,
    directPath: String,
    mediaKey: ByteArray,
    fileSha256: ByteArray,
    fileEncSha256: ByteArray,
    fileLength: Long,
    mimeType: String,
    caption: String?,
): WhatsAppE2EProto.ImageMessage {
    val imgBuilder = WhatsAppE2EProto.ImageMessage.newBuilder()
        .setUrl(url)
        .setDirectPath(directPath)
        .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
        .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
        .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
        .setFileLength(fileLength.toULong().toLong())
        .setMimetype(mimeType)
    if (caption != null) imgBuilder.setCaption(caption)
    return imgBuilder.build()
}

/** Video media message. */
private fun buildVideoMedia(
    url: String,
    directPath: String,
    mediaKey: ByteArray,
    fileSha256: ByteArray,
    fileEncSha256: ByteArray,
    fileLength: Long,
    mimeType: String,
    caption: String?,
): WhatsAppE2EProto.VideoMessage {
    val vidBuilder = WhatsAppE2EProto.VideoMessage.newBuilder()
        .setUrl(url)
        .setDirectPath(directPath)
        .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
        .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
        .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
        .setFileLength(fileLength.toULong().toLong())
        .setMimetype(mimeType)
    if (caption != null) vidBuilder.setCaption(caption)
    return vidBuilder.build()
}

/** Audio media message. */
private fun buildAudioMedia(
    url: String,
    directPath: String,
    mediaKey: ByteArray,
    fileSha256: ByteArray,
    fileEncSha256: ByteArray,
    fileLength: Long,
    mimeType: String,
): WhatsAppE2EProto.AudioMessage {
    val audBuilder = WhatsAppE2EProto.AudioMessage.newBuilder()
        .setUrl(url)
        .setDirectPath(directPath)
        .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
        .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
        .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
        .setFileLength(fileLength.toULong().toLong())
        .setMimetype(mimeType)
    return audBuilder.build()
}

/** Document media message. */
private fun buildDocumentMedia(
    url: String,
    directPath: String,
    mediaKey: ByteArray,
    fileSha256: ByteArray,
    fileEncSha256: ByteArray,
    fileLength: Long,
    mimeType: String,
): WhatsAppE2EProto.DocumentMessage {
    val docBuilder =
        WhatsAppE2EProto.DocumentMessage.newBuilder()
        .setUrl(url)
        .setDirectPath(directPath)
        .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
        .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
        .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
        .setFileLength(fileLength.toULong().toLong())
        .setMimetype(mimeType)
    return docBuilder.build()
}

/** Sticker media message. */
private fun buildStickerMedia(
    url: String,
    directPath: String,
    mediaKey: ByteArray,
    fileSha256: ByteArray,
    fileEncSha256: ByteArray,
    fileLength: Long,
    mimeType: String,
): WhatsAppE2EProto.StickerMessage {
    val stickerBuilder =
        WhatsAppE2EProto.StickerMessage.newBuilder()
        .setUrl(url)
        .setDirectPath(directPath)
        .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
        .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
        .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
        .setFileLength(fileLength.toULong().toLong())
        .setMimetype(mimeType)
    return stickerBuilder.build()
}

/**
 * Build a read receipt node.
 * From whatsmeow/receipt.go MarkRead()
 */
fun WhatsAppProtocol.buildReadReceipt(
    chatJid: String,
    messageIds: List<String>,
    senderJid: String? = null,
    timestamp: Long = System.currentTimeMillis() / MS_PER_SECOND,
): Node {
    if (messageIds.isEmpty()) throw IllegalArgumentException("No message IDs")

    val attrs = mutableMapOf(
        "id" to messageIds.first(),
        "type" to "read",
        "to" to chatJid,
        "t" to timestamp.toString()
    )
    if (senderJid != null && chatJid.contains("@g.us")) {
        attrs["participant"] = senderJid
    }

    val children = mutableListOf<Node>()
    if (messageIds.size > 1) {
        val items = messageIds.drop(1).map { id ->
            Node(tag = "item", attrs = mapOf("id" to id))
        }
        children.add(Node(tag = "list", content = items))
    }

    return Node(tag = "receipt", attrs = attrs, content = children)
}
