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
    private const val TAG = "RcsSession"

    private val _sessions = MutableStateFlow<Map<String, RcsSession>>(emptyMap())
    val sessions: StateFlow<Map<String, RcsSession>> = _sessions.asStateFlow()

    /** Transaction id (Via branch) → session, for response routing. */
    private val transactions = ConcurrentHashMap<String, String>()

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
        val listenPath = listenPathFor(conversationId)
        val (startLine, headers, content) =
            buildInvite(remoteTelUri, callId, localTag, branch, conversationId, listenPath = listenPath)
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
        val listenPath = listenPathFor(conversationId)
        val (startLine, headers, content) =
            buildInvite(focusUri, callId, localTag, branch, conversationId, subject, listenPath)
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
        ))
        return dialogId
    }

    /**
     * Host a group conference (focus role, UP 3.x conference model).
     *
     * Unlike joining a carrier focus via [startGroupSession], this makes US the
     * focus: we allocate a local `conf:` URI, invite each participant with a
     * REFER to it (their clients INVITE back and join — see the inbound
     * INVITE-to-focus path in [onSipRequest]), and relay in-conference
     * messages to the other participants (see
     * [RcsSyncService.handleInbound] — relay happens there, keyed off the
     * focus session map below).
     *
     * Focus INVITEs carry the `isfocus` Contact parameter (RFC 4354) + the CPM
     * group tag so peers treat us as the conference server rather than a 1:1
     * caller. Returns the focus URI on success, null otherwise.
     *
     * v1 limits: relay is pager-mode MESSAGE fan-out (no MSRP media mixing);
     * MLS E2EE still terminates per-member via the conversation's MLS group —
     * the focus relays opaque ciphertext, never plaintext.
     */
    suspend fun hostGroupFocus(
        conversationId: String,
        subject: String,
        participants: List<String>,
    ): String? {
        if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return null
        val distinct = participants.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return null
        val focusUri = "conf:${UUID.randomUUID()}@rcs.local"
        val localTag = UUID.randomUUID().toString().take(8)
        _sessions.value = _sessions.value + (conversationId to RcsSession(
            dialogId = "focus:$focusUri",
            callId = "focus-${UUID.randomUUID()}",
            localTag = localTag,
            remoteTag = "",
            remoteUri = focusUri,
            conversationId = conversationId,
            isGroup = true,
            isFocus = true,
        ))
        _focusMembers[focusUri] = distinct.toMutableSet()
        var invited = false
        for (peer in distinct) {
            if (sendReferToFocus(peer, focusUri, subject)) invited = true
        }
        if (!invited) {
            _sessions.value = _sessions.value - conversationId
            _focusMembers.remove(focusUri)
            return null
        }
        Log.i(TAG, "Hosting focus $focusUri for $conversationId (${distinct.size} invited)")
        return focusUri
    }

    /** Participants of hosted foci: focus URI → member E.164 set. */
    private val _focusMembers = ConcurrentHashMap<String, MutableSet<String>>()

    /** Members of the hosted focus [focusUri], or null when not ours. */
    fun focusMembers(focusUri: String): Set<String>? = _focusMembers[focusUri]?.toSet()

    /** Focus URI we host for [conversationId], or null when we don't host it. */
    fun hostedFocusFor(conversationId: String): String? {
        val session = _sessions.value[conversationId] ?: return null
        return if (session.isFocus) session.remoteUri else null
    }

    /** Track a joiner that INVITEd our focus URI (no REFER needed — direct dial). */
    fun noteFocusJoin(focusUri: String, member: String) {
        _focusMembers[focusUri]?.add(member.trim())
    }

    /** Drop a member from a hosted focus (BYE / removal). */
    fun noteFocusLeave(focusUri: String, member: String) {
        _focusMembers[focusUri]?.remove(member.trim())
    }

    /** Tear down a hosted focus: BYE every joined member dialog, drop state. */
    suspend fun destroyHostedFocus(conversationId: String): Boolean {
        val session = _sessions.value[conversationId] ?: return true
        if (!session.isFocus) return terminateSession(conversationId)
        val focusUri = session.remoteUri
        _focusMembers.remove(focusUri)
        _sessions.value = _sessions.value - conversationId
        RcsMsrpListen.dropPending(conversationId)
        return true
    }

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
     * REFER one peer to our hosted focus: `REFER sip:peer` with `Refer-To:
     * <focusUri>` + `Referred-By` us. Their client INVITEs the focus URI back.
     */
    private suspend fun sendReferToFocus(peer: String, focusUri: String, subject: String): Boolean {
        val cfg = RcsSipTransport.lastConfigSnapshot()
        val from = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val startLine = "REFER sip:$peer@rcs SIP/2.0"
        val headers = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <$from>;tag=${UUID.randomUUID().toString().take(8)}\r\n")
            append("To: <sip:$peer@rcs>\r\n")
            append("Call-ID: ${UUID.randomUUID()}@rcs-refer\r\n")
            append("CSeq: 1 REFER\r\n")
            append("Refer-To: <$focusUri>\r\n")
            append("Referred-By: <$from>\r\n")
            if (subject.isNotBlank()) append("Subject: $subject\r\n")
            append("Contact: <$from>;isfocus\r\n")
            append("Content-Length: 0\r\n")
        }
        return RcsSipTransport.sendSipMessage(startLine, headers, ByteArray(0))
    }

    /**
     * Advertise our listen path for [conversationId] when the passive
     * listener can bind. Null when listening is unavailable — callers fall
     * back to active-only offers. Needs a Context for the IMS request; the
     * session manager is context-free, so the sync service injects it via
     * [listenContextProvider] at startup (null = no passive offers).
     */
    var listenContextProvider: (() -> android.content.Context?)? = null

    private suspend fun listenPathFor(conversationId: String): String? {
        val context = listenContextProvider?.invoke() ?: return null
        // The accept callback is a no-op here: the sync service owns the real
        // accept loop (it calls ensureListening at startup). advertisePath
        // only registers the pending path when already listening.
        if (!RcsMsrpListen.isListening()) return null
        return RcsMsrpListen.advertisePath(context, conversationId) { _, _, _ -> }
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
        val passivePath = if (offered?.setup == MsrpSetup.ACTIVE && !offered.remotePath.isNullOrBlank()) {
            listenPathFor(conversationId)
        } else {
            null
        }
        val sdp = buildSdpAnswer(cfg?.msrpLocalIp, passivePath)
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
    private data class PendingOffer(val remotePath: String?, val setup: MsrpSetup?)

    private val _pendingOffers = ConcurrentHashMap<String, PendingOffer>()
    /** Tear down the session for [conversationId] with BYE. */
    suspend fun terminateSession(conversationId: String): Boolean {
        val session = _sessions.value[conversationId] ?: return true
        val branch = "z9hG4bK${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val startLine = "BYE sip:${session.remoteUri} SIP/2.0"
        val headers = buildString {
            append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
            append("Call-ID: ${session.callId}\r\n")
            append("CSeq: 2 BYE\r\n")
            append("Content-Length: 0\r\n")
        }
        val ok = RcsSipTransport.sendSipMessage(startLine, headers, ByteArray(0))
        transactions.remove(branch)
        _sessions.value = _sessions.value - conversationId
        _pendingOffers.remove(session.callId)
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
     * answer), other finals tear the pending session down.
     */
    fun onSipResponse(statusCode: Int, callId: String, remoteTag: String?, sdpAnswer: String?) {
        val entry = _sessions.value.entries.firstOrNull { it.value.callId == callId } ?: return
        val session = entry.value
        if (statusCode in 200..299 && !remoteTag.isNullOrBlank()) {
            val (localPath, remotePath) = parseSdpPaths(sdpAnswer)
            val setup = parseSdpSetup(sdpAnswer)
            _sessions.value = _sessions.value + (entry.key to session.copy(
                remoteTag = remoteTag,
                msrpLocalPath = localPath ?: session.msrpLocalPath,
                msrpRemotePath = remotePath,
                msrpSetup = setup,
            ))
            // Usable when: peer is passive/actpass (we connect out, the v1
            // path), OR peer is active and we have a listen path (they
            // connect to us — the accept loop completes the session), OR our
            // offer carried a listen path and the answer kept any path
            // (actpass negotiation leaves the direction to the answerer).
            val peerActive = setup == MsrpSetup.ACTIVE
            val usable = remotePath != null && (
                !peerActive ||
                    session.msrpLocalPath?.contains("msrp://") == true &&
                    RcsMsrpListen.isListening()
                )
            Log.i(TAG, "Session established ${session.dialogId} setup=$setup usable=$usable")
            if (!usable) {
                _sessions.value = _sessions.value + (entry.key to
                    (_sessions.value[entry.key] ?: session).copy(msrpRemotePath = null))
            }
        } else if (statusCode >= 300) {
            Log.w(TAG, "Session failed $callId code=$statusCode")
            transactions.entries.removeIf { it.value == session.dialogId }
            _sessions.value = _sessions.value - entry.key
        }
    }

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
    ): Triple<String, String, ByteArray> {
        val cfg = RcsSipTransport.lastConfigSnapshot()
        val from = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
        val sdp = buildSdpOffer(cfg?.msrpLocalIp, listenPath)
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
     */
    private fun buildSdpOffer(localIp: String?, listenPath: String?): String {
        val ip = localIp?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
        val (path, port, setup) = if (listenPath != null) {
            Triple(listenPath, RcsMsrpListen.listenPort() ?: 2855, "actpass")
        } else {
            Triple(
                "msrp://${UUID.randomUUID()}.invalid:2855/${UUID.randomUUID()};tcp",
                2855,
                "active",
            )
        }
        return buildString {
            append("v=0\r\n")
            append("o=- ${System.currentTimeMillis()} ${System.currentTimeMillis()} IN IP4 $ip\r\n")
            append("s=-\r\n")
            append("c=IN IP4 $ip\r\n")
            append("t=0 0\r\n")
            append("m=message $port TCP/MSRP *\r\n")
            append("a=path:$path\r\n")
            append("a=accept-types:message/cpim text/plain message/imdn+xml application/im-iscomposing+xml\r\n")
            append("a=setup:$setup\r\n")
        }
    }

    /**
     * SDP answer: `setup:passive` + listen path when we are accepting the
     * offerer's TCP connection ([passivePath] non-null), else the v1
     * `setup:active` answer (we connect out to the offerer).
     */
    private fun buildSdpAnswer(localIp: String?, passivePath: String? = null): String {
        val ip = localIp?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
        val (path, port, setup) = if (passivePath != null) {
            Triple(passivePath, RcsMsrpListen.listenPort() ?: 2855, "passive")
        } else {
            Triple(
                "msrp://${UUID.randomUUID()}.invalid:2855/${UUID.randomUUID()};tcp",
                2855,
                "active",
            )
        }
        return buildString {
            append("v=0\r\n")
            append("o=- ${System.currentTimeMillis()} ${System.currentTimeMillis()} IN IP4 $ip\r\n")
            append("s=-\r\n")
            append("c=IN IP4 $ip\r\n")
            append("t=0 0\r\n")
            append("m=message $port TCP/MSRP *\r\n")
            append("a=path:$path\r\n")
            append("a=accept-types:message/cpim text/plain message/imdn+xml application/im-iscomposing+xml\r\n")
            append("a=setup:$setup\r\n")
        }
    }

    private fun parseSdpPaths(sdp: String?): Pair<String?, String?> {
        if (sdp.isNullOrBlank()) return null to null
        val path = Regex("a=path:(\\S+)", RegexOption.IGNORE_CASE).find(sdp)
            ?.groupValues?.getOrNull(1)?.trim()
        return null to path
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
