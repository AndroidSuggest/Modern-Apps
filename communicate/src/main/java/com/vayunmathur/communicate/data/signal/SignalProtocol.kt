package com.vayunmathur.communicate.data.signal

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Frame encode/decode and message envelope handling for the Signal primary client.
 *
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol] for Signal:
 *  - Signal WebSocket frames are JSON text `{type, verb, path, body, id}` or binary
 *    sealed-sender envelopes. This object handles both directions.
 *  - E2E framing (sealed sender + Signal envelope) is delegated to the Rust crate
 *    (which already implements X3DH + Double Ratchet + XEdDSA) via
 *    [com.vayunmathur.communicate.data.signal.e2e.RustSignalCrypto].
 *
 * Real Signal protobufs (`SignalServiceProtos.Envelope`, `DataMessage`, `Content`)
 * would be generated from `.proto` files; in this repo we carry the envelope as JSON
 * plus base64 bodies so the processor can stay agnostic. When real protos are added,
 * replace [SignalEnvelope] with the generated class — call sites are isolated here.
 */
object SignalProtocol {
    private const val TAG = "SignalProtocol"

    const val WS_TYPE_REQUEST = "REQUEST"
    const val WS_TYPE_RESPONSE = "RESPONSE"
    const val WS_TYPE_MESSAGE = "MESSAGE"

    data class SignalEnvelope(
        val type: String,
        val sourceAci: String,
        val sourceDevice: Int,
        val timestamp: Long,
        /** Sealed-sender ciphertext bytes (or plaintext JSON when not yet E2E). */
        val content: ByteArray,
        val serverGuid: String? = null,
        val isGroup: Boolean = false,
        val groupId: String? = null,
    )

    data class WsFrame(
        val type: String,
        val verb: String?,
        val path: String?,
        val id: String?,
        val status: Int?,
        val body: ByteArray?,
        val raw: String,
    )

    fun parseWsFrame(text: String): WsFrame? {
        return try {
            val obj = JSONObject(text)
            val type = obj.optString("type", "")
            val bodyB64 = obj.optString("body", "")
            val body = if (bodyB64.isNotEmpty()) runCatching { Base64.decode(bodyB64, Base64.NO_WRAP) }.getOrNull() else null
            WsFrame(
                type = type,
                verb = if (obj.has("verb")) obj.optString("verb") else null,
                path = if (obj.has("path")) obj.optString("path") else null,
                id = if (obj.has("id")) obj.optString("id") else null,
                status = if (obj.has("status")) obj.optInt("status") else null,
                body = body,
                raw = text,
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseWsFrame failed: ${e.message}")
            null
        }
    }

    fun buildWsResponse(id: String, status: Int = 200): String {
        val o = JSONObject()
        o.put("type", WS_TYPE_RESPONSE)
        o.put("id", id)
        o.put("status", status)
        return o.toString()
    }

    /**
     * Try to decode [frame] as an inbound Signal envelope.
     * Returns null for non-message frames (keepalive, 403, etc.).
     *
     * Path conventions (Signal service):
     *  - `PUT /api/v1/message/{aci}` — inbound sealed message
     *  - `PUT /api/v1/queue/empty`    — queue drain signal
     */
    fun tryParseEnvelope(frame: WsFrame): SignalEnvelope? {
        if (frame.type != WS_TYPE_REQUEST) return null
        val path = frame.path ?: return null
        if (!path.contains("/api/v1/message") && !path.contains("/api/v1/queue")) return null
        val body = frame.body ?: return null
        // Body is JSON with source/timestamp/content or a binary envelope.
        // Try JSON first; fall back to raw bytes as sealed content.
        return try {
            val json = JSONObject(String(body, Charsets.UTF_8))
            SignalEnvelope(
                type = json.optString("type", "CIPHERTEXT"),
                sourceAci = json.optString("source", json.optString("sourceAci", "")),
                sourceDevice = json.optInt("sourceDevice", 1),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                content = json.optString("content", "").let {
                    if (it.isNotEmpty()) runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrDefault(it.toByteArray()) else body
                },
                serverGuid = if (json.has("serverGuid")) json.optString("serverGuid") else null,
                isGroup = json.optBoolean("isGroup", false),
                groupId = if (json.has("groupId")) json.optString("groupId") else null,
            )
        } catch (_: Exception) {
            SignalEnvelope(
                type = "CIPHERTEXT",
                sourceAci = "",
                sourceDevice = 0,
                timestamp = System.currentTimeMillis(),
                content = body,
            )
        }
    }

    fun generateMessageId(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun toConversationId(sourceAci: String, groupId: String?): String {
        return when {
            !groupId.isNullOrEmpty() -> "group:$groupId"
            sourceAci.isNotEmpty() -> sourceAci
            else -> "unknown"
        }
    }
}
