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
    internal const val TAG = "RcsSession"

    internal val _sessions = MutableStateFlow<Map<String, RcsSession>>(emptyMap())
    val sessions: StateFlow<Map<String, RcsSession>> = _sessions.asStateFlow()

    /** Transaction id (Via branch) → session, for response routing. */
    internal val transactions = ConcurrentHashMap<String, String>()

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
        _sessions.value = _sessions.value + (conversationId to RcsSession(
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
        _sessions.value = _sessions.value + (conversationId to RcsSession(
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
        maxAgeMs: Long = 5 * 60 * 1000L,
        maxIdleMs: Long = 30 * 60 * 1000L,
    ): List<String> {
        if (!RcsFeature.enabled) return emptyList()
        val dropped = mutableListOf<String>()
        for ((conversationId, session) in _sessions.value) {
            if (session.isFocus) continue
            val age = nowMs - session.createdAt
            val incomplete = session.remoteTag.isBlank() && age > maxAgeMs
            val idle = session.remoteTag.isNotBlank() &&
                session.msrpRemotePath == null && age > maxIdleMs
            if (incomplete || idle) {
                _sessions.value = _sessions.value - conversationId
                _pendingOffers.remove(session.callId)
                _answerFingerprints.remove(session.callId)
                RcsMsrpListen.dropPending(conversationId)
                dropped += conversationId
            }
        }
        transactions.entries.removeIf { (_, dialogId) ->
            _sessions.value.values.none { it.dialogId == dialogId }
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
        val session = _sessions.value[conversationId] ?: return false
        val cfg = RcsSipTransport.lastConfigSnapshot()
        val from = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
        val offered = _pendingOffers[session.callId]
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
            append("Contact: <$from>;+sip.instance=\"<urn:gsma:imei:${cfg?.imei ?: "unknown"}>\";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session\"\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${bytes.size}\r\n")
        }
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, bytes)
        if (ok) {
            // Record the offerer's path/setup for connect-out — unless we
            // answered passive, in which case THEY connect to our listen
            // path and the accept loop completes the session.
            val offer = _pendingOffers.remove(session.callId)
            if (offer != null) {
                _sessions.value = _sessions.value + (conversationId to session.copy(
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
        val session = _sessions.value[conversationId] ?: return true
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val startLine = "SIP/2.0 488 Not Acceptable Here"
        val headers = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("Call-ID: ${session.callId}\r\n")
            append("CSeq: 1 INVITE\r\n")
            append("Content-Length: 0\r\n")
        }
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, ByteArray(0))
        _pendingOffers.remove(session.callId)
        _sessions.value = _sessions.value - conversationId
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

    internal val _pendingOffers = ConcurrentHashMap<String, PendingOffer>()

    /** Peer's SDP answer fingerprint for an outgoing session ([callId]). */
    internal val _answerFingerprints = ConcurrentHashMap<String, String>()

    /** Stashed answer fingerprint for [callId], or null when absent. */
    fun peerFingerprint(callId: String): String? = _answerFingerprints[callId]

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
        val session = _sessions.value[conversationId]
            ?.takeIf { it.confirmed && it.remoteTag.isNotBlank() } ?: return false
        val req = RcsSipDialog.buildInDialogMessage(session, body, contentType)
        val ok = RcsSipTransport.sendSipMessage(req.startLine, req.headers, req.body)
        if (ok) {
            _sessions.value = _sessions.value + (
                conversationId to session.copy(nextCseq = req.nextCseq)
                )
        }
        return ok
    }

    /** Tear down the session for [conversationId] with in-dialog BYE. */
    suspend fun terminateSession(conversationId: String): Boolean {
        val session = _sessions.value[conversationId] ?: return true
        val ok = if (session.confirmed && session.remoteTag.isNotBlank()) {
            // Confirmed dialog: in-dialog BYE with next CSeq + route set.
            val req = RcsSipDialog.buildInDialogBye(session)
            val sent = RcsSipTransport.sendSipMessage(req.startLine, req.headers, req.body)
            _sessions.value = _sessions.value + (
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
        _sessions.value = _sessions.value - conversationId
        _pendingOffers.remove(session.callId)
        _answerFingerprints.remove(session.callId)
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
        val entry = _sessions.value.entries.firstOrNull { it.value.callId == callId } ?: return
        val session = entry.value
        if (statusCode in 200..299 && !remoteTag.isNullOrBlank()) {
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
                _answerFingerprints[callId] = fp
                RcsMsrpListen.notePeerFingerprint(entry.key, fp)
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
            _sessions.value = _sessions.value + (entry.key to established)
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
                _sessions.value = _sessions.value + (entry.key to
                    (_sessions.value[entry.key] ?: established).copy(msrpRemotePath = null))
            }
            // ACK the 2xx (RFC 3261 §13): same CSeq number as the INVITE,
            // Request-URI = answer Contact, confirmed dialog either way.
            val acked = runCatching {
                val (ackLine, ackHeaders) = RcsSipDialog.buildAck(
                    (_sessions.value[entry.key] ?: established).copy(remoteTag = remoteTag),
                )
                RcsSipTransport.sendSipMessage(ackLine, ackHeaders, ByteArray(0))
            }.getOrDefault(false)
            _sessions.value = _sessions.value + (entry.key to
                ((_sessions.value[entry.key] ?: established).copy(confirmed = true)))
            if (!acked) Log.w(TAG, "ACK not accepted for $callId (dialog unconfirmed at SIP layer)")
        } else if (statusCode >= 300) {
            Log.w(TAG, "Session failed $callId code=$statusCode")
            transactions.entries.removeIf { it.value == session.dialogId }
            _sessions.value = _sessions.value - entry.key
            _answerFingerprints.remove(callId)
            RcsMsrpListen.dropPending(entry.key)
        }
    }

    /**
     * Dialog-mismatch recovery (§1.6): 481/408/480/486/603 against [callId].
     * Closes MSRP state + framework dialog state, drops pending paths, and
     * parks the conversation in backoff so the send path doesn't hammer
     * session re-establishment. Never throws.
     */
    fun onDialogError(callId: String, statusCode: Int) {
        val entry = _sessions.value.entries.firstOrNull { it.value.callId == callId }
        if (entry != null) {
            _sessions.value = _sessions.value - entry.key
            RcsMsrpListen.dropPending(entry.key)
            backoffUntil[entry.key] = System.currentTimeMillis() + DIALOG_ERROR_BACKOFF_MS
            Log.w(TAG, "Dialog error $statusCode for ${entry.key}; backing off")
        }
        _pendingOffers.remove(callId)
        _answerFingerprints.remove(callId)
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
            "INVITE" -> {
                // INVITE to a focus URI we host: record the joiner, keyed to the
                // hosted conversation (the caller answers with acceptIncoming on
                // the hosted thread; relay fans their messages out).
                val target = (toUri ?: "") + "\n" + body
                hostedConversationFor(target)?.let { hosted ->
                    noteFocusJoin(hosted.focusUri, fromUri)
                    return RcsSession(
                        dialogId = "$callId:focus-in",
                        callId = callId,
                        localTag = UUID.randomUUID().toString().take(8),
                        remoteTag = fromTag.orEmpty(),
                        remoteUri = fromUri,
                        conversationId = hosted.conversationId,
                        isGroup = true,
                    )
                }
                val conversationId = fromUri.substringAfter("sip:").substringBefore("@")
                    .takeIf { it.isNotBlank() } ?: fromUri
                // Stash the offerer's path/setup for acceptIncoming connect-out.
                _pendingOffers[callId] = PendingOffer(
                    remotePath = Regex("a=path:(\\S+)", RegexOption.IGNORE_CASE).find(body)
                        ?.groupValues?.getOrNull(1)?.trim(),
                    setup = parseSdpSetup(body),
                    sdp = body,
                    peerFingerprint = RcsMsrpTls.parseFingerprint(body)?.second,
                )
                val session = RcsSession(
                    dialogId = "$callId:in",
                    callId = callId,
                    localTag = UUID.randomUUID().toString().take(8),
                    remoteTag = fromTag.orEmpty(),
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
            "ACK" -> {
                // ACK to our 200 OK: the incoming dialog is confirmed. Match
                // by Call-ID (the session was stored under the sender id).
                val entry = _sessions.value.entries.firstOrNull { it.value.callId == callId }
                if (entry != null) {
                    _sessions.value = _sessions.value + (
                        entry.key to entry.value.copy(confirmed = true)
                        )
                }
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
    internal fun buildSdpOffer(
        localIp: String?,
        listenPath: String?,
        tlsFingerprint: String? = null,
    ): String {
        val ip = localIp?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
        val secure = tlsFingerprint != null
        val (path, port, setup) = if (listenPath != null) {
            Triple(
                if (secure) listenPath.withScheme("msrps") else listenPath,
                RcsMsrpListen.listenPort() ?: 2855,
                "actpass",
            )
        } else {
            Triple(
                "${if (secure) "msrps" else "msrp"}://${UUID.randomUUID()}.invalid:2855/${UUID.randomUUID()};tcp",
                2855,
                "active",
            )
        }
        val proto = if (secure) "TCP/TLS/MSRP" else "TCP/MSRP"
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
    internal fun buildSdpAnswer(
        localIp: String?,
        passivePath: String? = null,
        secure: Boolean = false,
        tlsFingerprint: String? = null,
    ): String {
        val ip = localIp?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
        val useTls = secure && tlsFingerprint != null
        val (path, port, setup) = if (passivePath != null) {
            Triple(
                if (useTls) passivePath.withScheme("msrps") else passivePath,
                RcsMsrpListen.listenPort() ?: 2855,
                "passive",
            )
        } else {
            Triple(
                "${if (useTls) "msrps" else "msrp"}://${UUID.randomUUID()}.invalid:2855/${UUID.randomUUID()};tcp",
                2855,
                "active",
            )
        }
        val proto = if (useTls) "TCP/TLS/MSRP" else "TCP/MSRP"
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
            if (useTls) append("a=fingerprint:${RcsMsrpTls.FINGERPRINT_HASH} $tlsFingerprint\r\n")
        }
    }

    private fun parseSdpPaths(sdp: String?): Pair<String?, String?> {
        if (sdp.isNullOrBlank()) return null to null
        val path = Regex("a=path:(\\S+)", RegexOption.IGNORE_CASE).find(sdp)
            ?.groupValues?.getOrNull(1)?.trim()
        return null to path
    }

    /** Rewrite an `msrp(s)://` path's scheme (e.g. advertise `msrps://`). */
    private fun String.withScheme(scheme: String): String {
        val rest = substringAfter("://", missingDelimiterValue = this)
        return if (rest === this) this else "$scheme://$rest"
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
        _sessions.value.values.any { it.callId == callId }

    /**
     * Apply a re-INVITE's SDP to a live dialog (§1.5): update the bound
     * path/setup/TLS to the new offer. Media handover (socket swap) is the
     * sync service's job — it observes the session map. Never throws.
     */
    fun onReInvite(callId: String, sdp: String) {
        val entry = _sessions.value.entries.firstOrNull { it.value.callId == callId } ?: return
        val session = entry.value
        val (localPath, remotePath) = parseSdpPaths(sdp)
        val setup = parseSdpSetup(sdp) ?: session.msrpSetup
        val secure = isSecureSdp(sdp)
        RcsMsrpTls.parseFingerprint(sdp)?.second?.let { fp ->
            _answerFingerprints[callId] = fp
            RcsMsrpListen.notePeerFingerprint(entry.key, fp)
        }
        _sessions.value = _sessions.value + (entry.key to session.copy(
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
        val session = _sessions.value[conversationId] ?: return null
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
    private data class HostedTarget(val focusUri: String, val conversationId: String)
    private fun hostedConversationFor(target: String): HostedTarget? {
        if (target.isBlank()) return null
        for ((conversationId, session) in _sessions.value) {
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
