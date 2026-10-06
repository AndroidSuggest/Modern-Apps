package com.vayunmathur.communicate.data.rcs

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Rewrite an `msrp(s)://` path's scheme (e.g. advertise `msrps://`). */
internal fun String.withScheme(scheme: String): String {
    val rest = substringAfter("://", missingDelimiterValue = this)
    return if (rest === this) this else "$scheme://$rest"
}

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
    internal const val TAG = "RcsSession"
    private const val MAX_SESSION_AGE_MS = 5 * 60 * 1000L
    private const val MAX_SESSION_IDLE_MS = 30 * 60 * 1000L
    internal const val TAG_LENGTH = 8
    internal const val DEFAULT_MSRP_PORT = 2855
    private val SUCCESS_CODES = 200..299
    private const val REDIRECT_CODES = 300

    private const val CPM_ICSI_REF = "urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session"

    /** CPM session Contact header for [from] with this device's IMEI instance id. */
    internal fun contactHeader(from: String, imei: String?): String =
        "Contact: <$from>;+sip.instance=\"<urn:gsma:imei:${imei ?: "unknown"}>\"" +
            ";+g.3gpp.icsi-ref=\"$CPM_ICSI_REF\"\r\n"

    internal val sessionsMutable = MutableStateFlow<Map<String, RcsSession>>(emptyMap())
    val sessions: StateFlow<Map<String, RcsSession>> = sessionsMutable.asStateFlow()

    /** Transaction id (Via branch) → session, for response routing. */
    internal val transactions = ConcurrentHashMap<String, String>()

    /** Typing state per conversation: conversationId → (active, updatedAt). */
    internal val typingMutable = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val typing: StateFlow<Map<String, Boolean>> = typingMutable.asStateFlow()

    /** Active session for [conversationId], or null. */
    fun sessionFor(conversationId: String): RcsSession? = sessionsMutable.value[conversationId]

    /**
     * Start an outgoing 1:1 CPM session: INVITE with SDP-for-MSRP offer to the
     * `tel:` URI. Returns the dialog id on accept-tracked send, null when the
     * transport is unavailable. The 200 OK/ACK/MSRP bind completes
     * asynchronously via [onSipResponse]/[onSipRequest].
     *
     * The offer carries `setup:actpass` + our listen path when the passive
     * listener is up ([RcsMsrpListen]), so the peer may connect to us;
     * otherwise it is the v1 `setup:active` connect-out offer.
     */
    suspend fun startSession(remoteTelUri: String, conversationId: String): String? {
        if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return null
        val callId = "${UUID.randomUUID()}@rcs"
        val localTag = UUID.randomUUID().toString().take(8)
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val tlsFingerprint = tlsFingerprintFor()
        val secureOffer = tlsFingerprint != null
        val listenPath = listenPathFor(conversationId, secureOffer)
        val (startLine, headers, content) =
            buildInvite(
                remoteTelUri, callId, localTag, branch, conversationId,
                listenPath = listenPath, tlsFingerprint = tlsFingerprint,
            )
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, content)
        if (!ok) return null
        val dialogId = "$callId:$localTag"
        transactions[branch] = dialogId
        sessionsMutable.value = sessionsMutable.value + (conversationId to RcsSession(
            dialogId = dialogId,
            callId = callId,
            localTag = localTag,
            remoteTag = "",
            remoteUri = remoteTelUri,
            conversationId = conversationId,
            msrpSecure = secureOffer,
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
        val tlsFingerprint = tlsFingerprintFor()
        val listenPath = listenPathFor(conversationId, tlsFingerprint != null)
        val (startLine, headers, content) =
            buildInvite(
                focusUri, callId, localTag, branch, conversationId, subject,
                listenPath, tlsFingerprint,
            )
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, content)
        if (!ok) return null
        val dialogId = "$callId:$localTag"
        transactions[branch] = dialogId
        sessionsMutable.value = sessionsMutable.value + (conversationId to RcsSession(
            dialogId = dialogId,
            callId = callId,
            localTag = localTag,
            remoteTag = "",
            remoteUri = focusUri,
            conversationId = conversationId,
            isGroup = true,
            msrpSecure = tlsFingerprint != null,
        ))
        return dialogId
    }

    /** Focus state + management live in `RcsSessionFocus.kt` (split for file length). */

    /**
     * Drop sessions that never completed (no remote tag after [maxAgeMs])
     * and completed sessions idle longer than [maxIdleMs] with no MSRP
     * connection. Returns the dropped conversation ids so the caller can
     * close their connections. Best-effort; never throws.
     */
    fun sweepStaleSessions(
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = MAX_SESSION_AGE_MS,
        maxIdleMs: Long = MAX_SESSION_IDLE_MS,
    ): List<String> {
        if (!RcsFeature.enabled) return emptyList()
        val dropped = mutableListOf<String>()
        for ((conversationId, session) in sessionsMutable.value) {
            if (session.isFocus) continue
            val age = nowMs - session.createdAt
            val incomplete = session.remoteTag.isBlank() && age > maxAgeMs
            val idle = session.remoteTag.isNotBlank() &&
                session.msrpRemotePath == null && age > maxIdleMs
            if (incomplete || idle) {
                sessionsMutable.value = sessionsMutable.value - conversationId
                pendingOffersMutable.remove(session.callId)
                answerFingerprintsMutable.remove(session.callId)
                RcsMsrpListen.dropPending(conversationId)
                dropped += conversationId
            }
        }
        transactions.entries.removeIf { (_, dialogId) ->
            sessionsMutable.value.values.none { it.dialogId == dialogId }
        }
        return dropped
    }

    /**
     * Advertise our listen path for [conversationId] when the passive
     * listener can bind. Null when listening is unavailable — callers fall
     * back to active-only offers. Needs a Context for the IMS request; the
     * session manager is context-free, so the sync service injects it via
     * [listenContextProvider] at startup (null = no passive offers).
     */
    var listenContextProvider: (() -> android.content.Context?)? = null

    private suspend fun listenPathFor(
        conversationId: String,
        secure: Boolean = false,
        peerFingerprint: String? = null,
    ): String? {
        val context = listenContextProvider?.invoke() ?: return null
        // The accept callback is a no-op here: the sync service owns the real
        // accept loop (it calls ensureListening at startup). advertisePath
        // only registers the pending path when already listening.
        if (!RcsMsrpListen.isListening()) return null
        return RcsMsrpListen.advertisePath(
            context, conversationId, secure, peerFingerprint,
        ) { _, _, _ -> }
    }

    /**
     * Our TLS fingerprint for SDP offers/answers, or null when the identity
     * is unavailable (caller falls back to plaintext `msrp://`).
     */
    private suspend fun tlsFingerprintFor(): String? {
        val context = listenContextProvider?.invoke() ?: return null
        return RcsMsrpTls.ensureIdentity(context)?.fingerprint
    }

    /**
     * Answer a pending incoming INVITE with 200 OK + SDP answer.
     *
     * With the passive listener up and an offerer that wants us to listen
     * (`setup:active` + usable path), we answer `setup:passive` with our
     * listen path and their TCP connection completes the session. Otherwise
     * the v1 behavior holds: answer `setup:active` and connect out when the
     * offerer's path/setup allows, else keep the dialog for in-dialog
     * MESSAGE with media on pager-mode.
     *
     * Returns true when the 200 OK was accepted by the transport.
     */
    suspend fun acceptIncoming(conversationId: String): Boolean {
        if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return false
        val session = sessionsMutable.value[conversationId] ?: return false
        val cfg = RcsSipTransport.lastConfigSnapshot()
        val from = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
        val offered = pendingOffersMutable[session.callId]
        // Passive answer when the offerer is active-only and we can listen.
        // The offer's fingerprint (when present) pins the accept-side TLS.
        val offerSdp = offered?.sdp.orEmpty()
        val answerSecure = isSecureSdp(offerSdp)
        val tlsFingerprint = if (answerSecure) tlsFingerprintFor() else null
        val useTls = answerSecure && tlsFingerprint != null
        val passivePath = if (offered?.setup == MsrpSetup.ACTIVE && !offered.remotePath.isNullOrBlank()) {
            listenPathFor(conversationId, useTls, offered.peerFingerprint)
        } else {
            null
        }
        // Offered TLS but identity unavailable: answer plaintext (downgrade)
        // rather than failing the dialog — pager-mode still works.
        val sdp = buildSdpAnswer(
            cfg?.msrpLocalIp, passivePath,
            secure = useTls, tlsFingerprint = tlsFingerprint,
        )
        val bytes = sdp.toByteArray(Charsets.UTF_8)
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val startLine = "SIP/2.0 200 OK"
        val headers = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("From: <${session.remoteUri}>;tag=${session.remoteTag.ifBlank { "unknown" }}\r\n")
            append("To: <$from>;tag=${session.localTag}\r\n")
            append("Call-ID: ${session.callId}\r\n")
            append("CSeq: 1 INVITE\r\n")
            append(contactHeader(from, cfg?.imei))
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${bytes.size}\r\n")
        }
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, bytes)
        if (ok) {
            // Record the offerer's path/setup for connect-out — unless we
            // answered passive, in which case THEY connect to our listen
            // path and the accept loop completes the session.
            val offer = pendingOffersMutable.remove(session.callId)
            if (offer != null) {
                sessionsMutable.value = sessionsMutable.value + (conversationId to session.copy(
                    msrpRemotePath = offer.remotePath,
                    msrpSetup = offer.setup,
                    msrpLocalPath = passivePath ?: session.msrpLocalPath,
                    msrpSecure = useTls,
                ))
            }
        }
        return ok
    }

    /**
     * Decline a pending incoming INVITE with 488 (used when the offer
     * requires passive-us, i.e. `setup:active` with no usable path).
     */
    suspend fun declineIncoming(conversationId: String): Boolean {
        if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return false
        val session = sessionsMutable.value[conversationId] ?: return true
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val startLine = "SIP/2.0 488 Not Acceptable Here"
        val headers = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("Call-ID: ${session.callId}\r\n")
            append("CSeq: 1 INVITE\r\n")
            append("Content-Length: 0\r\n")
        }
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, ByteArray(0))
        pendingOffersMutable.remove(session.callId)
        sessionsMutable.value = sessionsMutable.value - conversationId
        return ok
    }

    /** Inbound offer details stashed at INVITE time for [acceptIncoming]. */
    internal data class PendingOffer(
        val remotePath: String?,
        val setup: MsrpSetup?,
        /** Raw offer SDP (for TLS/fingerprint detection at answer time). */
        val sdp: String = "",
        /** Peer's SDP fingerprint value, when offered (for TLS pinning). */
        val peerFingerprint: String? = null,
    )

    internal val pendingOffersMutable = ConcurrentHashMap<String, PendingOffer>()

    /** Peer's SDP answer fingerprint for an outgoing session ([callId]). */
    internal val answerFingerprintsMutable = ConcurrentHashMap<String, String>()

    /** Stashed answer fingerprint for [callId], or null when absent. */
    fun peerFingerprint(callId: String): String? = answerFingerprintsMutable[callId]

    /**
     * Send a MESSAGE inside [conversationId]'s confirmed dialog (in-dialog
     * Request-URI + route set + next CSeq). Returns false when no confirmed
     * session exists (caller uses pager-mode). Advances the dialog CSeq.
     */
    suspend fun sendInDialogMessage(
        conversationId: String,
        body: ByteArray,
        contentType: String = "message/cpim",
    ): Boolean {
        if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return false
        val session = sessionsMutable.value[conversationId]
            ?.takeIf { it.confirmed && it.remoteTag.isNotBlank() } ?: return false
        val req = RcsSipDialog.buildInDialogMessage(session, body, contentType)
        val ok = RcsSipTransport.sendSipMessage(req.startLine, req.headers, req.body)
        if (ok) {
            sessionsMutable.value = sessionsMutable.value + (
                conversationId to session.copy(nextCseq = req.nextCseq)
                )
        }
        return ok
    }

    /** Tear down the session for [conversationId] with in-dialog BYE. */
    suspend fun terminateSession(conversationId: String): Boolean {
        val session = sessionsMutable.value[conversationId] ?: return true
        val ok = if (session.confirmed && session.remoteTag.isNotBlank()) {
            // Confirmed dialog: in-dialog BYE with next CSeq + route set.
            val req = RcsSipDialog.buildInDialogBye(session)
            val sent = RcsSipTransport.sendSipMessage(req.startLine, req.headers, req.body)
            sessionsMutable.value = sessionsMutable.value + (
                conversationId to session.copy(nextCseq = req.nextCseq)
                )
            sent
        } else {
            // Unconfirmed/early dialog: best-effort BYE as before.
            val branch = RcsSipDialog.newBranch()
            val startLine = "BYE sip:${session.remoteUri} SIP/2.0"
            val headers = buildString {
                append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
                append("Call-ID: ${session.callId}\r\n")
                append("CSeq: 2 BYE\r\n")
                append("Content-Length: 0\r\n")
            }
            RcsSipTransport.sendSipMessage(startLine, headers, ByteArray(0))
        }
        transactions.entries.removeIf { it.value == session.dialogId }
        sessionsMutable.value = sessionsMutable.value - conversationId
        pendingOffersMutable.remove(session.callId)
        answerFingerprintsMutable.remove(session.callId)
        RcsMsrpListen.dropPending(conversationId)
        if (session.remoteTag.isNotBlank()) {
            // Tell the framework the dialog is gone so it releases delegate
            // state for this Call-ID (best-effort; stub-covered).
            RcsSipTransport.cleanupSession(session.callId)
        }
        return ok
    }

    /**
     * Route an inbound SIP response (from the delegate message callback) to
     * its session: 200 OK to INVITE completes the dialog (remote tag + SDP
     * answer + route set) and sends the ACK (RFC 3261 §13.2.2.4); other
     * finals tear the pending session down.
     *
     * Suspend: the ACK goes through the transport. Never throws.
     */
    suspend fun onSipResponse(
        statusCode: Int,
        callId: String,
        remoteTag: String?,
        sdpAnswer: String?,
        responseHeaders: String = "",
    ) {
        val entry = sessionsMutable.value.entries.firstOrNull { it.value.callId == callId } ?: return
        val session = entry.value
        if (statusCode in SUCCESS_CODES && !remoteTag.isNullOrBlank()) {
            establishSession(entry.key, session, remoteTag, sdpAnswer, callId, responseHeaders)
        } else if (statusCode >= REDIRECT_CODES) {
            failSession(entry.key, session, callId, statusCode)
        }
    }

    /** Apply a 2xx answer: paths, fingerprint, usability, ACK. */
    private suspend fun establishSession(
        key: String,
        session: RcsSession,
        remoteTag: String,
        sdpAnswer: String?,
        callId: String,
        responseHeaders: String,
    ) {
        val (localPath, remotePath) = parseSdpPaths(sdpAnswer)
        val setup = parseSdpSetup(sdpAnswer)
        val (contact, routeSet) = RcsSipDialog.parseDialogRoute(responseHeaders)
        // TLS sticks when WE offered secure and the answer keeps it
        // (secure answer SDP); a plaintext answer to our secure offer is
        // a downgrade — honor it and run plaintext.
        val answerSecure = isSecureSdp(sdpAnswer)
        // Stash the answer's fingerprint for the TLS client pin check
        // and the listen-side accept pin check.
        RcsMsrpTls.parseFingerprint(sdpAnswer)?.second?.let { fp ->
            answerFingerprintsMutable[callId] = fp
            RcsMsrpListen.notePeerFingerprint(key, fp)
        }
        val established = session.copy(
            remoteTag = remoteTag,
            msrpLocalPath = localPath ?: session.msrpLocalPath,
            msrpRemotePath = remotePath,
            msrpSetup = setup,
            msrpSecure = session.msrpSecure && answerSecure,
            remoteContact = contact ?: session.remoteContact,
            routeSet = if (routeSet.isNotEmpty()) routeSet else session.routeSet,
        )
        sessionsMutable.value = sessionsMutable.value + (key to established)
        // Usable when: peer is passive/actpass (we connect out, the v1
        // path), OR peer is active and we have a listen path (they
        // connect to us — the accept loop completes the session), OR our
        // offer carried a listen path and the answer kept any path
        // (actpass negotiation leaves the direction to the answerer).
        val peerActive = setup == MsrpSetup.ACTIVE
        val usable = remotePath != null && (
            !peerActive ||
                established.msrpLocalPath?.contains("msrp://") == true &&
                RcsMsrpListen.isListening()
            )
        Log.i(TAG, "Session established ${session.dialogId} setup=$setup usable=$usable")
        if (!usable) {
            sessionsMutable.value = sessionsMutable.value + (key to
                (sessionsMutable.value[key] ?: established).copy(msrpRemotePath = null))
        }
        // ACK the 2xx (RFC 3261 §13): same CSeq number as the INVITE,
        // Request-URI = answer Contact, confirmed dialog either way.
        val acked = runCatching {
            val (ackLine, ackHeaders) = RcsSipDialog.buildAck(
                (sessionsMutable.value[key] ?: established).copy(remoteTag = remoteTag),
            )
            RcsSipTransport.sendSipMessage(ackLine, ackHeaders, ByteArray(0))
        }.getOrDefault(false)
        sessionsMutable.value = sessionsMutable.value + (key to
            ((sessionsMutable.value[key] ?: established).copy(confirmed = true)))
        if (!acked) Log.w(TAG, "ACK not accepted for $callId (dialog unconfirmed at SIP layer)")
    }

    /**
     * Dialog-mismatch recovery (§1.6): 481/408/480/486/603 against [callId].
     * Closes MSRP state + framework dialog state, drops pending paths, and
     * parks the conversation in backoff so the send path doesn't hammer
     * session re-establishment. Never throws.
     */
    fun onDialogError(callId: String, statusCode: Int) {
        val entry = sessionsMutable.value.entries.firstOrNull { it.value.callId == callId }
        if (entry != null) {
            sessionsMutable.value = sessionsMutable.value - entry.key
            RcsMsrpListen.dropPending(entry.key)
            backoffUntil[entry.key] = System.currentTimeMillis() + DIALOG_ERROR_BACKOFF_MS
            Log.w(TAG, "Dialog error $statusCode for ${entry.key}; backing off")
        }
        pendingOffersMutable.remove(callId)
        answerFingerprintsMutable.remove(callId)
        transactions.entries.removeIf { it.value.endsWith(callId) }
        RcsSipTransport.cleanupSession(callId)
    }

    /** True when [conversationId] is in dialog-error backoff (skip fast re-INVITE). */
    fun inDialogBackoff(conversationId: String): Boolean {
        val until = backoffUntil[conversationId] ?: return false
        if (System.currentTimeMillis() >= until) {
            backoffUntil.remove(conversationId)
            return false
        }
        return true
    }

    private val backoffUntil = ConcurrentHashMap<String, Long>()

    /** Post-dialog-error quiet period before fast session re-establishment. */
    private const val DIALOG_ERROR_BACKOFF_MS = 5 * 60 * 1000L

    /**
     * Route an inbound SIP request: INVITE stashes the offer and starts an
     * incoming session entry (caller answers via [acceptIncoming] or declines
     * via [declineIncoming]), BYE tears down, MESSAGE with
     * `message/imdn+xml` updates receipts, `im-iscomposing` updates typing.
     */
    fun onSipRequest(
        method: String,
        callId: String,
        fromUri: String,
        contentType: String?,
        body: String,
        fromTag: String? = null,
        toUri: String? = null,
    ): RcsSession? {
        return when (method.uppercase()) {
            "INVITE" -> onInviteRequest(callId, fromUri, body, fromTag, toUri)
            "BYE" -> {
                val entry = sessionsMutable.value.entries.firstOrNull { it.value.callId == callId }
                if (entry != null) sessionsMutable.value = sessionsMutable.value - entry.key
                null
            }
            "ACK" -> {
                // ACK to our 200 OK: the incoming dialog is confirmed. Match
                // by Call-ID (the session was stored under the sender id).
                val entry = sessionsMutable.value.entries.firstOrNull { it.value.callId == callId }
                if (entry != null) {
                    sessionsMutable.value = sessionsMutable.value + (
                        entry.key to entry.value.copy(confirmed = true)
                        )
                }
                null
            }
            "MESSAGE" -> {
                onMessageRequest(fromUri, contentType, body)
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

    private fun parseSdpPaths(sdp: String?): Pair<String?, String?> {
        if (sdp.isNullOrBlank()) return null to null
        val path = Regex("a=path:(\\S+)", RegexOption.IGNORE_CASE).find(sdp)
            ?.groupValues?.getOrNull(1)?.trim()
        return null to path
    }

    /** True when the SDP negotiates TLS media (`TCP/TLS/MSRP`, `msrps`, or fingerprint). */
    fun isSecureSdp(sdp: String?): Boolean {
        if (sdp.isNullOrBlank()) return false
        return sdp.contains("TCP/TLS/MSRP", ignoreCase = true) ||
            Regex("a=path:msrps://", RegexOption.IGNORE_CASE).containsMatchIn(sdp) ||
            RcsMsrpTls.parseFingerprint(sdp) != null
    }

    /** True when [callId] matches a live session dialog (re-INVITE/UPDATE target). */
    fun isKnownDialog(callId: String): Boolean =
        sessionsMutable.value.values.any { it.callId == callId }

    /**
     * Apply a re-INVITE's SDP to a live dialog (§1.5): update the bound
     * path/setup/TLS to the new offer. Media handover (socket swap) is the
     * sync service's job — it observes the session map. Never throws.
     */
    fun onReInvite(callId: String, sdp: String) {
        val entry = sessionsMutable.value.entries.firstOrNull { it.value.callId == callId } ?: return
        val session = entry.value
        val (localPath, remotePath) = parseSdpPaths(sdp)
        val setup = parseSdpSetup(sdp) ?: session.msrpSetup
        val secure = isSecureSdp(sdp)
        RcsMsrpTls.parseFingerprint(sdp)?.second?.let { fp ->
            answerFingerprintsMutable[callId] = fp
            RcsMsrpListen.notePeerFingerprint(entry.key, fp)
        }
        sessionsMutable.value = sessionsMutable.value + (entry.key to session.copy(
            msrpRemotePath = remotePath ?: session.msrpRemotePath,
            msrpSetup = setup,
            msrpSecure = secure,
            msrpLocalPath = localPath ?: session.msrpLocalPath,
        ))
        Log.i(TAG, "Re-INVITE applied for ${entry.key} setup=$setup secure=$secure")
    }

    /**
     * Current SDP for [conversationId]'s session (for UPDATE/re-INVITE
     * answers, §1.5): re-emits our bound path + setup + TLS state. Null when
     * no session exists.
     */
    fun currentSdpFor(conversationId: String): ByteArray? {
        val session = sessionsMutable.value[conversationId] ?: return null
        val cfg = RcsSipTransport.lastConfigSnapshot()
        val ip = cfg?.msrpLocalIp?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
        val path = session.msrpLocalPath ?: return null
        val port = RcsMsrp.parseMsrpPath(path)?.second ?: 2855
        // We are the answerer side here: our role is passive when the peer
        // connects to us (their setup=active), else active (we connect out).
        val setup = if (session.msrpSetup == MsrpSetup.ACTIVE) "passive" else "active"
        val proto = if (session.msrpSecure) "TCP/TLS/MSRP" else "TCP/MSRP"
        val sdp = buildString {
            append("v=0\r\n")
            append("o=- ${System.currentTimeMillis()} ${System.currentTimeMillis()} IN IP4 $ip\r\n")
            append("s=-\r\n")
            append("c=IN IP4 $ip\r\n")
            append("t=0 0\r\n")
            append("m=message $port $proto *\r\n")
            append("a=path:$path\r\n")
            append("a=accept-types:message/cpim text/plain message/imdn+xml application/im-iscomposing+xml\r\n")
            append("a=setup:$setup\r\n")
        }
        return sdp.toByteArray(Charsets.UTF_8)
    }

    /** Hosted-focus lookup: which focus a To-URI/SDP blob targets. */
    internal data class HostedTarget(val focusUri: String, val conversationId: String)
    internal fun hostedConversationFor(target: String): HostedTarget? {
        if (target.isBlank()) return null
        for ((conversationId, session) in sessionsMutable.value) {
            if (!session.isFocus) continue
            if (target.contains(session.remoteUri, ignoreCase = true)) {
                return HostedTarget(session.remoteUri, conversationId)
            }
        }
        return null
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
