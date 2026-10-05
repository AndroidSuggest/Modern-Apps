package com.vayunmathur.communicate.data.signal

import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketResponseMessage

/**
 * Binary WebSocket framing helpers for [SignalProtocol] (split for file length).
 * Extension functions on [SignalProtocol]; behavior identical, call sites unchanged.
 */

internal fun SignalProtocol.parseWebSocketMessage(bytes: ByteArray): WebSocketMessage? = try {
    WebSocketMessage.parseFrom(bytes)
} catch (e: InvalidProtocolBufferException) {
    Log.w(SignalProtocol.TAG, "parseWebSocketMessage failed: ${e.message}")
    null
}

internal fun SignalProtocol.encodeWebSocketRequest(request: WebSocketRequestMessage): ByteArray =
    WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.REQUEST).setRequest(request).build().toByteArray()

internal fun SignalProtocol.encodeWebSocketResponse(response: WebSocketResponseMessage): ByteArray =
    WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.RESPONSE).setResponse(response)
        .build().toByteArray()

internal fun SignalProtocol.buildWsResponseProto(
    id: Long,
    status: Int = 200,
    message: String = "OK",
): WebSocketResponseMessage =
    WebSocketResponseMessage.newBuilder().setId(id).setStatus(status).setMessage(message).build()

internal fun SignalProtocol.encodeWsAck(id: Long, status: Int = 200): ByteArray =
    encodeWebSocketResponse(buildWsResponseProto(id, status))

internal fun SignalProtocol.buildWsRequest(
    verb: String,
    path: String,
    body: ByteArray? = null,
    id: Long = nextRequestId(),
    headers: List<String> = emptyList(),
): WebSocketRequestMessage {
    val b = WebSocketRequestMessage.newBuilder().setVerb(verb).setPath(path).setId(id)
    if (body != null && body.isNotEmpty()) b.setBody(ByteString.copyFrom(body))
    if (headers.isNotEmpty()) b.addAllHeaders(headers)
    return b.build()
}

internal fun SignalProtocol.nextRequestId(): Long =
    (java.security.SecureRandom().nextLong() and Long.MAX_VALUE).let { if (it == 0L) 1L else it }

internal fun SignalProtocol.tryParseEnvelopeFromWsBytes(wsBytes: ByteArray): SignalProtocol.SignalEnvelope? {
    val wsMessage = parseWebSocketMessage(wsBytes) ?: return null
    if (wsMessage.type != WebSocketMessage.Type.REQUEST) return null
    if (!wsMessage.hasRequest()) return null
    val req = wsMessage.request
    val path = if (req.hasPath()) req.path else ""
    if (isIgnorableRequest(req, path)) return null
    val envelope = parseEnvelopeFromRequest(req) ?: return null
    return toSignalEnvelope(envelope)
}

/** True for queue-empty, keepalive, and bodyless non-message requests. */
private fun isIgnorableRequest(req: WebSocketRequestMessage, path: String): Boolean {
    val isMessage = path.contains("/api/v1/message") || path.contains("/v1/messages")
    val isQueueEmpty = path.contains("/api/v1/queue/empty") || path.contains("/v1/queue/empty")
    if (isQueueEmpty) return true
    if (!isMessage && (path.contains("keepalive") || !req.hasBody())) return true
    return false
}

internal fun SignalProtocol.getRequestId(wsBytes: ByteArray): Long? {
    val ws = parseWebSocketMessage(wsBytes) ?: return null
    return if (ws.hasRequest() && ws.request.hasId()) ws.request.id else null
}

internal fun SignalProtocol.getRequestId(wsMessage: WebSocketMessage): Long? =
    if (wsMessage.hasRequest() && wsMessage.request.hasId()) wsMessage.request.id else null

internal fun SignalProtocol.isQueueEmptySignal(wsBytes: ByteArray): Boolean {
    val ws = parseWebSocketMessage(wsBytes) ?: return false
    if (!ws.hasRequest()) return false
    val path = if (ws.request.hasPath()) ws.request.path else return false
    return path.contains("queue/empty")
}

fun SignalProtocol.groupConversationId(groupIdentifier: ByteArray): String =
    GROUP_PREFIX + groupIdentifier.joinToString("") { "%02x".format(it) }

/** The raw 32-byte group identifier for a `group:<hex>` conversation id, or null. */
fun SignalProtocol.groupIdentifierOf(conversationId: String): ByteArray? {
    if (!isGroupConversation(conversationId)) return null
    return SignalGroups.hexToBytes(conversationId.removePrefix(GROUP_PREFIX))
}

/** Source ACI from binary or string service id. */
internal fun SignalProtocol.sourceAciFor(envelope: SignalServiceProtos.Envelope): String = when {
    envelope.hasSourceServiceIdBinary() -> {
        bytesToAciString(envelope.sourceServiceIdBinary.toByteArray())
    }
    envelope.hasSourceServiceId() -> envelope.sourceServiceId
    else -> ""
}

/** Destination ACI from binary or string service id. */
internal fun SignalProtocol.destAciFor(envelope: SignalServiceProtos.Envelope): String? = when {
    envelope.hasDestinationServiceIdBinary() -> {
        bytesToAciString(envelope.destinationServiceIdBinary.toByteArray())
    }
    envelope.hasDestinationServiceId() -> envelope.destinationServiceId
    else -> null
}
