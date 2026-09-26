package com.vayunmathur.communicate.data.rcs

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Conference focus management for [RcsSessionManager], split for file length.
 * Extension functions + focus-owned state (member sets, event subscriptions,
 * subscriber lists, version counters). The dialog/session core stays in the
 * manager; everything focus-shaped lives here.
 */

/** Participants of hosted foci: focus URI → member E.164 set. */
private val focusMembersMap = ConcurrentHashMap<String, MutableSet<String>>()

/**
 * Conference-event subscriptions WE hold (focus URI → dialog data for
 * the SUBSCRIBE dialog to a carrier focus, §3.1).
 */
private data class EventSubscription(
    val focusUri: String,
    val callId: String,
    val localTag: String,
    val remoteTag: String = "",
    val lastVersion: Int = -1,
)

private val eventSubs = ConcurrentHashMap<String, EventSubscription>()

/**
 * SUBSCRIBE dialogs on hosted foci (subscriber Call-ID → focus URI, §3.1
 * publish side). NOTIFYs go out on each membership change.
 */
private val focusSubscribers = ConcurrentHashMap<String, String>()

/** Conference-info version counter per hosted focus. */
private val focusVersions = ConcurrentHashMap<String, Int>()

/**
 * Host a group conference (focus role, UP 3.x conference model).
 *
 * Unlike joining a carrier focus via [RcsSessionManager.startGroupSession],
 * this makes US the focus: we allocate a local `conf:` URI, invite each
 * participant with a REFER to it (their clients INVITE back and join — see
 * the inbound INVITE-to-focus path in `onSipRequest`), and relay
 * in-conference messages to the other participants (see
 * `RcsSyncService.handleInbound` — relay happens there, keyed off the
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
suspend fun RcsSessionManager.hostGroupFocus(
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
    focusMembersMap[focusUri] = distinct.toMutableSet()
    var invited = false
    for (peer in distinct) {
        if (sendReferToFocus(peer, focusUri, subject)) invited = true
    }
    if (!invited) {
        _sessions.value = _sessions.value - conversationId
        focusMembersMap.remove(focusUri)
        return null
    }
    Log.i(TAG, "Hosting focus $focusUri for $conversationId (${distinct.size} invited)")
    return focusUri
}

/** Members of the hosted focus [focusUri], or null when not ours. */
fun RcsSessionManager.focusMembers(focusUri: String): Set<String>? =
    focusMembersMap[focusUri]?.toSet()

/** Focus URI we host for [conversationId], or null when we don't host it. */
fun RcsSessionManager.hostedFocusFor(conversationId: String): String? {
    val session = _sessions.value[conversationId] ?: return null
    return if (session.isFocus) session.remoteUri else null
}

/** Track a joiner that INVITEd our focus URI (no REFER needed — direct dial). */
fun RcsSessionManager.noteFocusJoin(focusUri: String, member: String) {
    focusMembersMap[focusUri]?.add(member.trim())
}

/** Drop a member from a hosted focus (BYE / removal). */
fun RcsSessionManager.noteFocusLeave(focusUri: String, member: String) {
    focusMembersMap[focusUri]?.remove(member.trim())
}

/** Tear down a hosted focus: BYE every joined member dialog, drop state. */
suspend fun RcsSessionManager.destroyHostedFocus(conversationId: String): Boolean {
    val session = _sessions.value[conversationId] ?: return true
    if (!session.isFocus) return terminateSession(conversationId)
    val focusUri = session.remoteUri
    // BYE every joined member dialog first (§3.2), best-effort.
    val members = focusMembersMap.remove(focusUri)?.toList().orEmpty()
    for (member in members) {
        runCatching {
            val dialogEntry = _sessions.value.entries.firstOrNull {
                it.value.callId.endsWith(member.hashCode().toString()) ||
                    it.value.remoteUri == member
            }
            if (dialogEntry != null) terminateSession(dialogEntry.key)
        }
    }
    focusSubscribers.entries.removeIf { it.value == focusUri }
    focusVersions.remove(focusUri)
    _sessions.value = _sessions.value - conversationId
    RcsMsrpListen.dropPending(conversationId)
    return true
}

/**
 * Invite [peer] to our hosted [focusUri]: REFER + member-set add (§3.2).
 * Returns true when the REFER was accepted by the transport. Publishes a
 * conference-info NOTIFY to subscribers on change.
 */
suspend fun RcsSessionManager.inviteFocusMember(
    conversationId: String,
    peer: String,
    subject: String = "",
): Boolean {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return false
    val session = _sessions.value[conversationId]?.takeIf { it.isFocus } ?: return false
    val member = peer.trim()
    if (member.isEmpty()) return false
    val ok = sendReferToFocus(member, session.remoteUri, subject)
    if (ok) {
        focusMembersMap[session.remoteUri]?.add(member)
        publishConferenceInfo(session.remoteUri)
    }
    return ok
}

/**
 * Remove [peer] from our hosted focus: BYE their dialog + member-set
 * drop (§3.2). Returns true when the member was tracked (BYE itself is
 * best-effort). Publishes a conference-info NOTIFY to subscribers.
 */
suspend fun RcsSessionManager.removeFocusMember(conversationId: String, peer: String): Boolean {
    if (!RcsFeature.enabled) return false
    val session = _sessions.value[conversationId]?.takeIf { it.isFocus } ?: return false
    val member = peer.trim()
    val removed = focusMembersMap[session.remoteUri]?.remove(member) == true
    // BYE any dialog joined by this member.
    val dialogEntry = _sessions.value.entries.firstOrNull {
        !it.value.isFocus && it.value.remoteUri == member &&
            it.value.conversationId == conversationId
    }
    if (dialogEntry != null && RcsSipTransport.canSend()) {
        runCatching { terminateSession(dialogEntry.key) }
    }
    noteFocusLeave(session.remoteUri, member)
    publishConferenceInfo(session.remoteUri)
    return removed
}

/**
 * SUBSCRIBE to `Event: conference` at a carrier [focusUri] (§3.1) so
 * participant joins/parts arrive as NOTIFYs. Out-of-dialog subscription;
 * refresh is the caller's job (see [refreshEventSubscriptions]).
 */
suspend fun RcsSessionManager.subscribeConferenceEvents(focusUri: String, conversationId: String): Boolean {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return false
    if (eventSubs.containsKey(conversationId)) return true
    val cfg = RcsSipTransport.lastConfigSnapshot()
    val from = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
    val callId = "${UUID.randomUUID()}@rcs-sub"
    val localTag = UUID.randomUUID().toString().take(8)
    val branch = RcsSipDialog.newBranch()
    val startLine = "SUBSCRIBE $focusUri SIP/2.0"
    val headers = buildString {
        append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
        append("Max-Forwards: 70\r\n")
        append("From: <$from>;tag=$localTag\r\n")
        append("To: <$focusUri>\r\n")
        append("Call-ID: $callId\r\n")
        append("CSeq: 1 SUBSCRIBE\r\n")
        append("Event: conference\r\n")
        append("Accept: application/conference-info+xml\r\n")
        append("Expires: 3600\r\n")
        append("Contact: <$from>\r\n")
        append("Content-Length: 0\r\n")
    }
    val ok = RcsSipTransport.sendSipMessage(startLine, headers, ByteArray(0))
    if (ok) {
        eventSubs[conversationId] = EventSubscription(focusUri, callId, localTag)
    }
    return ok
}

/**
 * Refresh conference-event subscriptions (re-SUBSCRIBE before expiry).
 * Called periodically by the sync service; each refresh bumps CSeq.
 */
suspend fun RcsSessionManager.refreshEventSubscriptions() {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return
    for ((conversationId, sub) in eventSubs) {
        runCatching {
            val cfg = RcsSipTransport.lastConfigSnapshot()
            val from = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
            val branch = RcsSipDialog.newBranch()
            val headers = buildString {
                append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
                append("Max-Forwards: 70\r\n")
                append("From: <$from>;tag=${sub.localTag}\r\n")
                append("To: <${sub.focusUri}>;tag=${sub.remoteTag}\r\n")
                append("Call-ID: ${sub.callId}\r\n")
                append("CSeq: 2 SUBSCRIBE\r\n")
                append("Event: conference\r\n")
                append("Accept: application/conference-info+xml\r\n")
                append("Expires: 3600\r\n")
                append("Contact: <$from>\r\n")
                append("Content-Length: 0\r\n")
            }
            RcsSipTransport.sendSipMessage("SUBSCRIBE ${sub.focusUri} SIP/2.0", headers, ByteArray(0))
        }
    }
}

/**
 * Route an inbound conference-info NOTIFY body for [conversationId]:
 * reconcile added members in + deleted members out, returning the added
 * + removed URI lists. Stale versions (≤ last seen) are ignored.
 */
fun RcsSessionManager.onConferenceNotify(conversationId: String, body: String): Pair<List<String>, List<String>> {
    val info = RcsConferenceEvents.parseConferenceInfo(body) ?: return emptyList<String>() to emptyList()
    val sub = eventSubs[conversationId]
    if (sub != null && info.version <= sub.lastVersion) return emptyList<String>() to emptyList()
    if (sub != null) eventSubs[conversationId] = sub.copy(lastVersion = info.version)
    val added = info.users.map { it.uri }.filter { it.isNotBlank() }
    val removed = RcsConferenceEvents.deletedUris(body)
    // Reconcile hosted-focus membership when the NOTIFY targets our focus.
    val hosted = _sessions.value[conversationId]?.takeIf { it.isFocus }
    if (hosted != null) {
        focusMembersMap[hosted.remoteUri]?.let { set ->
            set.removeAll(removed.toSet())
            set.addAll(added)
        }
    }
    return added to removed
}

/** Record an inbound SUBSCRIBE to our hosted focus (NOTIFY target). */
fun RcsSessionManager.noteFocusSubscriber(subscriberCallId: String, focusUri: String) {
    focusSubscribers[subscriberCallId] = focusUri
}

/** Drop a focus subscriber (unsubscribe / expiry). */
fun RcsSessionManager.dropFocusSubscriber(subscriberCallId: String) {
    focusSubscribers.remove(subscriberCallId)
}

/**
 * Publish `conference-info` NOTIFYs to all subscribers of [focusUri]
 * (§3.1 publish side). Best-effort per subscriber; never throws.
 */
suspend fun RcsSessionManager.publishConferenceInfo(focusUri: String) {
    if (!RcsFeature.enabled || !RcsSipTransport.canSend()) return
    val members = focusMembersMap[focusUri]?.toList() ?: return
    val subscribers = focusSubscribers.entries.filter { it.value == focusUri }
    if (subscribers.isEmpty()) return
    val version = (focusVersions[focusUri] ?: 0) + 1
    focusVersions[focusUri] = version
    val body = RcsConferenceEvents.buildConferenceInfo(focusUri, version, members)
        .toByteArray(Charsets.UTF_8)
    val cfg = RcsSipTransport.lastConfigSnapshot()
    val from = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
    for ((subCallId, _) in subscribers) {
        runCatching {
            val branch = RcsSipDialog.newBranch()
            val headers = buildString {
                append("Via: SIP/2.0/TCP local;branch=$branch\r\n")
                append("Max-Forwards: 70\r\n")
                append("From: <$from>;tag=${UUID.randomUUID().toString().take(8)}\r\n")
                append("To: <$focusUri>\r\n")
                append("Call-ID: $subCallId\r\n")
                append("CSeq: 1 NOTIFY\r\n")
                append("Event: conference\r\n")
                append("Subscription-State: active;expires=3600\r\n")
                append("Content-Type: application/conference-info+xml\r\n")
                append("Content-Length: ${body.size}\r\n")
            }
            RcsSipTransport.sendSipMessage("NOTIFY $focusUri SIP/2.0", headers, body)
        }
    }
}

/**
 * REFER one peer to our hosted focus: `REFER sip:peer` with `Refer-To:
 * <focusUri>` + `Referred-By` us. Their client INVITEs the focus URI back.
 */
internal suspend fun RcsSessionManager.sendReferToFocus(
    peer: String,
    focusUri: String,
    subject: String,
): Boolean {
    val cfg = RcsSipTransport.lastConfigSnapshot()
    val from = cfg?.publicUserId?.takeIf { it.isNotBlank() } ?: "sip:local@rcs"
    val branch = RcsSipDialog.newBranch()
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
