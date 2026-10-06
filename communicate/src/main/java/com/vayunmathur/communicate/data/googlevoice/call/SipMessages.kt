package com.vayunmathur.communicate.data.googlevoice.call

/**
 * SIP message builders for [SipClient] (split for file length).
 * Extension functions on [SipClient]; behavior identical, call sites unchanged.
 */

internal fun SipClient.buildRequest(
    method: String,
    requestUri: String,
    to: String,
    body: String? = null,
    contentType: String? = null,
    extraHeaders: List<String> = emptyList(),
    incrementCseq: Boolean = true,
    cseqOverride: Int? = null,
): String {
    val seq = cseqOverride ?: if (incrementCseq) ++cseq else cseq
    val branch = "z9hG4bK${randomToken(16)}"
    val bodyBytes = body?.toByteArray()?.size ?: 0
    return buildString {
        append("$method $requestUri SIP/2.0\r\n")
        append("Via: SIP/2.0/wss $localHost;branch=$branch\r\n")
        append("Max-Forwards: 70\r\n")
        append("From: ${fromUri()};tag=$fromTag\r\n")
        append("To: $to\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $seq $method\r\n")
        append("Contact: <sip:$contactUser@$localHost;transport=wss>;+sip.ice;reg-id=1\r\n")
        append("Supported: 100rel,ice,replaces,outbound,timer\r\n")
        append("Allow: INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,PRACK\r\n")
        append("User-Agent: Communicate GoogleVoice\r\n")
        extraHeaders.forEach { append("$it\r\n") }
        if (contentType != null) append("Content-Type: $contentType\r\n")
        append("Content-Length: $bodyBytes\r\n")
        append("\r\n")
        if (body != null) append(body)
    }
}

internal fun SipClient.buildInboundResponse(
    invite: InboundInvite,
    code: Int,
    reason: String,
    body: String? = null,
    contentType: String? = null,
): String {
    val bytes = body?.toByteArray()?.size ?: 0
    return buildString {
        append("SIP/2.0 $code $reason\r\n")
        invite.viaLines.forEach { append("$it\r\n") }
        append("From: ${invite.from}\r\n")
        append("To: ${invite.to}${invite.toTagForResponse(code)}\r\n")
        append("Call-ID: ${invite.callId}\r\n")
        append("CSeq: ${invite.cseq}\r\n")
        invite.recordRouteLines.forEach { append("$it\r\n") }
        if (code == SipClient.OK) append("Contact: <sip:$contactUser@$localHost;transport=wss>\r\n")
        if (contentType != null) append("Content-Type: $contentType\r\n")
        append("Content-Length: $bytes\r\n")
        append("\r\n")
        if (body != null) append(body)
    }
}

internal fun SipClient.buildCancelOk(message: String, headers: Map<String, String>): String {
    val viaLines = headerLines(message, "Via")
    val from = headers["from"].orEmpty()
    val to = headers["to"].orEmpty()
    val callId = headers["call-id"].orEmpty()
    val cseq = headers["cseq"].orEmpty()
    return buildString {
        append("SIP/2.0 200 OK\r\n")
        viaLines.forEach { append("$it\r\n") }
        append("From: $from\r\n")
        append("To: $to\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq\r\n")
        append("Content-Length: 0\r\n\r\n")
    }
}

internal fun SipClient.buildByeOk(message: String, headers: Map<String, String>): String {
    val viaLines = headerLines(message, "Via")
    val from = headers["from"].orEmpty()
    val to = headers["to"].orEmpty()
    val callId = headers["call-id"].orEmpty()
    val cseq = headers["cseq"].orEmpty()
    return buildString {
        append("SIP/2.0 200 OK\r\n")
        viaLines.forEach { append("$it\r\n") }
        append("From: $from\r\n")
        append("To: $to\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: $cseq\r\n")
        append("Content-Length: 0\r\n\r\n")
    }
}

internal data class InboundInvite(
    val viaLines: List<String>,
    val recordRouteLines: List<String>,
    val from: String,
    val to: String,
    val callId: String,
    val cseq: String,
    val responseToTag: String = randomToken(SipClient.TAG_TOKEN_LENGTH),
) {
    fun toTagForResponse(code: Int): String {
        if (to.contains(";tag=", ignoreCase = true)) return ""
        return if (code > SipClient.TRYING) ";tag=$responseToTag" else ""
    }

    companion object {
        fun from(message: String, headers: Map<String, String>) = InboundInvite(
            viaLines = headerLinesStatic(message, "Via"),
            recordRouteLines = headerLinesStatic(message, "Record-Route"),
            from = headers["from"].orEmpty(),
            to = headers["to"].orEmpty(),
            callId = headers["call-id"].orEmpty(),
            cseq = headers["cseq"].orEmpty(),
        )

        private fun headerLinesStatic(message: String, name: String): List<String> {
            val prefix = "$name:"
            return message.lineSequence()
                .map { it.trimEnd() }
                .takeWhile { it.isNotEmpty() }
                .filter { it.startsWith(prefix, ignoreCase = true) }
                .toList()
        }
    }
}

internal fun SipClient.fromUri() = "<sip:$aorUser@${SipClient.SIP_DOMAIN}>"

internal fun SipClient.headerLines(message: String, name: String): List<String> {
    val prefix = "$name:"
    return message.lineSequence()
        .map { it.trimEnd() }
        .takeWhile { it.isNotEmpty() }
        .filter { it.startsWith(prefix, ignoreCase = true) }
        .toList()
}
