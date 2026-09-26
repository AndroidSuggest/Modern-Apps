package com.vayunmathur.communicate.telephony

import android.telephony.ims.SipMessage
import android.util.Log
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget
import kotlinx.coroutines.launch

/**
 * Dialog-forming/method handlers for inbound SIP, split from
 * [RcsSyncService] for file length. Extension functions on the service so
 * they share its scope, notification channels, and MSRP connection map.
 */

/**
 * Inbound INVITE: stash the offer in the session manager, then accept or
 * decline. Accept when the offer carries an SDP we can answer (any
 * `m=message` section); decline `setup:active`-only offers we cannot
 * satisfy... in practice we always accept at the SIP layer (dialog
 * existence enables in-dialog MESSAGE) and let MSRP usability gate media.
 * Posts a session-invite notification so the user can jump in.
 */
internal suspend fun RcsSyncService.handleInboundInvite(message: SipMessage) {
    if (!RcsFeature.enabled) return
    val headers = message.getHeaderSection()
    val from = headerValue(headers, "From:")?.substringAfter("<")?.substringBefore(">")
        ?.substringAfter("sip:")?.substringBefore("@")?.takeIf { it.isNotBlank() }
        ?: return
    val fromTag = headerValue(headers, "From:")?.substringAfter("tag=", "")?.substringBefore(";")
        ?.trim()?.takeIf { it.isNotBlank() }
    val callId = message.getCallIdParameter() ?: return
    val body = message.getContent().toString(Charsets.UTF_8)
    val to = headerValue(headers, "To:")
    val stashed = RcsSessionManager.onSipRequest("INVITE", callId, from, "application/sdp", body, fromTag, to)
    // Focus join: route to the hosted group conversation, not the sender.
    val conversationId = stashed?.conversationId ?: from
    // 488 only when there is no SDP offer at all; otherwise accept.
    val hasSdp = body.contains("m=message", ignoreCase = true)
    if (!hasSdp) {
        RcsSessionManager.declineIncoming(conversationId)
        return
    }
    if (RcsSessionManager.acceptIncoming(conversationId)) {
        showSessionInviteNotification(conversationId, from)
    }
}

/** Inbound BYE: tear down the session + MSRP connection. */
internal suspend fun RcsSyncService.handleInboundBye(message: SipMessage) {
    if (!RcsFeature.enabled) return
    val callId = message.getCallIdParameter() ?: return
    RcsSessionManager.onSipRequest("BYE", callId, "", null, "")
    // Close connections whose sessions went away.
    closeDeadMsrpConnections()
    Log.i(RcsSyncService.TAG, "BYE processed callId=$callId")
}

/** Close MSRP connections whose sessions went away (+ drop listen paths). */
internal fun RcsSyncService.closeDeadMsrpConnections() {
    msrpConnections.keys.filter { RcsSessionManager.sessionFor(it) == null }.forEach { id ->
        msrpConnections.remove(id)?.close()
        RcsMsrpListen.dropPending(id)
    }
}

/**
 * Passive-side accept: the peer connected to our listen socket. Wrap it
 * as the session's connection (reusing the accepted TCP per RFC 4975
 * §5.4) and feed inbound chunks to the inbox. The session's local path is
 * what we advertised; the remote path is the peer's From-Path from the
 * first SEND.
 */
internal fun RcsSyncService.onMsrpAccepted(
    conversationId: String,
    socket: java.net.Socket,
    head: RcsMsrpListen.AcceptedHead,
) {
    if (!RcsFeature.enabled) {
        runCatching { socket.close() }
        return
    }
    val session = RcsSessionManager.sessionFor(conversationId) ?: run {
        runCatching { socket.close() }
        return
    }
    // Replace any stale connection for this conversation.
    msrpConnections.remove(conversationId)?.close()
    val peerFrom = head.lines.firstOrNull { it.startsWith("From-Path:", ignoreCase = true) }
        ?.substringAfter(":")?.trim().orEmpty()
    val localPath = session.msrpLocalPath ?: head.lines.firstOrNull {
        it.startsWith("To-Path:", ignoreCase = true)
    }?.substringAfter(":")?.trim().orEmpty()
    val conn = RcsMsrp.wrapAccepted(socket, head, localPath, peerFrom) { contentType, body ->
        serviceScope.launch { handleMsrpChunk(conversationId, session, contentType, body) }
    }
    msrpConnections[conversationId] = conn
    Log.i(RcsSyncService.TAG, "Passive MSRP accepted for $conversationId")
}

/**
 * Inbound REFER (UP conference join): the focus (or a peer) asks us to
 * INVITE the conference URI in `Refer-To`. We start an outgoing group
 * session to it, which joins the conference dialog.
 */
internal suspend fun RcsSyncService.handleInboundRefer(message: SipMessage) {
    if (!RcsFeature.enabled) return
    val headers = message.getHeaderSection()
    val referTo = headerValue(headers, "Refer-To:")?.substringAfter("<")?.substringBefore(">")
        ?.takeIf { it.isNotBlank() } ?: return
    val callId = message.getCallIdParameter().orEmpty()
    // Accept the referral, then join.
    sendReferResponse(message, 202, "Accepted")
    val conversationId = "conf:${referTo.hashCode()}"
    RcsSessionManager.startGroupSession(referTo, "", conversationId)
    Log.i(RcsSyncService.TAG, "REFER accepted, joining focus callId=$callId")
}

/**
 * Inbound OPTIONS (capability ping): answer 200 OK with our CPM feature
 * tags in Accept-Contact, so peers' UCE/OPTIONS probes see us as capable.
 */
internal suspend fun RcsSyncService.handleInboundOptions(message: SipMessage) {
    if (!RcsFeature.enabled) return
    val headers = message.getHeaderSection()
    val from = headerValue(headers, "From:") ?: return
    val callId = message.getCallIdParameter() ?: return
    val cseq = headerValue(headers, "CSeq:") ?: "1 OPTIONS"
    val branch = "z9hG4bK${java.util.UUID.randomUUID().toString().replace("-", "").take(16)}"
    val tags = RcsSipTransport.CPM_FEATURE_TAGS.joinToString(", ")
    val startLine = "SIP/2.0 200 OK"
    val outHeaders = buildString {
        append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
        append("From: $from\r\n")
        val cfg = RcsSipTransport.lastConfigSnapshot()
        val me = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
        append("To: <$me>\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq\r\n")
        append("Accept-Contact: *;$tags\r\n")
        append("Content-Length: 0\r\n")
    }
    RcsSipTransport.sendSipMessage(startLine, outHeaders, ByteArray(0))
}

/** Minimal final response to a REFER (no dialog needed). */
internal suspend fun RcsSyncService.sendReferResponse(message: SipMessage, code: Int, reason: String) {
    val headers = message.getHeaderSection()
    val from = headerValue(headers, "From:") ?: return
    val callId = message.getCallIdParameter() ?: return
    val cseq = headerValue(headers, "CSeq:") ?: "1 REFER"
    val branch = "z9hG4bK${java.util.UUID.randomUUID().toString().replace("-", "").take(16)}"
    val startLine = "SIP/2.0 $code $reason"
    val outHeaders = buildString {
        append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
        append("From: $from\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq\r\n")
        append("Content-Length: 0\r\n")
    }
    RcsSipTransport.sendSipMessage(startLine, outHeaders, ByteArray(0))
}

internal fun RcsSyncService.showSessionInviteNotification(conversationId: String, from: String) {
    val target = ConversationTarget(
        line = CommunicateLine.Rcs,
        address = conversationId,
        remoteId = conversationId,
        isGroup = false,
        personName = from,
    )
    ConversationSpace.notifyIncoming(
        context = this,
        target = target,
        channelId = RcsSyncService.INCOMING_CHANNEL_ID,
        body = getString(R.string.rcs_session_invite),
        timestamp = System.currentTimeMillis(),
        smallIcon = R.mipmap.ic_launcher,
    )
}

/** Parse a header value out of a SIP header section (case-insensitive name + colon). */
internal fun headerValue(headers: String, name: String): String? {
    for (line in headers.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith(name, ignoreCase = true)) {
            return trimmed.substringAfter(":").trim().takeIf { it.isNotEmpty() }
        }
    }
    return null
}
