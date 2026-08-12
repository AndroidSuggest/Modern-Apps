package com.vayunmathur.communicate.data.googlevoice.call

import android.util.Log
import com.vayunmathur.communicate.data.googlevoice.GvSipRegisterInfo
import com.vayunmathur.library.network.WebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

/**
 * Minimal SIP-over-WebSocket client for Google Voice web calling.
 *
 * Google Voice web calls run SIP over `wss://web.voice.telephony.goog/websocket` with
 * DTLS-SRTP WebRTC media (see `voice-documentation.md` → "Voice Calling (SIP over WebSocket)").
 * Outbound frames are readable SIP text; the registrar is `*.pbx.voice.sip.google.com`.
 *
 * This implements the observed lifecycle:
 * ```
 * REGISTER -> 200
 * Outbound: INVITE(SDP offer) -> 100/180/183 -> PRACK -> 200 OK(SDP) -> ACK -> media -> BYE
 * Inbound:  INVITE(recv) -> 180 Ringing -> 200 OK(answer) / 603 Decline / 487
 * ```
 *
 * ⚠️ The exact SIP auth (digest realm/credentials mapping) and some header specifics were not
 * recoverable from the HAR (inbound frames were binary/compressed). This is a structurally
 * complete best-effort that will need on-device iteration — the plan flags Phase 5 as the most
 * likely to need tuning. Transport uses the repo's [WebSocketClient] (no OkHttp).
 */
class SipClient(
    private val registerInfo: GvSipRegisterInfo,
    private val gvNumber: String,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onRegistered()
        fun onProvisional(code: Int)
        /** Remote answer SDP for our outbound INVITE. */
        fun onAnswered(remoteSdp: String)
        /** Early-media SDP delivered in a reliable provisional (183) before the final 200. */
        fun onEarlyMedia(remoteSdp: String)
        fun onEnded()
        fun onFailed(reason: String)
        /** Inbound INVITE: remote offer SDP + caller number. */
        fun onIncomingInvite(remoteSdp: String, from: String)
    }

    private var socket: WebSocketClient? = null
    private var readJob: Job? = null

    private val localHost = "${randomToken(12)}.invalid"
    private val callId = UUID.randomUUID().toString()
    private val fromTag = randomToken(8)
    private var toTag: String? = null
    private var cseq = 1
    private var lastInviteSdp: String? = null
    private var inviteTarget: String? = null
    private var inviteCseq = 0
    private var remoteContact: String? = null
    private var routeSet: List<String> = emptyList()
    private var lastExpires = 3600
    private var registerRetried = false
    private var authRetried = false
    private var inboundInvite: InboundInvite? = null

    // The SIP AOR/username is credential[0]; the digest password is credential[1] (from
    // sipregisterinfo/get). The AOR user is credential[0] URL-encoded ("=" -> "%3D").
    private val authUsername = registerInfo.credentials.getOrNull(0) ?: gvNumber
    private val authPassword = registerInfo.credentials.getOrNull(1) ?: ""
    private val aorUser = authUsername.replace("=", "%3D")
    private val contactUser = randomToken(8)

    suspend fun connect() {
        if (socket != null) return
        socket = WebSocketClient.connect(
            urlStr = WS_URL,
            headers = mapOf(
                "Sec-WebSocket-Protocol" to "sip",
                "Origin" to "https://voice.google.com",
            ),
        )
        readJob = scope.launch(Dispatchers.IO) {
            val s = socket ?: return@launch
            s.incomingFlow().collect { frame ->
                val text = when (frame) {
                    is WebSocketClient.WsFrame.Text -> frame.text
                    // Inbound frames were binary/compressed in the HAR; try a UTF-8 view.
                    is WebSocketClient.WsFrame.Binary -> runCatching { frame.bytes.toString(Charsets.UTF_8) }.getOrNull()
                    is WebSocketClient.WsFrame.Close -> { listener.onEnded(); null }
                    else -> null
                }
                if (!text.isNullOrBlank()) handleIncoming(text)
            }
        }
    }

    suspend fun register(expires: Int = 3600, authHeader: String? = null) {
        lastExpires = expires
        val msg = buildRequest(
            method = "REGISTER",
            requestUri = "sip:$SIP_DOMAIN",
            to = fromUri(),
            extraHeaders = listOfNotNull(
                "Expires: $expires",
                authHeader?.let { "Authorization: $it" },
            ),
        )
        send(msg)
    }

    /** Place an outbound call with the given ICE-complete SDP offer. */
    suspend fun invite(target: String, sdpOffer: String) {
        lastInviteSdp = sdpOffer
        inviteTarget = target
        val msg = buildRequest(
            method = "INVITE",
            requestUri = "sip:$target@$SIP_DOMAIN",
            to = "<sip:$target@$SIP_DOMAIN>",
            body = sdpOffer,
            contentType = "application/sdp",
        )
        inviteCseq = cseq
        send(msg)
    }

    /** Acknowledge a reliable provisional response (100rel) so the call can proceed. */
    private suspend fun prack(target: String, rseq: Int) {
        val msg = buildRequest(
            method = "PRACK",
            requestUri = "sip:$target@$SIP_DOMAIN",
            to = "<sip:$target@$SIP_DOMAIN>${toTag?.let { ";tag=$it" } ?: ""}",
            extraHeaders = listOf("RAck: $rseq $inviteCseq INVITE"),
        )
        send(msg)
    }

    suspend fun ack(target: String) {
        // In-dialog: send to the remote Contact with the route set, reusing the INVITE CSeq.
        val uri = remoteContact ?: "sip:$target@$SIP_DOMAIN"
        val msg = buildRequest(
            method = "ACK",
            requestUri = uri,
            to = "<sip:$target@$SIP_DOMAIN>${toTag?.let { ";tag=$it" } ?: ""}",
            extraHeaders = routeSet.map { "Route: $it" },
            cseqOverride = inviteCseq,
        )
        send(msg)
    }

    suspend fun bye(target: String) {
        // Must target the remote Contact URI and carry the dialog route set, or Google can't
        // match the dialog and the far leg never hangs up.
        val uri = remoteContact ?: "sip:$target@$SIP_DOMAIN"
        val msg = buildRequest(
            method = "BYE",
            requestUri = uri,
            to = "<sip:$target@$SIP_DOMAIN>${toTag?.let { ";tag=$it" } ?: ""}",
            extraHeaders = routeSet.map { "Route: $it" },
        )
        send(msg)
    }

    /** Answer an inbound INVITE with our answer SDP (200 OK). */
    suspend fun answerInbound(answerSdp: String) {
        val invite = inboundInvite ?: return
        send(buildInboundResponse(invite, 200, "OK", body = answerSdp, contentType = "application/sdp"))
    }

    /** Decline an inbound INVITE (603). */
    suspend fun declineInbound() {
        val invite = inboundInvite ?: return
        send(buildInboundResponse(invite, 603, "Decline"))
        inboundInvite = null
    }

    fun close() {
        readJob?.cancel()
        readJob = null
        val s = socket
        socket = null
        inboundInvite = null
        scope.launch { runCatching { s?.close() } }
    }

    // ------------------------------------------------------------------

    private suspend fun send(message: String) {
        Log.d(TAG, "SIP >>\n$message")
        socket?.send(message)
    }

    private fun handleIncoming(message: String) {
        Log.d(TAG, "SIP <<\n$message")
        val firstLine = message.lineSequence().firstOrNull()?.trim().orEmpty()
        val headers = parseHeaders(message)
        headers["to"]?.let { extractTag(it)?.let { t -> toTag = t } }
        val body = message.substringAfter("\r\n\r\n", "").ifBlank { message.substringAfter("\n\n", "") }

        // Capture the dialog remote target + route set from INVITE responses (183/200), needed
        // so in-dialog ACK/BYE route correctly and actually tear the call down.
        val cseqHeaderTop = headers["cseq"].orEmpty()
        val codeTop = if (firstLine.startsWith("SIP/2.0")) firstLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0 else -1
        if (cseqHeaderTop.contains("INVITE", true) && codeTop in 180..299) {
            if (remoteContact == null) extractContact(message)?.let { remoteContact = it }
            if (routeSet.isEmpty()) {
                val rr = extractRecordRoutes(message)
                if (rr.isNotEmpty()) routeSet = rr.reversed()
            }
        }

        when {
            firstLine.startsWith("SIP/2.0") -> {
                val code = firstLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                val cseqHeader = headers["cseq"].orEmpty()
                when {
                    // Registrar wants a longer registration interval; retry once at Min-Expires.
                    code == 423 && cseqHeader.contains("REGISTER", true) && !registerRetried -> {
                        registerRetried = true
                        val min = headers["min-expires"]?.toIntOrNull() ?: (lastExpires * 2)
                        scope.launch { runCatching { register(min) } }
                    }
                    // Digest challenge: compute the response and re-REGISTER with Authorization.
                    (code == 401 || code == 407) && cseqHeader.contains("REGISTER", true) && !authRetried -> {
                        authRetried = true
                        val challenge = headers["www-authenticate"] ?: headers["proxy-authenticate"] ?: ""
                        val nonce = extractParam(challenge, "nonce") ?: ""
                        val realm = extractParam(challenge, "realm") ?: SIP_DOMAIN
                        val auth = digestAuth("REGISTER", "sip:$SIP_DOMAIN", nonce, realm)
                        scope.launch { runCatching { register(lastExpires, auth) } }
                    }
                    code == 200 && cseqHeader.contains("REGISTER", true) -> listener.onRegistered()
                    code == 200 && cseqHeader.contains("INVITE", true) -> listener.onAnswered(body)
                    code in 100..199 -> {
                        listener.onProvisional(code)
                        // Reliable provisional (100rel) carries the SDP answer + RSeq; apply the
                        // early media and PRACK it, or the registrar drops the call with 504.
                        if (body.contains("m=", ignoreCase = true)) listener.onEarlyMedia(body)
                        val rseq = headers["rseq"]?.toIntOrNull()
                        val tgt = inviteTarget
                        if (rseq != null && tgt != null) {
                            scope.launch { runCatching { prack(tgt, rseq) } }
                        }
                    }
                    code == 487 || code == 603 || code == 486 -> listener.onEnded()
                    code in 400..699 -> listener.onFailed("SIP $firstLine")
                }
            }
            firstLine.startsWith("INVITE", true) -> {
                val invite = InboundInvite.from(message, headers)
                inboundInvite = invite
                scope.launch {
                    runCatching { send(buildInboundResponse(invite, 100, "Trying")) }
                    runCatching { send(buildInboundResponse(invite, 180, "Ringing")) }
                }
                val from = headers["from"]?.let { extractUserFromHeader(it) } ?: "Unknown"
                listener.onIncomingInvite(body, from)
            }
            firstLine.startsWith("CANCEL", true) -> {
                val invite = inboundInvite
                scope.launch {
                    runCatching { send(buildCancelOk(message, headers)) }
                    if (invite != null) {
                        runCatching { send(buildInboundResponse(invite, 487, "Request Terminated")) }
                    }
                }
                inboundInvite = null
                listener.onEnded()
            }
            firstLine.startsWith("BYE", true) -> {
                scope.launch { runCatching { send(buildByeOk(message, headers)) } }
                inboundInvite = null
                listener.onEnded()
            }
        }
    }

    private fun buildRequest(
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

    private fun buildInboundResponse(
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
            if (code == 200) append("Contact: <sip:$contactUser@$localHost;transport=wss>\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            append("Content-Length: $bytes\r\n")
            append("\r\n")
            if (body != null) append(body)
        }
    }

    private fun buildCancelOk(message: String, headers: Map<String, String>): String {
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

    private fun buildByeOk(message: String, headers: Map<String, String>): String {
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

    /** The remote target URI from a response's Contact header (used for in-dialog ACK/BYE). */
    private fun extractContact(message: String): String? {
        val line = message.lineSequence().firstOrNull { it.trim().startsWith("Contact:", true) } ?: return null
        return Regex("<([^>]+)>").find(line)?.groupValues?.getOrNull(1)
            ?: line.substringAfter(":").trim().ifBlank { null }
    }

    /** All Record-Route URIs (in order) from a response; the dialog route set is their reverse. */
    private fun extractRecordRoutes(message: String): List<String> =
        message.lineSequence()
            .filter { it.trim().startsWith("Record-Route:", true) }
            .mapNotNull { Regex("<([^>]+)>").find(it)?.groupValues?.getOrNull(1)?.let { u -> "<$u>" } }
            .toList()

    private fun fromUri() = "<sip:$aorUser@$SIP_DOMAIN>"

    private fun headerLines(message: String, name: String): List<String> {
        val prefix = "$name:"
        return message.lineSequence()
            .map { it.trimEnd() }
            .takeWhile { it.isNotEmpty() }
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .toList()
    }

    private fun parseHeaders(message: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (line in message.lineSequence()) {
            val trimmed = line.trimEnd()
            if (trimmed.isEmpty()) break
            val idx = trimmed.indexOf(':')
            if (idx > 0) {
                out[trimmed.substring(0, idx).trim().lowercase()] = trimmed.substring(idx + 1).trim()
            }
        }
        return out
    }

    private fun extractTag(headerValue: String): String? =
        Regex(";tag=([^;\\s]+)").find(headerValue)?.groupValues?.getOrNull(1)

    /** Extract a quoted or bare param (e.g. nonce, realm) from a WWW-Authenticate header. */
    private fun extractParam(header: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"").find(header)?.groupValues?.getOrNull(1)
            ?: Regex("$name=([^,\\s]+)").find(header)?.groupValues?.getOrNull(1)

    /** RFC 2069-style MD5 SIP digest (no qop), matching Google's registrar challenge. */
    private fun digestAuth(method: String, uri: String, nonce: String, realm: String): String {
        val ha1 = md5("$authUsername:$realm:$authPassword")
        val ha2 = md5("$method:$uri")
        val response = md5("$ha1:$nonce:$ha2")
        return "Digest algorithm=MD5, username=\"$authUsername\", realm=\"$realm\", " +
            "nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
    }

    private fun md5(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun extractUserFromHeader(headerValue: String): String? =
        Regex("sip:([^@>;\\s]+)@").find(headerValue)?.groupValues?.getOrNull(1)

    private data class InboundInvite(
        val viaLines: List<String>,
        val recordRouteLines: List<String>,
        val from: String,
        val to: String,
        val callId: String,
        val cseq: String,
        val responseToTag: String = randomToken(8),
    ) {
        fun toTagForResponse(code: Int): String {
            if (to.contains(";tag=", ignoreCase = true)) return ""
            return if (code > 100) ";tag=$responseToTag" else ""
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

    companion object {
        private const val TAG = "SipClient"
        private const val WS_URL = "wss://web.voice.telephony.goog/websocket"
        // Registrar/domain observed in capture 2 (User-Agent: GoogleVoice; PBX host).
        private const val SIP_DOMAIN = "web.c.pbx.voice.sip.google.com"
    }
}

private fun randomToken(len: Int): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
}
