package com.vayunmathur.communicate.data.signal.transport

import android.os.Build
import com.vayunmathur.communicate.data.signal.SignalAuthData
import org.json.JSONObject

/**
 * Envelope/payload builder for the Signal primary client.
 *
 * Mirrors `data/whatsapp/transport/PrimaryClientPayload.kt` but for Signal's
 * WebSocket+REST registration. The wire format here is JSON (Signal's service
 * speaks JSON/protobuf over WS); the helper keeps construction in one place so
 * [com.vayunmathur.communicate.data.signal.SignalProtocol] stays transport-only.
 *
 * Two surfaces:
 *  - [buildWebSocketRequest] — WS envelope `{type, verb, path, body, id}`
 *  - [buildDataMessage] / [buildRegistrationPayload] — app-layer payloads
 */
object SignalPayload {

    /**
     * Build a Signal WebSocket REQUEST envelope (JSON text frame).
     * Signal WS: `{type:"REQUEST", verb:"PUT", path:"/api/v1/message/…", body: base64, id: uuid}`
     */
    fun buildWebSocketRequest(
        verb: String,
        path: String,
        body: ByteArray? = null,
        id: String = java.util.UUID.randomUUID().toString(),
    ): String {
        val obj = JSONObject()
        obj.put("type", "REQUEST")
        obj.put("verb", verb)
        obj.put("path", path)
        obj.put("id", id)
        if (body != null && body.isNotEmpty()) {
            obj.put("body", android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP))
        }
        return obj.toString()
    }

    fun buildWebSocketResponse(
        id: String,
        status: Int = 200,
        body: ByteArray? = null,
    ): String {
        val obj = JSONObject()
        obj.put("type", "RESPONSE")
        obj.put("status", status)
        obj.put("id", id)
        if (body != null) obj.put("body", android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP))
        return obj.toString()
    }

    /**
     * Build the JSON for a Signal DataMessage (text + optional sealed-sender fields).
     * This is the plaintext that gets E2E-encrypted before WS send.
     */
    fun buildDataMessage(
        body: String,
        timestamp: Long = System.currentTimeMillis(),
        groupId: ByteArray? = null,
        quoteId: Long? = null,
    ): JSONObject {
        val data = JSONObject()
        data.put("body", body)
        data.put("timestamp", timestamp)
        if (groupId != null) data.put("groupId", android.util.Base64.encodeToString(groupId, android.util.Base64.NO_WRAP))
        if (quoteId != null) data.put("quoteId", quoteId)
        return data
    }

    /**
     * Registration account attributes payload (fetched on device creation).
     * Mirrors the `AccountAttributes` JSON Signal expects on `PUT /v1/accounts/attributes/`.
     */
    fun buildAccountAttributes(
        auth: SignalAuthData,
        fetchesMessages: Boolean = true,
        registrationLock: String? = null,
    ): String {
        val obj = JSONObject()
        obj.put("fetchesMessages", fetchesMessages)
        obj.put("registrationId", auth.registrationId)
        obj.put("name", auth.profileName)
        if (registrationLock != null) obj.put("pin", registrationLock)
        // Signal capabilities
        obj.put("capabilities", JSONObject().apply {
            put("announcementGroup", true)
            put("senderKey", true)
            put("storage", true)
        })
        return obj.toString()
    }

    /**
     * Device capabilities + version info for the WS hello.
     * Included as UA header rather than body, but kept here for parity with WhatsApp's payload builder.
     */
    fun userAgent(): String {
        val device = "${Build.MANUFACTURER}-${Build.MODEL}".replace(' ', '_')
        return "Signal-Android 7.20.0 Android/${Build.VERSION.RELEASE} Device/$device"
    }
}
