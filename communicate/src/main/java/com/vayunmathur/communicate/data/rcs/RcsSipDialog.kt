package com.vayunmathur.communicate.data.rcs

import java.util.UUID

/**
 * Pure SIP dialog builders + parsers (RFC 3261 §12): ACK, in-dialog MESSAGE /
 * BYE / REFER with sequencing, route-set recording.
 *
 * Split from `RcsSessionManager` for file length. All functions are pure
 * string builders/parsers — unit-tested without the transport. Sending stays
 * in the manager (it owns the session map + `RcsSipTransport`).
 */
object RcsSipDialog {
    /**
     * Build an ACK for the 2xx to our INVITE (RFC 3261 §13.2.2.4): same
     * Call-ID/From-tag/To-tag/CSeq-number as the INVITE, Request-URI = the
     * 200 OK's Contact, Route = recorded route set.
     */
    fun buildAck(session: RcsSession, branch: String = newBranch()): Pair<String, String> {
        val target = session.remoteContact?.takeIf { it.isNotBlank() } ?: session.remoteUri
        val startLine = "ACK $target SIP/2.0"
        val headers = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <${session.localUri()}>;tag=${session.localTag}\r\n")
            append("To: <${session.remoteUri}>;tag=${session.remoteTag}\r\n")
            append("Call-ID: ${session.callId}\r\n")
            append("CSeq: ${session.inviteCseq} ACK\r\n")
            session.routeSet.forEach { append("Route: <$it>\r\n") }
            append("Content-Length: 0\r\n")
        }
        return startLine to headers
    }

    /**
     * Build an in-dialog MESSAGE on [session] with the next CSeq
     * (RFC 3261 §12.2.1.1). Returns (startLine, headers, content, nextCseq)
     * — the caller persists `nextCseq` via `session.copy(nextCseq = ...)`.
     */
    fun buildInDialogMessage(
        session: RcsSession,
        body: ByteArray,
        contentType: String = "message/cpim",
        branch: String = newBranch(),
    ): InDialogRequest {
        val cseq = session.nextCseq
        val target = session.remoteContact?.takeIf { it.isNotBlank() } ?: session.remoteUri
        val startLine = "MESSAGE $target SIP/2.0"
        val headers = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <${session.localUri()}>;tag=${session.localTag}\r\n")
            append("To: <${session.remoteUri}>;tag=${session.remoteTag}\r\n")
            append("Call-ID: ${session.callId}\r\n")
            append("CSeq: $cseq MESSAGE\r\n")
            session.routeSet.forEach { append("Route: <$it>\r\n") }
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
        }
        return InDialogRequest(startLine, headers, body, cseq + 1)
    }

    /**
     * Build an in-dialog BYE on [session] with the next CSeq. Same return
     * contract as [buildInDialogMessage].
     */
    fun buildInDialogBye(
        session: RcsSession,
        branch: String = newBranch(),
    ): InDialogRequest {
        val cseq = session.nextCseq
        val target = session.remoteContact?.takeIf { it.isNotBlank() } ?: session.remoteUri
        val startLine = "BYE $target SIP/2.0"
        val headers = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <${session.localUri()}>;tag=${session.localTag}\r\n")
            append("To: <${session.remoteUri}>;tag=${session.remoteTag}\r\n")
            append("Call-ID: ${session.callId}\r\n")
            append("CSeq: $cseq BYE\r\n")
            session.routeSet.forEach { append("Route: <$it>\r\n") }
            append("Content-Length: 0\r\n")
        }
        return InDialogRequest(startLine, headers, ByteArray(0), cseq + 1)
    }

    /** An in-dialog request plus the session's next CSeq after sending it. */
    data class InDialogRequest(
        val startLine: String,
        val headers: String,
        val body: ByteArray,
        val nextCseq: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is InDialogRequest) return false
            return startLine == other.startLine && headers == other.headers &&
                body.contentEquals(other.body) && nextCseq == other.nextCseq
        }

        override fun hashCode(): Int {
            var result = startLine.hashCode()
            result = 31 * result + headers.hashCode()
            result = 31 * result + body.contentHashCode()
            result = 31 * result + nextCseq.hashCode()
            return result
        }
    }

    /**
     * Parse `Contact:` + `Record-Route:` from a 200 OK header section into
     * (contactUri, routeSet). The route set is the Record-Route values in
     * *reverse* order for the UAC (RFC 3261 §12.1.2). Empty route set when
     * absent.
     */
    fun parseDialogRoute(headers: String): Pair<String?, List<String>> {
        var contact: String? = null
        val routes = mutableListOf<String>()
        for (line in headers.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Contact:", ignoreCase = true) -> {
                    contact = trimmed.substringAfter(":").trim()
                        .substringAfter("<").substringBefore(">")
                        .takeIf { it.isNotBlank() }
                        ?: trimmed.substringAfter(":").trim().substringBefore(";")
                            .takeIf { it.isNotBlank() }
                }
                trimmed.startsWith("Record-Route:", ignoreCase = true) -> {
                    trimmed.substringAfter(":").trim()
                        .substringAfter("<").substringBefore(">")
                        .takeIf { it.isNotBlank() }?.let { routes += it }
                }
            }
        }
        return contact to routes.reversed()
    }

    /** Parse the CSeq number from a header section, or null. */
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

    /** Parse the CSeq method token from a header section, or null. */
    fun parseCseqMethod(headers: String): String? {
        for (line in headers.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("CSeq:", ignoreCase = true)) {
                return trimmed.substringAfter(":").trim()
                    .substringAfter(" ").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    fun newBranch(): String = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"

    /** Our local URI for From headers (delegate identity when known). */
    private fun RcsSession.localUri(): String =
        RcsSipTransport.lastConfigSnapshot()?.publicUserId?.takeIf { it.isNotBlank() }
            ?: "sip:local@rcs"
}
