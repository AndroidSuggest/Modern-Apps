package com.vayunmathur.communicate.data.rcs

/**
 * RCS Universal Profile session + messaging primitives.
 *
 * Covers the TestRcsApp gap analysis:
 * - TestRcsApp HAS: INVITE/MSRP 1:1 sessions (SimpleChatSession), CPIM
 *   envelopes (CpimUtils), FT-over-HTTP upload (FileUploadController),
 *   provisioning flow (ProvisioningController), UCE (UceActivity).
 * - TestRcsApp LACKS (built here from the UP spec): IMDN delivery/display
 *   receipts (RFC 5438), is-typing notifications (RFC 3994), group
 *   conference INVITE semantics, FT-over-HTTP receiver side.
 *
 * All SIP construction uses the `SipDelegateConfiguration` captured by
 * [RcsSipTransport] (public user identity, home domain, local address).
 * Pager-mode CPIM remains the v1 send path; sessions upgrade a conversation
 * to INVITE/MSRP when both ends support it.
 */

/** A live CPM session dialog (INVITE/200/ACK completed, MSRP bound). */
data class RcsSession(
    /** SIP dialog id (Call-ID + tags); routes in-dialog traffic. */
    val dialogId: String,
    val callId: String,
    val localTag: String,
    val remoteTag: String,
    val remoteUri: String,
    val conversationId: String,
    val isGroup: Boolean = false,
    /** MSRP path negotiated via SDP (`a=path`). Null until bound. */
    val msrpLocalPath: String? = null,
    val msrpRemotePath: String? = null,
    /**
     * Peer's MSRP setup role from the SDP answer. ACTIVE-only: we always
     * connect out, so a peer answering `active` keeps `msrpRemotePath`
     * nulled (pager-mode fallback). Null = absent, treated as passive.
     */
    val msrpSetup: MsrpSetup? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** MSRP connection roles (`a=setup:`). */
enum class MsrpSetup {
    ACTIVE,
    PASSIVE,
    ACTPASS,
}

/** IMDN disposition (RFC 5438) for delivery/display receipts. */
enum class ImdnDisposition(val token: String) {
    Delivered("positive-delivery"),
    Displayed("display"),
    Failed("failed"),
}

/**
 * Build an IMDN `message/imdn+xml` body reporting [disposition] for
 * [originalMessageId] (the `imdn.Message-ID` from the received CPIM).
 */
fun buildImdnBody(originalMessageId: String, disposition: ImdnDisposition): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\n")
    append("  <message-id>$originalMessageId</message-id>\n")
    append("  <datetime>${java.time.Instant.now()}</datetime>\n")
    append("  <status><${disposition.token}/></status>\n")
    append("</imdn>")
}

/** Parse an IMDN report body into (message-id, disposition), or null. */
fun parseImdnBody(body: String): Pair<String, ImdnDisposition>? {
    if (!body.contains("<imdn", ignoreCase = true)) return null
    val id = Regex("<message-id>([^<]+)</message-id>").find(body)?.groupValues?.getOrNull(1)
        ?: return null
    val disposition = ImdnDisposition.entries.firstOrNull { body.contains("<${it.token}", ignoreCase = true) }
        ?: return null
    return id.trim() to disposition
}

/** Extract the `imdn.Message-ID` from a CPIM envelope, or null. */
fun extractImdnMessageId(cpim: String): String? =
    Regex("imdn\\.Message-ID:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(cpim)
        ?.groupValues?.getOrNull(1)?.trim()

/**
 * Build an `application/im-iscomposing+xml` body (RFC 3994): [active] true
 * while the user is typing, false + `lastactive` on idle.
 */
fun buildIsComposingBody(active: Boolean): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<isComposing xmlns=\"urn:ietf:params:xml:ns:im-composing\">\n")
    append("  <state>${if (active) "active" else "idle"}</state>\n")
    if (!active) append("  <lastactive>${java.time.Instant.now()}</lastactive>\n")
    append("</isComposing>")
}

/** True when [body] is an is-composing notification; returns active flag or null. */
fun parseIsComposingBody(body: String): Boolean? {
    if (!body.contains("im-composing", ignoreCase = true)) return null
    return when {
        body.contains("<state>active</state>", ignoreCase = true) -> true
        body.contains("<state>idle</state>", ignoreCase = true) -> false
        else -> null
    }
}
