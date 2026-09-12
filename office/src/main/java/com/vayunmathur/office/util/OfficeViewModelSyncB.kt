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

// --- Sync B: device/index/inbox/join-requests/trees/share/open (split from OfficeViewModel.kt for file length) ---

/** This device's sync id. Empty until [initSync] has run. */
val OfficeViewModel.syncDeviceId: String get() = OfficeSync.deviceId

/** Registers this device, loads the local online index, and pulls any new invites. */
fun OfficeViewModel.initSync() {
    viewModelScope.launch(Dispatchers.IO) {
        // Gate inside the coroutine so we don't miss the cold-start window where the persisted
        // enabled-state flow hasn't loaded yet: an already-provisioned device (keys+id present)
        // counts as enabled even if the flow still reads false. New/un-opted-in devices no-op.
        if (!onlineEnabled.value && !hasOnlineIdentity()) return@launch
        if (!onlineEnabled.value) _onlineEnabled.value = true
        runCatching {
            OfficeSync.init(getApplication())
            val ds = DataStoreUtils.getInstance(getApplication())
            _onlineDocs.value = loadIndex(ds)
            _pendingRequests.value = loadRequests(ds)
        }
        refreshOnline()
    }
}

internal suspend fun OfficeViewModel.loadIndex(ds: DataStoreUtils): List<OfficeDocMeta> =
    ds.getString("officeDocsIndex")
        ?.let { runCatching { syncJson.decodeFromString<List<OfficeDocMeta>>(it) }.getOrNull() }
        ?: emptyList()

internal suspend fun OfficeViewModel.saveIndex(ds: DataStoreUtils, list: List<OfficeDocMeta>) {
    ds.setString("officeDocsIndex", syncJson.encodeToString(list))
    _onlineDocs.value = list
}

internal suspend fun OfficeViewModel.loadRequests(ds: DataStoreUtils): List<OfficeSync.JoinRequest> =
    ds.getString("officePendingRequests")
        ?.let { runCatching { syncJson.decodeFromString<List<OfficeSync.JoinRequest>>(it) }.getOrNull() }
        ?: emptyList()

internal suspend fun OfficeViewModel.saveRequests(ds: DataStoreUtils, list: List<OfficeSync.JoinRequest>) {
    ds.setString("officePendingRequests", syncJson.encodeToString(list))
    _pendingRequests.value = list
}

internal suspend fun OfficeViewModel.removePendingRequest(docId: String, requesterId: String) {
    val ds = DataStoreUtils.getInstance(getApplication())
    requestsMutex.withLock {
        saveRequests(ds, loadRequests(ds).filterNot { it.docId == docId && it.requesterId == requesterId })
    }
}

/** Builds a tappable invite link for a document this device owns (public ids only).
 *  An https link that bounces to `office://join/...`, since messengers won't linkify
 *  a bare custom scheme. The ids ride in the fragment, which browsers never send. */
fun OfficeViewModel.shareLinkFor(docId: String): String =
    "https://ma.vayunmathur.com/of/share#id=$docId&owner=${OfficeSync.deviceId}"

/**
 * Sends a PQC-sealed join request to [ownerId]'s inbox after ensuring this device is registered.
 * Called when the user taps an `office://join` link. [onResult] receives whether it was delivered.
 */
fun OfficeViewModel.requestToJoin(docId: String, ownerId: String, onResult: (Boolean) -> Unit = {}) {
    viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching {
            if (!_onlineEnabled.value) _onlineEnabled.value = true
            OfficeSync.init(getApplication())
            OfficeSync.sendJoinRequest(ownerId, docId, OfficeSync.deviceId, myName())
        }.getOrDefault(false)
        withContext(Dispatchers.Main) { onResult(ok) }
    }
}

/**
 * Approves a pending join request by sealing the existing invite to the requester (reusing the
 * stored [OfficeDocMeta], so the document need not be open) and recording them on the roster.
 */
fun OfficeViewModel.approveJoinRequest(docId: String, requesterId: String, name: String = "", onResult: (Boolean) -> Unit = {}) {
    viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching {
            OfficeSync.init(getApplication())
            val ds = DataStoreUtils.getInstance(getApplication())
            val meta = indexMutex.withLock { loadIndex(ds) }.firstOrNull { it.docId == docId }
            if (meta == null || !meta.owner) return@runCatching false
            val key = Base64.decode(meta.keyB64)
            // Record the new member on the owner-signed roster, then seal the invite to them.
            writeMembers(docId, key, listOf(OfficeMember(requesterId, name, OfficeRoles.EDITOR)))
            OfficeSync.sendInvite(requesterId, docId, key, meta.title, meta.charMode, OfficeRoles.EDITOR, meta.ownerKeyB64, meta.charKind)
        }.getOrDefault(false)
        if (ok) removePendingRequest(docId, requesterId)
        withContext(Dispatchers.Main) { onResult(ok) }
    }
}

/** Drops a pending join request locally without granting access. */
fun OfficeViewModel.denyRequest(docId: String, requesterId: String) {
    viewModelScope.launch(Dispatchers.IO) { removePendingRequest(docId, requesterId) }
}

/** Pulls new invites from this device's inbox and merges them into the online list; refreshes titles. */
fun OfficeViewModel.refreshOnline() {
    if (!_onlineEnabled.value && !hasOnlineIdentity()) return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            OfficeSync.init(getApplication())
            val ds = DataStoreUtils.getInstance(getApplication())
            val cursor = ds.getLong("officeInboxCursor")?.toInt() ?: 0
            val res = OfficeSync.pullInbox(cursor)
            if (res.invites.isNotEmpty()) {
                indexMutex.withLock {
                    val index = loadIndex(ds).associateBy { it.docId }.toMutableMap()
                    for (inv in res.invites) {
                        if (!index.containsKey(inv.docId)) index[inv.docId] = officeDocMetaFromInvite(inv)
                    }
                    saveIndex(ds, index.values.toList())
                }
            }
            if (res.requests.isNotEmpty()) {
                // Only surface requests for docs this device owns; dedup by (docId, requesterId).
                val owned = indexMutex.withLock { loadIndex(ds) }.filter { it.owner }.map { it.docId }.toSet()
                requestsMutex.withLock {
                    val existing = loadRequests(ds).toMutableList()
                    for (r in res.requests) {
                        if (r.docId in owned && existing.none { it.docId == r.docId && it.requesterId == r.requesterId }) {
                            existing.add(r)
                        }
                    }
                    saveRequests(ds, existing)
                }
            }
            ds.setLong("officeInboxCursor", res.seq.toLong())
            // Pick up any owner rename for docs we already have (network done outside the lock).
            val current = indexMutex.withLock { loadIndex(ds) }
            val updates = HashMap<String, String>()
            for (meta in current) {
                val k = runCatching { Base64.decode(meta.keyB64) }.getOrNull() ?: continue
                val ok = meta.ownerKeyB64.takeIf { it.isNotBlank() }?.let { runCatching { Base64.decode(it) }.getOrNull() }
                val t = runCatching { fetchTitle(meta.docId, k, ok) }.getOrNull()
                if (t != null && t != meta.title) updates[meta.docId] = t
            }
            if (updates.isNotEmpty()) indexMutex.withLock {
                val index = loadIndex(ds).associateBy { it.docId }.toMutableMap()
                for ((id, t) in updates) index[id]?.let { index[id] = it.copy(title = t) }
                saveIndex(ds, index.values.toList())
            }
        }
    }
}

// --- CRDT persistence & two-way sync ---

internal suspend fun OfficeViewModel.loadTree(ds: DataStoreUtils, docId: String): DocumentTreeCrdt? =
    ds.getString("crdt:$docId")?.let { DocumentTreeCrdt(OfficeSync.deviceId).apply { loadState(it) } }

internal suspend fun OfficeViewModel.saveTree(ds: DataStoreUtils, docId: String, tree: DocumentTreeCrdt) {
    ds.setString("crdt:$docId", tree.serialize())
}

/** Parses flat-ODF text back into a document via a temp file (tolerant; null on failure). */
internal fun OfficeViewModel.parseFlat(flat: String): OdfDocument? {
    val ctx: Context = getApplication()
    val f = java.io.File(ctx.cacheDir, "crdt_merge.fodt")
    f.writeText(flat)
    return runCatching { DocumentImporter.open(ctx, Uri.fromFile(f), "merge.fodt") }.getOrNull()
}

/**
 * Two-way tree-CRDT sync for the open online document: diff local edits into ops and push them,
 * then pull + merge remote ops. If the merge changed the document, re-render it into the editor.
 */
internal suspend fun OfficeViewModel.syncDoc(docId: String, key: ByteArray) = syncMutex.withLock {
    val ds = DataStoreUtils.getInstance(getApplication())
    val tree = currentTree ?: (loadTree(ds, docId) ?: DocumentTreeCrdt(OfficeSync.deviceId)).also { currentTree = it }
    val cursor = ds.getLong("crdtCursor:$docId")?.toInt() ?: 0
    val localXml = exportFlat()
    val startVersion = editVersion
    val opsJson = tree.updateJson(localXml)
    // Only push signed ops if we're allowed to edit; viewers never push.
    if (opsJson != "[]" && OfficeRoles.canEdit(currentRole)) {
        val sig = Base64.encode(OfficeSync.sign(opsJson.encodeToByteArray()))
        val signed = syncJson.encodeToString(SignedOp(OfficeSync.deviceId, sig, opsJson))
        // Prefer the live WebSocket (peers get it instantly, like presence); HTTP is the fallback.
        if (!OfficeSync.liveAppend(docId, key, listOf(signed))) {
            OfficeSync.appendDocActions(docId, key, listOf(signed))
        }
    }
    // Pull from the OLD cursor so remote ops (and our just-pushed, idempotent ops) are merged.
    val pulled = OfficeSync.pullDocActions(docId, key, cursor)
    for (item in pulled.items) {
        applySignedOp(tree, item)
    }
    ds.setLong("crdtCursor:$docId", pulled.seq.toLong())
    saveTree(ds, docId, tree)
    val mergedXml = tree.render()
    if (mergedXml != localXml) {
        val doc = parseFlat(mergedXml)
        // Don't overwrite the editor if the user typed while this sync was running; the next
        // sync (triggered by that edit) will fold it in and re-merge. Prevents "deletes coming back".
        if (doc != null) withContext(Dispatchers.Main) { if (editVersion == startVersion) updateDocument(doc) }
    }
}

/**
 * Shares the current document with [recipientId]. If it isn't online yet, it is first copied
 * into the online folder (new doc id + content key + CRDT upload), then the invite is sent.
 */
/** Shares with a recipient. onResult receives null on success, or a human-readable error reason. */
fun OfficeViewModel.shareCurrentDocument(recipientId: String, role: String = OfficeRoles.EDITOR, docName: String = "", onResult: (String?) -> Unit = {}) {
    viewModelScope.launch(Dispatchers.IO) {
        val error: String? = try {
            // Registration (key upload) must complete before we share, so peers can find us and
            // decrypt what we send. If it fails, bail out before mutating ownership or sending.
            if (!OfficeSync.init(getApplication())) "Couldn't register your device — check your connection and try again."
            else {
            val ds = DataStoreUtils.getInstance(getApplication())
            val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document
            val firstShare = currentDocId == null
            // The owner names the document when it first goes online; later shares keep that name.
            val title = if (firstShare) docName.trim().ifBlank { doc?.title ?: "Document" }
                else (currentOnlineTitle() ?: doc?.title ?: "Document")
            // The tree CRDT is universal, so there's no per-type "char kind" any more.
            val charKind = ""
            val charMode = true
            val docId = currentDocId ?: OfficeSync.newDocumentId()
            val key = currentDocKey ?: OfficeSync.newDocumentKey()
            if (firstShare) {
                currentRole = OfficeRoles.OWNER
                currentOwnerKey = OfficeSync.publicBundle
                currentMembers[OfficeSync.deviceId] = OfficeRoles.OWNER
                currentEpoch = 0
                currentBaseKey = key
                // Reflect the chosen name in the open editor so its title matches the online doc.
                if (doc != null) withContext(Dispatchers.Main) {
                    (state.value as? OfficeViewModel.ViewState.Loaded)?.document?.let { _state.value = ViewState.Loaded(withTitle(it, title)) }
                }
            }
            when {
                currentRole != OfficeRoles.OWNER -> "Only the owner can share this document."
                OfficeSync.getKey(recipientId) == null ->
                    "That device id isn't registered yet. Ask them to open Office once (Online tab), then try again."
                else -> {
                    currentDocId = docId
                    currentDocKey = key
                    currentCharKind = charKind
                    _isOnline.value = true
                    val ownerKeyB64 = Base64.encode(OfficeSync.publicBundle)
                    syncDoc(docId, key)
                    indexMutex.withLock {
                        val index = loadIndex(ds).associateBy { it.docId }.toMutableMap()
                        if (!index.containsKey(docId)) {
                            index[docId] = OfficeDocMeta(docId, title, Base64.encode(key), owner = true, charMode = charMode, role = OfficeRoles.OWNER, ownerKeyB64 = ownerKeyB64, charKind = charKind)
                            saveIndex(ds, index.values.toList())
                        }
                    }
                    startLive(docId, key)
                    recordMembers(docId, key, listOf(
                        OfficeMember(OfficeSync.deviceId, myName(), OfficeRoles.OWNER),
                        OfficeMember(recipientId, "", role),
                    ))
                    if (OfficeSync.sendInvite(recipientId, docId, key, title, charMode, role, ownerKeyB64, charKind)) null
                    else "Couldn't deliver the invite — check your connection."
                }
            }
            }
        } catch (e: Exception) {
            "Error: ${e.message ?: e.javaClass.simpleName}"
        }
        withContext(Dispatchers.Main) { onResult(error) }
    }
}

/** Pushes local edits and merges remote ones for the open online document (no-op if offline). */
fun OfficeViewModel.syncCurrentDocument() {
    val docId = currentDocId ?: return
    val key = currentDocKey ?: return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { OfficeSync.init(getApplication()); syncDoc(docId, key) }
    }
}

/** Opens an online document by building its CRDT from pulled ops and rendering it into the editor. */
fun OfficeViewModel.openOnlineDocument(meta: OfficeDocMeta) {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            OfficeSync.init(getApplication())
            val ds = DataStoreUtils.getInstance(getApplication())
            val inviteKey = Base64.decode(meta.keyB64)
            currentRole = meta.role
            currentOwnerKey = meta.ownerKeyB64.takeIf { it.isNotBlank() }?.let { Base64.decode(it) }
            currentBaseKey = inviteKey
            currentMembers.clear()
            // Follow any ownership transfers; whoever holds the resolved owner key acts as owner.
            runCatching { fetchOwnerKey(meta.docId, meta.ownerKeyB64) }.getOrNull()?.let { resolved ->
                currentOwnerKey = resolved
                if (resolved.contentEquals(OfficeSync.publicBundle)) currentRole = OfficeRoles.OWNER
            }
            // Resolve the current content key (honors key rotations / read-revocation).
            val (epoch, key) = fetchCurrentKey(meta.docId, inviteKey, currentOwnerKey)
            currentEpoch = epoch
            val crdt = loadTree(ds, meta.docId) ?: DocumentTreeCrdt(OfficeSync.deviceId)
            // Refresh the (owner-signed) roster so op role-checks are correct before applying.
            runCatching { fetchMembers(meta.docId, key, currentOwnerKey) }
            // Pick up any owner rename since we last saw this doc.
            val title = runCatching { fetchTitle(meta.docId, key, currentOwnerKey) }.getOrNull() ?: meta.title
            if (title != meta.title) indexMutex.withLock {
                val index = loadIndex(ds).associateBy { it.docId }.toMutableMap()
                index[meta.docId]?.let { index[meta.docId] = it.copy(title = title); saveIndex(ds, index.values.toList()) }
            }
            val cursor = ds.getLong("crdtCursor:${meta.docId}")?.toInt() ?: 0
            val pulled = OfficeSync.pullDocActions(meta.docId, key, cursor)
            for (item in pulled.items) {
                applySignedOp(crdt, item)
            }
            ds.setLong("crdtCursor:${meta.docId}", pulled.seq.toLong())
            saveTree(ds, meta.docId, crdt)
            val mergedXml = crdt.render()
            if (mergedXml.isBlank()) {
                // No accepted content — usually ops from an older version that no longer verify.
                crdt.close() // never adopted as currentTree; free its native handle
                withContext(Dispatchers.Main) {
                    _state.value = ViewState.Error("This shared document has no readable content. It was likely created with an older version — ask the owner to re-share a new copy.")
                }
                return@runCatching
            }
            val ctx: Context = getApplication()
            val safeTitle = title.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document" }
            val file = java.io.File(ctx.cacheDir, "online_${meta.docId}.fodt")
            file.writeText(mergedXml)
            withContext(Dispatchers.Main) {
                loadDocument(Uri.fromFile(file), "$safeTitle.fodt", meta.docId, key)
                _isEditMode.value = OfficeRoles.canEdit(meta.role) // viewers are read-only
            }
            // loadDocument() above already closed+nulled any prior tree; guard defensively.
            if (currentTree !== crdt) currentTree?.close()
            currentTree = crdt
            currentCharKind = meta.charKind.ifEmpty { if (meta.charMode) "text" else "" }
            startLive(meta.docId, key)
        }
    }
}

/** Computes the verification security code with a peer device id (compare out-of-band). */
fun OfficeViewModel.securityCodeWith(peerId: String, onResult: (String?) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
        val code = runCatching {
            OfficeSync.init(getApplication())
            val pem = OfficeSync.getKey(peerId) ?: return@runCatching null
            OfficeSync.securityCode(pem)
        }.getOrNull()
        withContext(Dispatchers.Main) { onResult(code) }
    }
}
