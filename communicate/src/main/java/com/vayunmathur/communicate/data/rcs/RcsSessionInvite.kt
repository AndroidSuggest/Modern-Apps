package com.vayunmathur.communicate.data.rcs

import com.vayunmathur.library.log.Log
import java.util.UUID

/**
 * SIP/SDP builders for [RcsSessionManager] (split for file length).
 * Extension functions on [RcsSessionManager]; behavior identical, call sites unchanged.
 */

internal fun RcsSessionManager.buildInvite(
    targetUri: String,
    callId: String,
    localTag: String,
    branch: String,
    conversationId: String,
    subject: String? = null,
    listenPath: String? = null,
    tlsFingerprint: String? = null,
): Triple<String, String, ByteArray> {
    val cfg = RcsSipTransport.lastConfigSnapshot()
    val from = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
    val sdp = buildSdpOffer(cfg?.msrpLocalIp, listenPath, tlsFingerprint)
    val bytes = sdp.toByteArray(Charsets.UTF_8)
    val startLine = "INVITE $targetUri SIP/2.0"
    val headers = buildString {
        append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
        append("Max-Forwards: 70\r\n")
        append("From: <$from>;tag=$localTag\r\n")
        append("To: <$targetUri>\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: 1 INVITE\r\n")
        append(contactHeader(from, cfg?.imei))
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

/**
 * SDP offer: `setup:actpass` + listen path when we can accept inbound
 * TCP ([listenPath] from [RcsMsrpListen]), else the v1 `setup:active`
 * connect-out offer with an unroutable placeholder path.
 *
 * TLS (RFC 4976): when our identity is available ([tlsFingerprint]
 * non-null) the offer is `TCP/TLS/MSRP` with an `msrps://` path +
 * `a=fingerprint`, so a TLS-capable peer answers secure; otherwise plain
 * `TCP/MSRP`. The media proto/paths always agree (never a TLS path on a
 * plaintext `m=` line).
 */
internal fun RcsSessionManager.buildSdpOffer(
    localIp: String?,
    listenPath: String?,
    tlsFingerprint: String? = null,
    ): String {
    val ip = localIp?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
    val secure = tlsFingerprint != null
    val (path, port, setup) = sdpPathPortSetup(listenPath, secure, "actpass")
    val proto = if (secure) "TCP/TLS/MSRP" else "TCP/MSRP"
    return buildSdpBody(ip, port, proto, path, setup, secure, tlsFingerprint)
}

/** Path/port/setup triple for an offer or answer. */
private fun sdpPathPortSetup(
    listenPath: String?,
    secure: Boolean,
    listenSetup: String,
): Triple<String, Int, String> {
    if (listenPath != null) {
        return Triple(
            if (secure) listenPath.withScheme("msrps") else listenPath,
            RcsMsrpListen.listenPort() ?: RcsSessionManager.DEFAULT_MSRP_PORT,
            listenSetup,
        )
    }
    val scheme = if (secure) "msrps" else "msrp"
    return Triple(
        "$scheme://${UUID.randomUUID()}.invalid:" +
        "${RcsSessionManager.DEFAULT_MSRP_PORT}/${UUID.randomUUID()};tcp",
        RcsSessionManager.DEFAULT_MSRP_PORT,
        "active",
    )
}

/** Render the SDP body. */
private fun buildSdpBody(
    ip: String,
    port: Int,
    proto: String,
    path: String,
    setup: String,
    secure: Boolean,
    tlsFingerprint: String?,
): String {
    return buildString {
        append("v=0\r\n")
        append("o=- ${System.currentTimeMillis()} ${System.currentTimeMillis()} IN IP4 $ip\r\n")
        append("s=-\r\n")
        append("c=IN IP4 $ip\r\n")
        append("t=0 0\r\n")
        append("m=message $port $proto *\r\n")
        append("a=path:$path\r\n")
        append("a=accept-types:message/cpim text/plain message/imdn+xml application/im-iscomposing+xml\r\n")
        append("a=setup:$setup\r\n")
        if (secure) append("a=fingerprint:${RcsMsrpTls.FINGERPRINT_HASH} $tlsFingerprint\r\n")
    }
}

    /**
     * SDP answer: `setup:passive` + listen path when we are accepting the
     * offerer's TCP connection ([passivePath] non-null), else the v1
     * `setup:active` answer (we connect out to the offerer). [secure]
     * mirrors the offerer's choice: a TLS offer (`TCP/TLS/MSRP`,
     * `msrps://`, or `a=fingerprint`) gets a TLS answer with our
     * fingerprint; otherwise plaintext.
     */
internal fun RcsSessionManager.buildSdpAnswer(
    localIp: String?,
    passivePath: String? = null,
    secure: Boolean = false,
    tlsFingerprint: String? = null,
    ): String {
    val ip = localIp?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
    val useTls = secure && tlsFingerprint != null
    val (path, port, setup) = sdpPathPortSetup(passivePath, useTls, "passive")
    val proto = if (useTls) "TCP/TLS/MSRP" else "TCP/MSRP"
    return buildSdpBody(ip, port, proto, path, setup, useTls, tlsFingerprint)
}
/** Inbound INVITE: focus join or new incoming session. */
internal fun RcsSessionManager.onInviteRequest(
    callId: String,
    fromUri: String,
    body: String,
    fromTag: String?,
    toUri: String?,
): RcsSession {
    // INVITE to a focus URI we host: record the joiner, keyed to the
    // hosted conversation (the caller answers with acceptIncoming on
    // the hosted thread; relay fans their messages out).
    val target = (toUri ?: "") + "\n" + body
    hostedConversationFor(target)?.let { hosted ->
        noteFocusJoin(hosted.focusUri, fromUri)
        return RcsSession(
            dialogId = "$callId:focus-in",
            callId = callId,
            localTag = UUID.randomUUID().toString().take(TAG_LENGTH),
            remoteTag = fromTag.orEmpty(),
            remoteUri = fromUri,
            conversationId = hosted.conversationId,
            isGroup = true,
        )
    }
    val conversationId = fromUri.substringAfter("sip:").substringBefore("@")
        .takeIf { it.isNotBlank() } ?: fromUri
    // Stash the offerer's path/setup for acceptIncoming connect-out.
    pendingOffersMutable[callId] = RcsSessionManager.PendingOffer(
        remotePath = Regex("a=path:(\\S+)", RegexOption.IGNORE_CASE).find(body)
            ?.groupValues?.getOrNull(1)?.trim(),
        setup = parseSdpSetup(body),
        sdp = body,
        peerFingerprint = RcsMsrpTls.parseFingerprint(body)?.second,
    )
    val session = RcsSession(
        dialogId = "$callId:in",
        callId = callId,
        localTag = UUID.randomUUID().toString().take(TAG_LENGTH),
        remoteTag = fromTag.orEmpty(),
        remoteUri = fromUri,
        conversationId = conversationId,
    )
    sessionsMutable.value = sessionsMutable.value + (conversationId to session)
    return session
}

/** Inbound MESSAGE: IMDN reports and typing notifications. */
internal fun RcsSessionManager.onMessageRequest(fromUri: String, contentType: String?, body: String) {
    val conversationId = fromUri.substringAfter("sip:").substringBefore("@")
    if (contentType?.contains("imdn", ignoreCase = true) == true) {
        RcsImdn.onReportReceived(body)
    } else if (contentType?.contains("im-composing", ignoreCase = true) == true ||
        parseIsComposingBody(body) != null
    ) {
        val active = parseIsComposingBody(body) ?: false
        typingMutable.value = typingMutable.value + (conversationId to active)
    }
}

/** Tear down a failed session (>= 300). */
internal fun RcsSessionManager.failSession(
    key: String,
    session: RcsSession,
    callId: String,
    statusCode: Int,
) {
    Log.status(RcsSessionManager.TAG, "Session failed $callId code=$statusCode")
    transactions.entries.removeIf { it.value == session.dialogId }
    sessionsMutable.value = sessionsMutable.value - key
    answerFingerprintsMutable.remove(callId)
    RcsMsrpListen.dropPending(key)
}
