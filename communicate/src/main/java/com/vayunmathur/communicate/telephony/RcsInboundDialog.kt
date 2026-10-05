package com.vayunmathur.communicate.telephony

import android.telephony.ims.SipMessage
import android.util.Log
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.rcs.RcsConferenceEvents
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsSessionManager
import com.vayunmathur.communicate.data.rcs.RcsSipDialog
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.noteFocusSubscriber
import com.vayunmathur.communicate.data.rcs.onConferenceNotify
import com.vayunmathur.communicate.data.rcs.publishConferenceInfo
import com.vayunmathur.communicate.data.rcs.subscribeConferenceEvents
import com.vayunmathur.communicate.notifications.ConversationSpace
import com.vayunmathur.communicate.notifications.ConversationTarget
import kotlinx.coroutines.launch

/**
 * Dialog-forming/method handlers for inbound SIP, split from
 * [RcsSyncService] for file length. Extension functions on the service so
 * they share its scope, notification channels, and MSRP connection map.
 */

private const val SIP_OK = 200
private const val SIP_ACCEPTED = 202
private const val SIP_BAD_EVENT = 489
private const val SIP_CALL_GONE = 481
private const val SIP_NOT_ACCEPTABLE = 488
private const val SIP_DECLINE = 603

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
    val callId = message.getCallIdParameter() ?: return
    val body = message.getContent().toString(Charsets.UTF_8)
    // Re-INVITE on a live dialog (§1.5): update media, answer 200 with
    // current SDP — do NOT create a new session entry.
    if (RcsSessionManager.isKnownDialog(callId)) {
        answerReInvite(message, callId, body)
        return
    }
    val fromTag = headerValue(headers, "From:")?.substringAfter("tag=", "")?.substringBefore(";")
        ?.trim()?.takeIf { it.isNotBlank() }
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

/** Answer a re-INVITE with current SDP, declining when none is available. */
private suspend fun RcsSyncService.answerReInvite(message: SipMessage, callId: String, body: String) {
    RcsSessionManager.onReInvite(callId, body)
    val entry = RcsSessionManager.sessionsMutable.value.entries
        .firstOrNull { it.value.callId == callId } ?: return
    val sdp = RcsSessionManager.currentSdpFor(entry.key) ?: run {
        RcsSessionManager.declineIncoming(entry.key)
        return
    }
    sendSessionResponse(message, SIP_OK, "OK", sdp, "application/sdp")
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
 * session to it, which joins the conference dialog, then SUBSCRIBE for
 * conference events (§3.1) and report progress back with sipfrag NOTIFYs
 * (§3.3).
 */
internal suspend fun RcsSyncService.handleInboundRefer(message: SipMessage) {
    if (!RcsFeature.enabled) return
    val headers = message.getHeaderSection()
    val referTo = headerValue(headers, "Refer-To:")?.substringAfter("<")?.substringBefore(">")
        ?.takeIf { it.isNotBlank() } ?: return
    // Accept the referral, then join.
    sendReferResponse(message, SIP_ACCEPTED, "Accepted")
    val conversationId = "conf:${referTo.hashCode()}"
    val joined = RcsSessionManager.startGroupSession(referTo, "", conversationId) != null
    notifyReferStatus(message, joined)
    if (joined) {
        RcsSessionManager.subscribeConferenceEvents(referTo, conversationId)
    }
    Log.i(RcsSyncService.TAG, "REFER accepted, joining focus callId=${message.getCallIdParameter()}")
}

/**
 * Report a referral outcome to the referrer with an in-dialog NOTIFY
 * carrying `message/sipfrag` (RFC 3420 §3.3): 200 on join, 603 on failure.
 * Best-effort; never throws.
 */
internal suspend fun RcsSyncService.notifyReferStatus(message: SipMessage, ok: Boolean) {
    if (!RcsFeature.enabled) return
    runCatching {
        val headers = message.getHeaderSection()
        val from = headerValue(headers, "From:") ?: return
        val callId = message.getCallIdParameter() ?: return
        val cseq = headerValue(headers, "CSeq:") ?: "1 REFER"
        // NOTIFYs for a REFER reuse the REFER's dialog identifiers with a
        // fresh CSeq; the subscription here is implicit (no SUBSCRIBE).
        val sipfrag = RcsConferenceEvents.buildSipfrag(
            if (ok) SIP_OK else SIP_DECLINE,
            if (ok) "OK" else "Decline",
        ).toByteArray(Charsets.UTF_8)
        val branch = RcsSipDialog.newBranch()
        val cfg = RcsSipTransport.lastConfigSnapshot()
        val me = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
        val outHeaders = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("From: $from\r\n")
            append("To: <$me>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: ${cseq.substringBefore(" ").trim().toLongOrNull()?.plus(1) ?: 2} NOTIFY\r\n")
            append("Event: refer\r\n")
            append("Subscription-State: terminated;reason=noresource\r\n")
            append("Content-Type: message/sipfrag\r\n")
            append("Content-Length: ${sipfrag.size}\r\n")
        }
        RcsSipTransport.sendSipMessage("NOTIFY $from SIP/2.0", outHeaders, sipfrag)
    }
}

/**
 * Inbound NOTIFY: conference-info on a joined focus (§3.1) reconciles the
 * member set; refer sipfrag on our outgoing REFERs just logs. Answers 200 OK
 * to keep subscriptions alive.
 */
internal suspend fun RcsSyncService.handleInboundNotify(message: SipMessage) {
    if (!RcsFeature.enabled) return
    val headers = message.getHeaderSection()
    val event = headerValue(headers, "Event:")?.substringBefore(";")?.trim().orEmpty()
    val body = message.getContent().toString(Charsets.UTF_8)
    val from = headerValue(headers, "From:")?.substringAfter("<")?.substringBefore(">")
        ?.takeIf { it.isNotBlank() }.orEmpty()
    if (event.equals("conference", ignoreCase = true)) {
        // Match to our subscription by focus URI (From) or fall back to any
        // conversation holding an event sub for it.
        val conversationId = "conf:${from.hashCode()}"
        val (added, removed) = RcsSessionManager.onConferenceNotify(conversationId, body)
        if (added.isNotEmpty() || removed.isNotEmpty()) {
            Log.i(RcsSyncService.TAG, "Conference update +${added.size}/-${removed.size} for $conversationId")
        }
    } else if (event.equals("refer", ignoreCase = true)) {
        RcsConferenceEvents.parseSipfrag(body)?.let { (code, reason) ->
            Log.i(RcsSyncService.TAG, "Referral status $code $reason from $from")
        }
    }
    sendNotifyResponse(message)
}

/** Minimal 200 OK to a NOTIFY (keeps the subscription dialog alive). */
internal suspend fun RcsSyncService.sendNotifyResponse(message: SipMessage) {
    val headers = message.getHeaderSection()
    val from = headerValue(headers, "From:") ?: return
    val callId = message.getCallIdParameter() ?: return
    val cseq = headerValue(headers, "CSeq:") ?: "1 NOTIFY"
    val branch = RcsSipDialog.newBranch()
    val startLine = "SIP/2.0 200 OK"
    val outHeaders = buildString {
        append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
        append("From: $from\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq\r\n")
        append("Content-Length: 0\r\n")
    }
    RcsSipTransport.sendSipMessage(startLine, outHeaders, ByteArray(0))
}

/**
 * Inbound SUBSCRIBE: a peer subscribes to our hosted focus's conference
 * events (§3.1 publish side). Records the subscriber, answers 202, and
 * immediately NOTIFYs current state.
 */
internal suspend fun RcsSyncService.handleInboundSubscribe(message: SipMessage) {
    if (!RcsFeature.enabled) return
    val headers = message.getHeaderSection()
    val event = headerValue(headers, "Event:")?.substringBefore(";")?.trim().orEmpty()
    if (!event.equals("conference", ignoreCase = true)) {
        sendSubscribeResponse(message, SIP_BAD_EVENT, "Bad Event")
        return
    }
    val to = headerValue(headers, "To:")?.substringAfter("<")?.substringBefore(">")
        ?.takeIf { it.isNotBlank() }.orEmpty()
    val callId = message.getCallIdParameter() ?: return
    sendSubscribeResponse(message, SIP_ACCEPTED, "Accepted")
    RcsSessionManager.noteFocusSubscriber(callId, to)
    // Find the hosted conversation for this focus and publish current state.
    val conversationId = RcsSessionManager.sessions.value.entries
        .firstOrNull { it.value.isFocus && it.value.remoteUri == to }?.key
    if (conversationId != null) {
        RcsSessionManager.publishConferenceInfo(to)
    }
    Log.i(RcsSyncService.TAG, "Focus SUBSCRIBE accepted for $to")
}

/** Minimal final response to a SUBSCRIBE. */
internal suspend fun RcsSyncService.sendSubscribeResponse(message: SipMessage, code: Int, reason: String) {
    val headers = message.getHeaderSection()
    val from = headerValue(headers, "From:") ?: return
    val callId = message.getCallIdParameter() ?: return
    val cseq = headerValue(headers, "CSeq:") ?: "1 SUBSCRIBE"
    val branch = RcsSipDialog.newBranch()
    val startLine = "SIP/2.0 $code $reason"
    val outHeaders = buildString {
        append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
        append("From: $from\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq\r\n")
        append("Expires: 3600\r\n")
        append("Content-Length: 0\r\n")
    }
    RcsSipTransport.sendSipMessage(startLine, outHeaders, ByteArray(0))
}

/**
 * Inbound UPDATE (RFC 3311) / re-INVITE session refresh (§1.5): answer 200
 * OK echoing current SDP when the dialog exists, 481 otherwise. Re-INVITEs
 * (INVITE on an existing Call-ID) are answered the same way via
 * [handleInboundInvite]'s session path — this covers standalone UPDATE.
 */
internal suspend fun RcsSyncService.handleInboundUpdate(message: SipMessage) {
    if (!RcsFeature.enabled) return
    val headers = message.getHeaderSection()
    val callId = message.getCallIdParameter() ?: return
    val session = RcsSessionManager.sessions.value.values.firstOrNull { it.callId == callId }
    if (session == null) {
        sendSessionResponse(message, SIP_CALL_GONE, "Call/Transaction Does Not Exist")
        return
    }
    // Echo our current SDP (no media change on refresh).
    val sdp = RcsSessionManager.currentSdpFor(session.conversationId) ?: run {
        sendSessionResponse(message, SIP_NOT_ACCEPTABLE, "Not Acceptable Here")
        return
    }
    sendSessionResponse(message, SIP_OK, "OK", sdp, "application/sdp")
}

/** Final response to UPDATE/re-INVITE with optional SDP body. */
internal suspend fun RcsSyncService.sendSessionResponse(
    message: SipMessage,
    code: Int,
    reason: String,
    body: ByteArray = ByteArray(0),
    contentType: String? = null,
) {
    val headers = message.getHeaderSection()
    val from = headerValue(headers, "From:") ?: return
    val callId = message.getCallIdParameter() ?: return
    val cseq = headerValue(headers, "CSeq:") ?: "1 UPDATE"
    val branch = RcsSipDialog.newBranch()
    val startLine = "SIP/2.0 $code $reason"
    val outHeaders = buildString {
        append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
        append("From: $from\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq\r\n")
        if (contentType != null) append("Content-Type: $contentType\r\n")
        append("Content-Length: ${body.size}\r\n")
    }
    RcsSipTransport.sendSipMessage(startLine, outHeaders, body)
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
