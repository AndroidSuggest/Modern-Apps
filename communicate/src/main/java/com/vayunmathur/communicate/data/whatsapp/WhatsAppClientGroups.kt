package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient.State
import com.vayunmathur.communicate.data.whatsapp.buildCreateGroup
import com.vayunmathur.communicate.data.whatsapp.buildGroupInfoChange
import com.vayunmathur.communicate.data.whatsapp.buildGroupParticipantChange
import com.vayunmathur.communicate.data.whatsapp.buildGroupParticipantsQuery
import com.vayunmathur.communicate.data.whatsapp.buildSetGroupTopic
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.generateMessageId
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Query a group's participant JIDs via an interactive w:g2 query.
 * Ref whatsmeow group.go getGroupInfo.
 */
internal suspend fun WhatsAppClient.queryGroupParticipants(groupJid: String): List<String> {
    val iq = WhatsAppProtocol.buildGroupParticipantsQuery(groupJid, generateMessageId())
    val resp = sendIqAndWait(iq) ?: return emptyList()
    val group = resp.getChildByTag("group") ?: return emptyList()
    return group.getChildren().filter { it.tag == "participant" }.mapNotNull { it.attrs["jid"] }
}

/**
 * Fetch a group's subject + participants (w:g2 interactive query) and publish a
 * [WhatsAppEvent.ConversationUpdate] so the thread shows up named + flagged as a group. Freshly
 * joined groups aren't in history sync, so without this the conversation never appears (and
 * group messages, which only upsert a bare row, look nameless / like a 1:1). Fetched at most
 * once per group per session ([knownGroups]); does NOT touch the unread badge on re-entry.
 * Ref whatsmeow group.go GetGroupInfo.
 */
internal suspend fun WhatsAppClient.fetchAndEmitGroupInfo(groupJid: String) {
    if (!groupJid.contains("@g.us")) return
    if (!knownGroups.add(groupJid)) return
    val iq = WhatsAppProtocol.buildGroupParticipantsQuery(groupJid, generateMessageId())
    val resp = sendIqAndWait(iq) ?: run { knownGroups.remove(groupJid); return }
    val group = resp.getChildByTag("group") ?: run { knownGroups.remove(groupJid); return }
    val subject = group.attrs["subject"]?.ifEmpty { null }
    val participantJids = group.getChildren()
        .filter { it.tag == "participant" }
        .mapNotNull { it.attrs["jid"] }
    val participantNames = participantJids.map { resolveName(it) }
    val serviceData = if (participantNames.isNotEmpty()) {
        org.json.JSONObject()
            .put("participantNames", org.json.JSONArray(participantNames))
            .toString()
    } else null
    _events.emit(WhatsAppEvent.ConversationUpdate(
        source = MessageSource.WHATSAPP,
        conversationId = "wa:$groupJid",
        peerName = subject,
        peerPhone = null,
        avatarUrl = null,
        lastPreview = null,
        lastTimestamp = 0L,
        unreadCount = 0,
        isGroup = true,
        participantCount = participantJids.size,
        serviceData = serviceData,
    ))
    // Phase F 1e: best-effort MEX enrichment beyond the classic w:g2 path (subject/description).
    // Dev-gated; no-ops when the op's doc_id isn't in the bundled persist map.
    if (WhatsAppFeature.enabled && subject == null) {
        runCatching { enrichGroupFromMex(groupJid, participantJids.size) }
    }
}

/**
 * Enrich a group thread via `xwa2_group_query_by_id` (Phase F 1e). Emits a follow-up
 * [WhatsAppEvent.ConversationUpdate] with the MEX-reported subject when available. Best-effort:
 * silently returns on transport error / missing doc_id.
 */
internal suspend fun WhatsAppClient.enrichGroupFromMex(groupJid: String, participantCount: Int) {
    val res = com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps.groupQueryById(appContext, groupJid)
    if (!res.isSuccess) return
    val data = res.data ?: return
    val group = data.optJSONObject("xwa2_group_query_by_id") ?: return
    val subject = group.optJSONObject("subject")?.optString("subject")?.ifEmpty { null }
        ?: group.optString("subject").ifEmpty { null } ?: return
    _events.emit(WhatsAppEvent.ConversationUpdate(
        source = MessageSource.WHATSAPP,
        conversationId = "wa:$groupJid",
        peerName = subject,
        peerPhone = null,
        avatarUrl = null,
        lastPreview = null,
        lastTimestamp = 0L,
        unreadCount = 0,
        isGroup = true,
        participantCount = participantCount,
    ))
}

/**
 * Set group name.
 * From Go HandleMatrixRoomName / SetGroupName.
 */
suspend fun WhatsAppClient.setGroupName(conversationId: String, name: String): Boolean {
    if (_state.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val groupJid = extractJid(conversationId) ?: return false
    if (!groupJid.contains("@g.us")) return false

    val id = WhatsAppProtocol.generateMessageId(authData?.wid)
    val node = WhatsAppProtocol.buildGroupInfoChange(groupJid, "subject", name, id)
    return ws.send(WhatsAppProtocol.encodeNode(node))
}

/**
 * Set group topic/description with old/new ID tracking.
 * From Go HandleMatrixRoomTopic / SetGroupTopic.
 */
suspend fun WhatsAppClient.setGroupTopic(
    conversationId: String,
    topic: String,
    previousTopicId: String? = null,
): Boolean {
    if (_state.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val groupJid = extractJid(conversationId) ?: return false
    if (!groupJid.contains("@g.us")) return false

    val id = WhatsAppProtocol.generateMessageId(authData?.wid)
    val node = WhatsAppProtocol.buildSetGroupTopic(groupJid, topic, id, previousTopicId)
    return ws.send(WhatsAppProtocol.encodeNode(node))
}

/**
 * Set group avatar with crop/resize/JPEG conversion.
 * Crops to square, scales between 190-720px, encodes as JPEG quality 75.
 * From Go HandleMatrixRoomAvatar / convertRoomAvatar.
 */
suspend fun WhatsAppClient.setGroupAvatar(conversationId: String, imageBytes: ByteArray): Boolean {
    if (_state.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val groupJid = extractJid(conversationId) ?: return false
    if (!groupJid.contains("@g.us")) return false

    val processed = withContext(Dispatchers.Default) {
        cropResizeAvatar(imageBytes)
    } ?: return false

    val id = WhatsAppProtocol.generateMessageId(authData?.wid)
    val node = WhatsAppProtocol.Node(
        tag = "iq",
        attrs = mapOf(
            "id" to id,
            "type" to "set",
            "xmlns" to "w:profile:picture",
            "to" to groupJid,
        ),
        content = listOf(
            WhatsAppProtocol.Node(
                tag = "picture",
                attrs = mapOf("type" to "image"),
                data = processed,
            )
        ),
    )
    return ws.send(WhatsAppProtocol.encodeNode(node))
}

internal fun WhatsAppClient.cropResizeAvatar(imageBytes: ByteArray): ByteArray? {
    val original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
    val size = minOf(original.width, original.height)
    val x = (original.width - size) / 2
    val y = (original.height - size) / 2
    val cropped = Bitmap.createBitmap(original, x, y, size, size)
    // Go: min=190, max=720, BiLinear scaling
    val minDim = 190
    val maxDim = 720
    val targetDim = size.coerceIn(minDim, maxDim)
    val scaled = if (size != targetDim) {
        cropped.scale(targetDim, targetDim).also {
            if (it !== cropped) cropped.recycle()
        }
    } else cropped
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
    if (scaled !== original) scaled.recycle()
    if (original !== cropped && original !== scaled) original.recycle()
    return out.toByteArray()
}

/**
 * Add or remove group members.
 * From Go HandleMatrixMembership / UpdateGroupParticipants.
 */
suspend fun WhatsAppClient.updateGroupParticipants(
    conversationId: String,
    participantJids: List<String>,
    action: String,
): Boolean {
    if (_state.value !is State.Connected) return false
    val ws = webSocket ?: return false
    val groupJid = extractJid(conversationId) ?: return false
    if (!groupJid.contains("@g.us")) return false

    val validActions = setOf("add", "remove", "promote", "demote")
    if (action !in validActions) return false

    val id = WhatsAppProtocol.generateMessageId(authData?.wid)
    val node = WhatsAppProtocol.buildGroupParticipantChange(groupJid, participantJids, action, id)
    return ws.send(WhatsAppProtocol.encodeNode(node))
}

/**
 * Create a new group with [subject] and [participantJids] (full user JIDs, e.g.
 * `15551234567@s.whatsapp.net`). Sends the `w:g2` create IQ, awaits the `<group>` result, and
 * returns the new group's `@g.us` JID (or null on failure). On success it also fetches + emits
 * the group info so the thread appears named + flagged with its participants persisted.
 * Ref whatsmeow group.go CreateGroup.
 */
suspend fun WhatsAppClient.createGroup(subject: String, participantJids: List<String>): String? {
    if (_state.value !is State.Connected) { WhatsAppDiag.log(TAG, "createGroup: not connected"); return null }
    if (participantJids.isEmpty()) { WhatsAppDiag.log(TAG, "createGroup: no participants"); return null }

    val id = generateMessageId()
    // whatsmeow uses a client-chosen unique key (unix seconds) echoed back in the response.
    val key = (System.currentTimeMillis() / 1000).toString()
    val iq = WhatsAppProtocol.buildCreateGroup(subject, participantJids, id, key)
    val resp = sendIqAndWait(iq) ?: run { WhatsAppDiag.log(TAG, "createGroup: no response"); return null }
    if (resp.attrs["type"] == "error") {
        val err = resp.getChildByTag("error")
        WhatsAppDiag.log(
            TAG,
            "createGroup: server error code=${err?.attrs?.get("code")} text=${err?.attrs?.get("text")}",
        )
        return null
    }
    val group = resp.getChildByTag("group") ?: run { WhatsAppDiag.log(TAG, "createGroup: no <group>"); return null }
    // Response group node carries the new JID either as `jid` or as a bare `id` local-part.
    val groupJid = group.attrs["jid"]
        ?: group.attrs["id"]?.let { if (it.contains("@")) it else "$it@g.us" }
        ?: run { WhatsAppDiag.log(TAG, "createGroup: no group jid in resp"); return null }
    WhatsAppDiag.log(TAG, "createGroup: created $groupJid")
    runCatching { fetchAndEmitGroupInfo(groupJid) }
    return groupJid
}
