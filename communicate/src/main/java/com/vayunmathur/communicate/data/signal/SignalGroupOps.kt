package com.vayunmathur.communicate.data.signal

import android.util.Log
import com.vayunmathur.communicate.data.signal.transport.SignalGroupsApi
import kotlinx.coroutines.launch
import org.signal.storageservice.storage.protos.groups.GroupChange

/**
 * SignalClient group operations (split from SignalClient.kt for file length).
 *
 * Extension functions on [SignalClient]; behavior identical, call sites unchanged.
 */

suspend fun SignalClient.createGroup(subject: String, contacts: List<String>): String? {
    val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
    val groupId = SignalProtocol.toConversationId("", masterKey)
    val requestBody = SignalGroups.buildCreateGroupRequest(masterKey, subject, contacts, revision = 0)
    val auth = authData ?: return null
    val ok = SignalGroups.putNewGroup(authData = auth, requestBody = requestBody, sslSocketFactory = signalTls())
    _events.emit(SignalEvent.ConversationUpdate(conversationId = groupId, peerName = subject, peerPhone = null, avatarUrl = null, lastPreview = null, lastTimestamp = System.currentTimeMillis(), unreadCount = 0, isGroup = true, participantCount = contacts.size))
    try {
        // The master key must be kept: without it we cannot address the group again.
        db?.conversationDao()?.upsert(
            SignalConversation(
                chatId = groupId,
                isGroup = true,
                name = subject,
                participants = contacts.joinToString(","),
                groupMasterKey = masterKey,
                groupRevision = 0,
            ),
        )
        Log.i(TAG, "createGroup $groupId (PUT /v2/groups/ ${if (ok) "ok" else "failed"})")
    } catch (_: Exception) {}
    return groupId
}

suspend fun SignalClient.setGroupName(conversationId: String, name: String): Boolean {
    val existing = try { db?.conversationDao()?.getConversation(conversationId) } catch (_: Exception) { null }
    val masterKey = existing?.groupMasterKey
    if (masterKey == null) {
        // Previously this generated a fresh random master key per rename, which described an
        // unrelated group rather than this one.
        Log.w(TAG, "no stored master key for $conversationId, cannot rename the group")
        return false
    }
    val revision = existing.groupRevision + 1
    val accepted = groupChange(masterKey) { secretParams ->
        SignalGroupsApi.titleChange(secretParams, name, revision)
    }
    if (!accepted) {
        // The local name is not updated on rejection: claiming success for a change the server refused is
        // how the group drifts out of step with everyone else's view of it.
        Log.w(TAG, "the server rejected renaming $conversationId")
        return false
    }
    _events.emit(SignalEvent.ConversationNameChanged(conversationId = conversationId, newName = name))
    try {
        db?.conversationDao()?.upsert(existing.copy(name = name, groupRevision = revision))
    } catch (_: Exception) {}
    return true
}

/**
 * Submit a group change built by [build], handling the credential dance.
 *
 * Returns whether the server accepted it, so callers can avoid recording a change that did not happen.
 */
private suspend fun SignalClient.groupChange(
    masterKey: ByteArray,
    build: (org.signal.libsignal.zkgroup.groups.GroupSecretParams) -> GroupChange.Actions?,
): Boolean {
    val auth = authData ?: return false
    return try {
        val secretParams = org.signal.libsignal.zkgroup.groups.GroupSecretParams.deriveFromMasterKey(
            org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey),
        )
        val actions = build(secretParams) ?: return false
        val credential = SignalGroupsApi
            .fetchCredentials(basicAuthHeader(), signalTls())
            .minByOrNull { kotlin.math.abs(it.redemptionTimeSeconds - System.currentTimeMillis() / 1000) }
            ?: return false
        val authorization = SignalGroupsApi.authorizationFor(auth, secretParams, credential) ?: return false
        SignalGroupsApi.patchGroup(authorization, actions, signalTls())
    } catch (t: Throwable) {
        Log.w(TAG, "could not submit a group change", t)
        false
    }
}

suspend fun SignalClient.updateGroupParticipants(conversationId: String, participantIds: List<String>, action: String): Boolean {
    val existing = try { db?.conversationDao()?.getConversation(conversationId) } catch (_: Exception) { null }
    val masterKey = existing?.groupMasterKey
    if (masterKey == null) {
        Log.w(TAG, "no stored master key for $conversationId, cannot change membership")
        return false
    }
    val revision = existing.groupRevision + 1
    val accepted = when (action) {
        "add" -> groupChange(masterKey) { secretParams ->
            SignalGroupsApi.addMembersChange(secretParams, participantIds, revision)
        }
        else -> {
            // Removal needs DeleteMemberAction, which is not built yet; refuse rather than pretend.
            Log.w(TAG, "removing members from a Signal group is not implemented")
            false
        }
    }
    if (!accepted) {
        Log.w(TAG, "the server rejected the membership change for $conversationId")
        return false
    }
    for (pid in participantIds) {
        if (action == "add") _events.emit(SignalEvent.ParticipantAdded(conversationId = conversationId, participantId = pid))
        else _events.emit(SignalEvent.ParticipantRemoved(conversationId = conversationId, participantId = pid))
    }
    return true
}

/**
 * Refresh a group's membership and title from the server.
 *
 * This is what makes a reply reach the whole group: membership learned from inbound messages only covers
 * whoever has spoken, whereas the server knows everyone. Returns false when the group could not be fetched,
 * leaving the locally-known membership in place rather than emptying it.
 */
suspend fun SignalClient.refreshGroup(conversationId: String): Boolean {
    val auth = authData ?: return false
    val dao = db?.conversationDao() ?: return false
    val existing = try { dao.getConversation(conversationId) } catch (_: Exception) { null } ?: return false
    val masterKey = existing.groupMasterKey ?: return false
    return try {
        val secretParams = org.signal.libsignal.zkgroup.groups.GroupSecretParams.deriveFromMasterKey(
            org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey),
        )
        val credential = SignalGroupsApi
            .fetchCredentials(basicAuthHeader(), signalTls())
            // Credentials come a week at a time; the one for today is the only one the server accepts now.
            .minByOrNull { kotlin.math.abs(it.redemptionTimeSeconds - System.currentTimeMillis() / 1000) }
            ?: return false
        val authorization = SignalGroupsApi.authorizationFor(auth, secretParams, credential) ?: return false
        val group = SignalGroupsApi.fetchGroup(authorization, signalTls()) ?: return false
        val state = SignalGroupsApi.decryptGroup(secretParams, group) ?: return false
        dao.upsert(
            existing.copy(
                isGroup = true,
                name = state.title.ifBlank { existing.name },
                participants = state.memberAcis.joinToString(","),
                groupRevision = state.revision,
            ),
        )
        Log.i(
            TAG,
            "refreshed $conversationId: \"${state.title}\" revision=${state.revision} " +
                "members=${state.memberAcis.size} pending=${state.pendingCount}",
        )
        true
    } catch (t: Throwable) {
        Log.w(TAG, "could not refresh the group $conversationId", t)
        false
    }
}

/** The group authorization a calling request needs, derived from the group behind [groupId]. */
internal suspend fun SignalClient.groupAuthorization(groupId: ByteArray): String? {
    val auth = authData ?: return null
    val conversation = conversationForGroupId(groupId) ?: return null
    val masterKey = conversation.groupMasterKey ?: return null
    val secretParams = org.signal.libsignal.zkgroup.groups.GroupSecretParams.deriveFromMasterKey(
        org.signal.libsignal.zkgroup.groups.GroupMasterKey(masterKey),
    )
    val credential = SignalGroupsApi
        .fetchCredentials(basicAuthHeader(), signalTls())
        .minByOrNull { kotlin.math.abs(it.redemptionTimeSeconds - System.currentTimeMillis() / 1000) }
        ?: return null
    return SignalGroupsApi.authorizationFor(auth, secretParams, credential)
}

internal suspend fun SignalClient.conversationForGroupId(groupId: ByteArray) = try {
    db?.conversationDao()?.getConversation(SignalProtocol.groupConversationId(groupId))
} catch (_: Exception) {
    null
}

/**
 * Record a group we received a message from, so we can reply to it.
 *
 * Membership is learned incrementally from whoever has spoken: the envelope names only its sender, and the
 * full member list comes from the server's group state. Until that is fetched, a reply reaches everyone we
 * have heard from rather than everyone in the group — which is a real limitation, but strictly better than
 * refusing to send at all.
 */
internal suspend fun SignalClient.rememberInboundGroup(conversationId: String, masterKey: ByteArray, senderAci: String) {
    val dao = db?.conversationDao() ?: return
    try {
        val existing = dao.getConversation(conversationId)
        val members = buildSet {
            existing?.participants?.split(",")?.forEach { entry ->
                entry.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
            add(senderAci)
            // Ourselves: a fan-out send skips our own address, but membership should still be accurate.
            authData?.aci?.takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        dao.upsert(
            (existing ?: SignalConversation(chatId = conversationId)).copy(
                isGroup = true,
                groupMasterKey = masterKey,
                participants = members.joinToString(","),
            ),
        )
        // Now that the master key is known, ask the server for the real membership. Best-effort: the
        // message is already usable with what we learned from the envelope.
        scope.launch { refreshGroup(conversationId) }
    } catch (t: Throwable) {
        Log.w(TAG, "could not record the group behind $conversationId", t)
    }
}
