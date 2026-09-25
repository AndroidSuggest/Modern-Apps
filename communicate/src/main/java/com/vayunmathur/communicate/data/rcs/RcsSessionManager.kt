package com.vayunmathur.communicate.data.rcs

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CPM session manager: INVITE/MSRP 1:1 dialogs + group conference dialogs.
 *
 * Mirrors TestRcsApp's `MinimalCpmChatService` (transaction → session routing,
 * dialog cache for in-dialog requests) without the jain-sip/Guava stack:
 * SIP construction is string-based from the delegate configuration, sends go
 * through [RcsSipTransport.sendSipMessage].
 *
 * v1 scope: outgoing 1:1 INVITE with SDP-for-MSRP offer, incoming INVITE auto
 * handling (200 OK + local SDP or 488), BYE teardown, and group conference
 * INVITE to the focus URI. MSRP media itself rides the negotiated socket
 * (see [RcsMsrp]); text falls back to pager-mode CPIM until the session is
 * established, exactly like ChatManager's session-init gate.
 */
object RcsSessionManager {
    private const val TAG = "RcsSession"

    private val _sessions = MutableStateFlow<Map<String, RcsSession>>(emptyMap())
    val sessions: StateFlow<Map<String, RcsSession>> = _sessions.asStateFlow()

    /** Transaction id (Via branch) → session, for response routing. */
    private val transactions = ConcurrentHashMap<String, String>()

    /** Typing state per conversation: conversationId → (active, updatedAt). */
    private val _typing = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val typing: StateFlow<Map<String, Boolean>> = _typing.asStateFlow()

    /** Active session for [conversationId], or null. */
    fun sessionFor(conversationId: String): RcsSession? = _sessions.value[conversationId]

    /**
     * Start an outgoing 1:1 CPM session: INVITE with SDP-for-MSRP offer to the
     * `tel:` URI. Returns the dialog id on accept-tracked send, null when the
     * transport is unavailable. The 200 OK/ACK/MSRP bind completes
     * asynchronously via [onSipResponse]/[onSipRequest].
     */
    suspend fun startSession(remoteTelUri: String, conversationId: String): String? {
        if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return null
        val callId = "${UUID.randomUUID()}@rcs"
        val localTag = UUID.randomUUID().toString().take(8)
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val (startLine, headers, content) = buildInvite(remoteTelUri, callId, localTag, branch, conversationId)
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, content)
        if (!ok) return null
        val dialogId = "$callId:$localTag"
        transactions[branch] = dialogId
        _sessions.value = _sessions.value + (conversationId to RcsSession(
            dialogId = dialogId,
            callId = callId,
            localTag = localTag,
            remoteTag = "",
            remoteUri = remoteTelUri,
            conversationId = conversationId,
        ))
        return dialogId
    }

    /**
     * Start a group conference session: INVITE to the conference focus URI
     * with `Conversation-ID` + subject. Members join via the focus; per-UP the
     * focus own the participant list (no re-INVITE fan-out from us).
     */
    suspend fun startGroupSession(
        focusUri: String,
        subject: String,
        conversationId: String,
    ): String? {
        if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return null
        val callId = "${UUID.randomUUID()}@rcs"
        val localTag = UUID.randomUUID().toString().take(8)
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val (startLine, headers, content) = buildInvite(focusUri, callId, localTag, branch, conversationId, subject)
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, content)
        if (!ok) return null
        val dialogId = "$callId:$localTag"
        transactions[branch] = dialogId
        _sessions.value = _sessions.value + (conversationId to RcsSession(
            dialogId = dialogId,
            callId = callId,
            localTag = localTag,
            remoteTag = "",
            remoteUri = focusUri,
            conversationId = conversationId,
            isGroup = true,
        ))
        return dialogId
    }

    /** Tear down the session for [conversationId] with BYE. */
    suspend fun terminateSession(conversationId: String): Boolean {
        val session = _sessions.value[conversationId] ?: return true
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val startLine = "BYE sip:${session.remoteUri} SIP/2.0"
        val headers = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("Call-ID: ${session.callId}\r\n")
            append("CSeq: 2 BYE\r\n")
            append("Content-Length: 0\r\n")
        }
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, ByteArray(0))
        transactions.remove(branch)
        _sessions.value = _sessions.value - conversationId
        return ok
    }

    /**
     * Route an inbound SIP response (from the delegate message callback) to
     * its session: 200 OK to INVITE completes the dialog (remote tag + SDP
     * answer), other finals tear the pending session down.
     */
    fun onSipResponse(statusCode: Int, callId: String, remoteTag: String?, sdpAnswer: String?) {
        val entry = _sessions.value.entries.firstOrNull { it.value.callId == callId } ?: return
        val session = entry.value
        if (statusCode in 200..299 && !remoteTag.isNullOrBlank()) {
            val (localPath, remotePath) = parseSdpPaths(sdpAnswer)
            val setup = parseSdpSetup(sdpAnswer)
            _sessions.value = _sessions.value + (entry.key to session.copy(
                remoteTag = remoteTag,
                msrpLocalPath = localPath,
                msrpRemotePath = remotePath,
                // ACTIVE-only: we connect out. A peer answering `active` means
                // it expects US to listen, which we cannot — keep the remote
                // path nulled so sends stay on pager-mode.
                msrpSetup = setup,
            ))
            val usable = remotePath != null && setup != MsrpSetup.ACTIVE
            Log.i(TAG, "Session established ${session.dialogId} setup=$setup usable=$usable")
            if (!usable) {
                _sessions.value = _sessions.value + (entry.key to
                    (_sessions.value[entry.key] ?: session).copy(msrpRemotePath = null))
            }
        } else if (statusCode >= 300) {
            Log.w(TAG, "Session failed $callId code=$statusCode")
            transactions.entries.removeIf { it.value == session.dialogId }
            _sessions.value = _sessions.value - entry.key
        }
    }

    /**
     * Route an inbound SIP request: INVITE starts an incoming session entry
     * (caller answers via [acceptIncoming]), BYE tears down, MESSAGE with
     * `message/imdn+xml` updates receipts, `im-iscomposing` updates typing.
     */
    fun onSipRequest(
        method: String,
        callId: String,
        fromUri: String,
        contentType: String?,
        body: String,
    ): RcsSession? {
        return when (method.uppercase()) {
            "INVITE" -> {
                val conversationId = fromUri.substringAfter("sip:").substringBefore("@")
                    .takeIf { it.isNotBlank() } ?: fromUri
                val session = RcsSession(
                    dialogId = "$callId:in",
                    callId = callId,
                    localTag = UUID.randomUUID().toString().take(8),
                    remoteTag = "",
                    remoteUri = fromUri,
                    conversationId = conversationId,
                )
                _sessions.value = _sessions.value + (conversationId to session)
                session
            }
            "BYE" -> {
                val entry = _sessions.value.entries.firstOrNull { it.value.callId == callId }
                if (entry != null) _sessions.value = _sessions.value - entry.key
                null
            }
            "MESSAGE" -> {
                val conversationId = fromUri.substringAfter("sip:").substringBefore("@")
                if (contentType?.contains("imdn", ignoreCase = true) == true) {
                    RcsImdn.onReportReceived(body)
                } else if (contentType?.contains("im-composing", ignoreCase = true) == true ||
                    parseIsComposingBody(body) != null
                ) {
                    val active = parseIsComposingBody(body) ?: false
                    _typing.value = _typing.value + (conversationId to active)
                }
                null
            }
            else -> null
        }
    }

    /**
     * Build an INVITE with SDP-for-MSRP offer (TestRcsApp SipUtils.buildInvite
     * header set, string-built): From (P-Preferred-Identity), To, Via,
     * Max-Forwards, Call-ID, CSeq, Contact (+sip.instance + CPM tag),
     * Conversation-ID, Contribution-ID, Accept-Contact (CPM session),
     * P-Preferred-Service (CPM URN), Route (Service-Route), User-Agent,
     * P-Access-Network-Info when configured.
     */
    fun buildInvite(
        targetUri: String,
        callId: String,
        localTag: String,
        branch: String,
        conversationId: String,
        subject: String? = null,
    ): Triple<String, String, ByteArray> {
        val cfg = RcsSipTransport.lastConfigSnapshot()
        val from = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
        val sdp = buildSdpOffer(cfg?.msrpLocalIp)
        val bytes = sdp.toByteArray(Charsets.UTF_8)
        val startLine = "INVITE $targetUri SIP/2.0"
        val headers = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <$from>;tag=$localTag\r\n")
            append("To: <$targetUri>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: 1 INVITE\r\n")
            append("Contact: <$from>;+sip.instance=\"<urn:gsma:imei:${cfg?.imei ?: "unknown"}>\";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"\r\n")
            append("Conversation-ID: $conversationId\r\n")
            append("Contribution-ID: ${UUID.randomUUID()}\r\n")
            append("Accept-Contact: *;+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"\r\n")
            append("P-Preferred-Identity: <$from>\r\n")
            append("P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.oma.cpm.session\r\n")
            if (subject != null) append("Subject: $subject\r\n")
            cfg?.serviceRoute?.takeIf { it.isNotBlank() }?.let { append("Route: <$it>\r\n") }
            cfg?.pani?.takeIf { it.isNotBlank() }?.let { append("P-Access-Network-Info: $it\r\n") }
            append("User-Agent: ${cfg?.userAgent ?: "Communicate-RCS/1.0"}\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${bytes.size}\r\n")
        }
        return Triple(startLine, headers, bytes)
    }

    private fun buildSdpOffer(localIp: String?): String {
        val ip = localIp?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
        val path = "msrp://${UUID.randomUUID()}.invalid:2855/${UUID.randomUUID()};tcp"
        return buildString {
            append("v=0\r\n")
            append("o=- ${System.currentTimeMillis()} ${System.currentTimeMillis()} IN IP4 $ip\r\n")
            append("s=-\r\n")
            append("c=IN IP4 $ip\r\n")
            append("t=0 0\r\n")
            append("m=message 2855 TCP/MSRP *\r\n")
            append("a=path:$path\r\n")
            append("a=accept-types:message/cpim text/plain\r\n")
            append("a=setup:active\r\n")
        }
    }

    private fun parseSdpPaths(sdp: String?): Pair<String?, String?> {
        if (sdp.isNullOrBlank()) return null to null
        val path = Regex("a=path:(\\S+)", RegexOption.IGNORE_CASE).find(sdp)
            ?.groupValues?.getOrNull(1)?.trim()
        return null to path
    }

    /**
     * Parse the MSRP `a=setup:` role from an SDP answer. Null when absent
     * (assume peer is passive, the common answer to our active offer).
     */
    fun parseSdpSetup(sdp: String?): MsrpSetup? {
        if (sdp.isNullOrBlank()) return null
        val token = Regex("a=setup:(\\S+)", RegexOption.IGNORE_CASE).find(sdp)
            ?.groupValues?.getOrNull(1)?.trim()?.lowercase() ?: return null
        return when (token) {
            "active" -> MsrpSetup.ACTIVE
            "passive" -> MsrpSetup.PASSIVE
            "actpass" -> MsrpSetup.ACTPASS
            else -> null
        }
    }
}
