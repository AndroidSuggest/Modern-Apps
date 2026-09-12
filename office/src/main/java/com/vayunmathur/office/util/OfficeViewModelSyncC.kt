package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.vayunmathur.office.R

// --- Sync C: members / key epochs / ownership / titles (split from OfficeViewModel.kt for file length) ---

/** This device's role in the open document (owner/editor/viewer). */
fun OfficeViewModel.currentDocRole(): String = currentRole

/** The open document's online id, or null if it isn't a cloud document. */
fun OfficeViewModel.currentOnlineDocId(): String? = currentDocId

/** The stored online title of the open document, if any. */
internal fun OfficeViewModel.currentOnlineTitle(): String? =
    currentDocId?.let { id -> _onlineDocs.value.firstOrNull { it.docId == id }?.title }

/** Returns a copy of [doc] with a new [title] (works across all document types). */
internal fun OfficeViewModel.withTitle(doc: OdfDocument, title: String): OdfDocument = when (doc) {
    is OdfDocument.TextDocument -> doc.copy(title = title)
    is OdfDocument.Spreadsheet -> doc.copy(title = title)
    is OdfDocument.Presentation -> doc.copy(title = title)
    is OdfDocument.Drawing -> doc.copy(title = title)
}

/** True if the local user may edit the open document. */
fun OfficeViewModel.canEditCurrent(): Boolean = currentDocId == null || OfficeRoles.canEdit(currentRole)

/** Canonical bytes an owner signs for a member record (binds id+role to the doc). */
internal fun OfficeViewModel.memberSigningBytes(docId: String, m: OfficeMember): ByteArray =
    "$docId|${m.id}|${m.role}".encodeToByteArray()

/** Owner-signs and appends member records to the (encrypted) members channel. Owner only. */
internal suspend fun OfficeViewModel.recordMembers(docId: String, key: ByteArray, members: List<OfficeMember>) {
    if (currentRole != OfficeRoles.OWNER) return
    writeMembers(docId, key, members)
}

/** Signs + appends member records without the open-doc owner guard. Callers must be the owner
 *  (e.g. verified via the stored meta's `owner` flag) since signing uses this device's key. */
internal suspend fun OfficeViewModel.writeMembers(docId: String, key: ByteArray, members: List<OfficeMember>) {
    runCatching {
        val items = members.map { m ->
            val sig = Base64.encode(OfficeSync.sign(memberSigningBytes(docId, m)))
            syncJson.encodeToString(SignedMember(m, sig))
        }
        OfficeSync.appendDocActions("members:$docId", key, items)
    }
}

/** Reads + verifies the roster: only records with a valid OWNER signature are honored. */
internal suspend fun OfficeViewModel.fetchMembers(docId: String, key: ByteArray, ownerKey: ByteArray?): List<OfficeMember> {
    if (ownerKey == null) return emptyList()
    val res = OfficeSync.pullDocActions("members:$docId", key, 0)
    val byId = LinkedHashMap<String, OfficeMember>()
    for (item in res.items) {
        val sm = runCatching { syncJson.decodeFromString<SignedMember>(item) }.getOrNull() ?: continue
        val ok = OfficeSync.verify(ownerKey, memberSigningBytes(docId, sm.member), Base64.decode(sm.sig))
        if (!ok) continue // not signed by the owner -> ignore (client-enforced authority)
        byId[sm.member.id] = sm.member
    }
    currentMembers.clear()
    byId.values.forEach { currentMembers[it.id] = it.role }
    return byId.values.toList()
}

/** Loads the people who have access to the currently open document (for the share menu). */
fun OfficeViewModel.documentMembers(onResult: (List<OfficeMember>) -> Unit) {
    val docId = currentDocId
    val key = currentDocKey
    if (docId == null || key == null) { onResult(emptyList()); return }
    viewModelScope.launch(Dispatchers.IO) {
        val members = runCatching { OfficeSync.init(getApplication()); fetchMembers(docId, key, currentOwnerKey) }.getOrDefault(emptyList())
        withContext(Dispatchers.Main) { onResult(members) }
    }
}

/** Owner sets/changes a member's role (editor/viewer) or revokes (role="revoked"). Owner only. */
fun OfficeViewModel.setMemberRole(memberId: String, role: String, onResult: (Boolean) -> Unit = {}) {
    val docId = currentDocId; val key = currentDocKey
    if (docId == null || key == null || currentRole != OfficeRoles.OWNER) { onResult(false); return }
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            OfficeSync.init(getApplication())
            recordMembers(docId, key, listOf(OfficeMember(memberId, "", role)))
            // Revoking read access requires rotating the content key so the removed member can no
            // longer decrypt new content.
            if (role == OfficeRoles.REVOKED) rotateKey(docId)
        }
        onResult(true)
    }
}

internal fun OfficeViewModel.epochSigningBytes(docId: String, epoch: Int, wraps: Map<String, String>): ByteArray =
    (listOf(docId, epoch.toString()) + wraps.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" }).joinToString("|").encodeToByteArray()

/**
 * Resolves the current content key: the highest owner-signed [KeyEpoch] this device can unseal,
 * falling back to the epoch-0 (invite) key. Returns (epoch, key).
 */
internal suspend fun OfficeViewModel.fetchCurrentKey(docId: String, inviteKey: ByteArray, ownerKey: ByteArray?): Pair<Int, ByteArray> {
    if (ownerKey == null) return 0 to inviteKey
    var bestEpoch = 0; var bestKey = inviteKey
    for (blob in runCatching { OfficeSync.pullRaw("keys:$docId", 0) }.getOrDefault(emptyList())) {
        val ke = runCatching { syncJson.decodeFromString<KeyEpoch>(Base64.decode(blob).decodeToString()) }.getOrNull() ?: continue
        if (!OfficeSync.verify(ownerKey, epochSigningBytes(docId, ke.epoch, ke.wraps), Base64.decode(ke.sig))) continue
        val myWrap = ke.wraps[OfficeSync.deviceId] ?: continue
        val k = runCatching { OfficeSync.unseal(Base64.decode(myWrap)) }.getOrNull() ?: continue
        if (ke.epoch >= bestEpoch) { bestEpoch = ke.epoch; bestKey = k }
    }
    return bestEpoch to bestKey
}

/** Owner mints a new content key sealed to the remaining members and re-baselines the doc under it. */
internal suspend fun OfficeViewModel.rotateKey(docId: String) {
    if (currentRole != OfficeRoles.OWNER) return
    val oldKey = currentDocKey ?: return
    val members = runCatching { fetchMembers(docId, oldKey, currentOwnerKey) }.getOrDefault(emptyList())
        .filter { it.role != OfficeRoles.REVOKED }
    val newEpoch = currentEpoch + 1
    val newKey = OfficeSync.newDocumentKey()
    val wraps = HashMap<String, String>()
    for (m in members) {
        val bundle = memberKey(m.id) ?: continue
        wraps[m.id] = Base64.encode(OfficeSync.seal(bundle, newKey))
    }
    // Always include ourselves so the owner keeps access even if not yet in the roster fetch.
    wraps[OfficeSync.deviceId] = Base64.encode(OfficeSync.seal(OfficeSync.publicBundle, newKey))
    val sig = Base64.encode(OfficeSync.sign(epochSigningBytes(docId, newEpoch, wraps)))
    val record = Base64.encode(syncJson.encodeToString(KeyEpoch(newEpoch, wraps, sig)).encodeToByteArray())
    OfficeSync.appendRaw("keys:$docId", listOf(record))
    // Switch to the new key and re-baseline: push the full current CRDT under the new key so
    // remaining members rebuild from it (their old-key ops still apply; new ops use the new key).
    currentEpoch = newEpoch
    currentDocKey = newKey
    val crdt = currentTree
    if (crdt != null) {
        val opsJson = crdt.toStateNodesJson()
        val opSig = Base64.encode(OfficeSync.sign(opsJson.encodeToByteArray()))
        OfficeSync.appendDocActions(docId, newKey, listOf(syncJson.encodeToString(SignedOp(OfficeSync.deviceId, opSig, opsJson))))
    }
    // Restart the live loop on the new key.
    startLive(docId, newKey)
}

internal fun OfficeViewModel.titleSigningBytes(docId: String, title: String): ByteArray = "$docId|title|$title".encodeToByteArray()

internal fun OfficeViewModel.ownerSigningBytes(docId: String, newOwnerId: String, newOwnerKeyB64: String): ByteArray =
    "$docId|owner|$newOwnerId|$newOwnerKeyB64".encodeToByteArray()

/** Follows the signed ownership-transfer chain from [baseOwnerKeyB64] to the current owner key. */
internal suspend fun OfficeViewModel.fetchOwnerKey(docId: String, baseOwnerKeyB64: String): ByteArray? {
    var ownerKey = baseOwnerKeyB64.takeIf { it.isNotBlank() }?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
    val transfers = runCatching { OfficeSync.pullRaw("owner:$docId", 0) }.getOrDefault(emptyList())
        .mapNotNull { runCatching { syncJson.decodeFromString<OwnerTransfer>(Base64.decode(it).decodeToString()) }.getOrNull() }
    val used = HashSet<Int>()
    var advanced = true
    while (advanced) {
        advanced = false
        for ((idx, t) in transfers.withIndex()) {
            if (idx in used) continue
            if (OfficeSync.verify(ownerKey, ownerSigningBytes(docId, t.newOwnerId, t.newOwnerKey), Base64.decode(t.sig))) {
                ownerKey = runCatching { Base64.decode(t.newOwnerKey) }.getOrNull() ?: continue
                used.add(idx); advanced = true
            }
        }
    }
    return ownerKey
}

/** Transfers ownership of the open document to [memberId] (owner only). */
fun OfficeViewModel.transferOwnership(memberId: String, onResult: (Boolean) -> Unit = {}) {
    val docId = currentDocId; val key = currentDocKey
    if (docId == null || key == null || currentRole != OfficeRoles.OWNER) { onResult(false); return }
    viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching {
            OfficeSync.init(getApplication())
            val newBundle = memberKey(memberId) ?: return@runCatching false
            val newKeyB64 = Base64.encode(newBundle)
            // Last owner-signed roster change: promote the new owner, demote self to editor.
            recordMembers(docId, key, listOf(
                OfficeMember(memberId, "", OfficeRoles.OWNER),
                OfficeMember(OfficeSync.deviceId, myName(), OfficeRoles.EDITOR),
            ))
            val sig = Base64.encode(OfficeSync.sign(ownerSigningBytes(docId, memberId, newKeyB64)))
            OfficeSync.appendRaw("owner:$docId", listOf(Base64.encode(syncJson.encodeToString(OwnerTransfer(memberId, newKeyB64, sig)).encodeToByteArray())))
            // We are no longer the owner.
            currentOwnerKey = newBundle
            currentRole = OfficeRoles.EDITOR
            true
        }.getOrDefault(false)
        withContext(Dispatchers.Main) { onResult(ok) }
    }
}

/** Reads the latest owner-signed document title, if the owner ever renamed it. */
internal suspend fun OfficeViewModel.fetchTitle(docId: String, key: ByteArray, ownerKey: ByteArray?): String? {
    if (ownerKey == null) return null
    val res = OfficeSync.pullDocActions("title:$docId", key, 0)
    var latest: String? = null
    for (item in res.items) {
        val st = runCatching { syncJson.decodeFromString<SignedTitle>(item) }.getOrNull() ?: continue
        if (OfficeSync.verify(ownerKey, titleSigningBytes(docId, st.title), Base64.decode(st.sig))) latest = st.title
    }
    return latest
}

/** Applies an owner rename to the currently-open document (title bar + index) if it changed. */
internal suspend fun OfficeViewModel.pollTitle(docId: String, key: ByteArray) {
    if (currentDocId != docId) return
    val t = fetchTitle(docId, key, currentOwnerKey) ?: return
    val cur = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return
    if (cur.title == t) return
    withContext(Dispatchers.Main) {
        (state.value as? OfficeViewModel.ViewState.Loaded)?.document?.let { if (it.title != t) _state.value = ViewState.Loaded(withTitle(it, t)) }
    }
    val ds = DataStoreUtils.getInstance(getApplication())
    indexMutex.withLock {
        val index = loadIndex(ds).associateBy { it.docId }.toMutableMap()
        index[docId]?.let { if (it.title != t) { index[docId] = it.copy(title = t); saveIndex(ds, index.values.toList()) } }
    }
}

/** Owner renames the open online document; the new name is published (signed) to all members. */
fun OfficeViewModel.renameDocument(newName: String, onResult: (Boolean) -> Unit = {}) {
    val docId = currentDocId; val key = currentDocKey
    val name = newName.trim()
    if (docId == null || key == null || currentRole != OfficeRoles.OWNER || name.isBlank()) { onResult(false); return }
    viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching {
            OfficeSync.init(getApplication())
            val ds = DataStoreUtils.getInstance(getApplication())
            val sig = Base64.encode(OfficeSync.sign(titleSigningBytes(docId, name)))
            OfficeSync.appendDocActions("title:$docId", key, listOf(syncJson.encodeToString(SignedTitle(name, sig))))
            indexMutex.withLock {
                val index = loadIndex(ds).associateBy { it.docId }.toMutableMap()
                index[docId]?.let { index[docId] = it.copy(title = name); saveIndex(ds, index.values.toList()) }
            }
            withContext(Dispatchers.Main) {
                (state.value as? OfficeViewModel.ViewState.Loaded)?.document?.let { _state.value = ViewState.Loaded(withTitle(it, name)) }
            }
            true
        }.getOrDefault(false)
        withContext(Dispatchers.Main) { onResult(ok) }
    }
}

/** Verifies + returns the pubkey for a member id (cached). */
internal suspend fun OfficeViewModel.memberKey(id: String): ByteArray? =
    memberKeyCache[id] ?: OfficeSync.getKey(id)?.also { memberKeyCache[id] = it }

/** This device's own display name (for the roster/presence). */
fun OfficeViewModel.myDisplayName(): String = myName()
