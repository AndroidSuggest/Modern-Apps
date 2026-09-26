package com.vayunmathur.communicate.data.rcs

import android.telephony.ims.SipMessage

/**
 * Pure SIP response parser (RFC 3261 §7.2): `SIP/2.0 <code> <reason>` start
 * line + the dialog identifiers a response routes on.
 *
 * Split from `RcsSipTransport` for testability — no transport, no session
 * map. [parse] returns null when [message] is a request, not a response.
 */
object RcsSipResponse {
    /** A parsed SIP response with its routing keys. */
    data class Parsed(
        val statusCode: Int,
        val reason: String,
        /** Via branch of the top Via (== our transaction id). */
        val branch: String,
        val callId: String,
        /** To-tag (dialog id for 2xx to INVITE). */
        val toTag: String?,
        /** From-tag (echo of our tag). */
        val fromTag: String?,
        /** CSeq method token (INVITE/MESSAGE/BYE/REFER/…). */
        val cseqMethod: String,
        val cseqNumber: Long?,
        /** Raw header section (for Contact/Record-Route/privacy parsing). */
        val headers: String,
        /** Body text (SDP answers). */
        val body: String,
    )

    fun parse(message: SipMessage): Parsed? {
        return runCatching {
            val startLine = message.getStartLine().trim()
            if (!startLine.startsWith("SIP/2.0", ignoreCase = true)) return null
            val statusCode = startLine.substringAfter("SIP/2.0").trim()
                .substringBefore(" ").trim().toIntOrNull() ?: return null
            val reason = startLine.substringAfter("SIP/2.0").trim()
                .substringAfter(" ").trim()
            val headers = message.getHeaderSection()
            val branch = headers.lineSequence()
                .firstOrNull { it.trim().startsWith("Via:", ignoreCase = true) }
                ?.substringAfter("branch=")?.substringBefore(";")?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            val callId = headers.lineSequence()
                .firstOrNull { it.trim().startsWith("Call-ID:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            fun tagOf(name: String): String? = headers.lineSequence()
                .firstOrNull { it.trim().startsWith(name, ignoreCase = true) }
                ?.substringAfter("tag=", "")?.substringBefore(";")?.trim()
                ?.takeIf { it.isNotEmpty() }
            val cseqLine = headers.lineSequence()
                .firstOrNull { it.trim().startsWith("CSeq:", ignoreCase = true) }
                ?.substringAfter(":")?.trim().orEmpty()
            val cseqNumber = cseqLine.substringBefore(" ").trim().toLongOrNull()
            val cseqMethod = cseqLine.substringAfter(" ").trim()
            Parsed(
                statusCode = statusCode,
                reason = reason,
                branch = branch,
                callId = callId,
                toTag = tagOf("To:"),
                fromTag = tagOf("From:"),
                cseqMethod = cseqMethod,
                cseqNumber = cseqNumber,
                headers = headers,
                body = message.getContent().toString(Charsets.UTF_8),
            )
        }.getOrNull()
    }

    /** True when [startLine] is a SIP response rather than a request. */
    fun isResponse(startLine: String): Boolean =
        startLine.trim().startsWith("SIP/2.0", ignoreCase = true)
}
