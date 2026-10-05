package com.vayunmathur.communicate.data.signal.transport

import com.google.protobuf.ByteString
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage

/**
 * WebSocket framing builders for [SignalPayload] (split for file length).
 * Extension functions on [SignalPayload]; behavior identical, call sites unchanged.
 */

fun SignalPayload.buildWebSocketRequestMessage(
    verb: String,
    path: String,
    body: ByteArray? = null,
    id: Long = nextRequestId(),
    headers: List<String> = emptyList(),
): WebSocketRequestMessage {
    val b = WebSocketRequestMessage.newBuilder()
        .setVerb(verb)
        .setPath(path)
        .setId(id)
    if (body != null && body.isNotEmpty()) b.setBody(ByteString.copyFrom(body))
    if (headers.isNotEmpty()) b.addAllHeaders(headers)
    return b.build()
}

fun SignalPayload.buildWebSocketResponseMessage(
    id: Long,
    status: Int = 200,
    message: String = "OK",
    body: ByteArray? = null,
    headers: List<String> = emptyList(),
): WebSocketResponseMessage {
    val b = WebSocketResponseMessage.newBuilder()
        .setId(id)
        .setStatus(status)
        .setMessage(message)
    if (body != null && body.isNotEmpty()) b.setBody(ByteString.copyFrom(body))
    if (headers.isNotEmpty()) b.addAllHeaders(headers)
    return b.build()
}

fun SignalPayload.buildWebSocketMessageForRequest(request: WebSocketRequestMessage): WebSocketMessage =
    WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.REQUEST).setRequest(request).build()

fun SignalPayload.buildWebSocketMessageForResponse(response: WebSocketResponseMessage): WebSocketMessage =
    WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.RESPONSE).setResponse(response).build()

fun SignalPayload.encodeWebSocketRequest(
    verb: String,
    path: String,
    body: ByteArray? = null,
    id: Long = nextRequestId(),
    headers: List<String> = emptyList(),
): ByteArray = buildWebSocketMessageForRequest(buildWebSocketRequestMessage(
    verb,
    path,
    body,
    id,
    headers)).toByteArray()

fun SignalPayload.encodeWebSocketResponse(
    id: Long,
    status: Int = 200,
    message: String = "OK",
    body: ByteArray? = null,
): ByteArray = buildWebSocketMessageForResponse(buildWebSocketResponseMessage(
    id,
    status,
    message,
    body,
)).toByteArray()

/** Apply the GroupContextV2 block when any group field is present. */
internal fun SignalPayload.applyGroupV2(
    b: SignalServiceProtos.DataMessage.Builder,
    groupV2MasterKey: ByteArray?,
    groupV2Revision: Int?,
    groupV2Change: ByteArray?,
) {
    if (groupV2MasterKey != null || groupV2Revision != null || groupV2Change != null) {
        val g = SignalServiceProtos.GroupContextV2.newBuilder()
        if (groupV2MasterKey != null) g.setMasterKey(ByteString.copyFrom(groupV2MasterKey))
        if (groupV2Revision != null) g.setRevision(groupV2Revision)
        if (groupV2Change != null) g.setGroupChange(ByteString.copyFrom(groupV2Change))
        b.setGroupV2(g)
    }
}

/** Apply the optional DataMessage fields. */
internal fun SignalPayload.applyDataOptionals(
    b: SignalServiceProtos.DataMessage.Builder,
    bodyRanges: List<SignalServiceProtos.BodyRange>,
    attachments: List<SignalServiceProtos.AttachmentPointer>,
    quote: SignalServiceProtos.DataMessage.Quote?,
    reaction: SignalServiceProtos.DataMessage.Reaction?,
    delete: SignalServiceProtos.DataMessage.Delete?,
    pollCreate: SignalServiceProtos.DataMessage.PollCreate?,
    pollVote: SignalServiceProtos.DataMessage.PollVote?,
    pollTerminate: SignalServiceProtos.DataMessage.PollTerminate?,
    expireTimer: Int?,
    profileKey: ByteArray?,
    contact: SignalServiceProtos.DataMessage.Contact?,
) {
    if (bodyRanges.isNotEmpty()) b.addAllBodyRanges(bodyRanges)
    if (attachments.isNotEmpty()) b.addAllAttachments(attachments)
    if (quote != null) b.setQuote(quote)
    if (reaction != null) b.setReaction(reaction)
    if (delete != null) b.setDelete(delete)
    if (pollCreate != null) b.setPollCreate(pollCreate)
    if (pollVote != null) b.setPollVote(pollVote)
    if (pollTerminate != null) b.setPollTerminate(pollTerminate)
    if (expireTimer != null) b.setExpireTimer(expireTimer)
    if (profileKey != null) b.setProfileKey(ByteString.copyFrom(profileKey))
    if (contact != null) b.addContact(contact)
}

fun SignalPayload.nextRequestId(): Long =
    (secureRandom.nextLong() and Long.MAX_VALUE).let { if (it == 0L) 1L else it }
