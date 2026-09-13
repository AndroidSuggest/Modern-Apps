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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.vayunmathur.office.R

/** Local metadata for a document in the online folder (title/key stay client-side; never sent in the clear). */
@Serializable
data class OfficeDocMeta(
    val docId: String,
    val title: String,
    val keyB64: String,
    val owner: Boolean,
    val charMode: Boolean = false,
    val role: String = OfficeRoles.EDITOR,
    val ownerKeyB64: String = "",
    val charKind: String = "", // "", "text", "sheet", or "slide"
)

/** Builds the local metadata for a shared document from an incoming invite (carries role + owner key). */
fun officeDocMetaFromInvite(inv: OfficeSync.Invite): OfficeDocMeta =
    OfficeDocMeta(
        docId = inv.docId,
        title = inv.title,
        keyB64 = inv.key,
        owner = false,
        charMode = inv.charMode,
        role = inv.role,
        ownerKeyB64 = inv.ownerKey,
        charKind = inv.charKind,
    )

/** Document access roles. Enforced entirely client-side via signature checks (server is a pure relay). */
object OfficeRoles {
    const val OWNER = "owner"
    const val EDITOR = "editor"
    const val VIEWER = "viewer"
    const val REVOKED = "revoked"

    fun canEdit(role: String) = role == OWNER || role == EDITOR
}

/** How long a collaborator stays visible after their last activity, and how long "typing…" lingers. */
internal const val PRESENCE_TTL_MS = 5 * 60 * 1000L
internal const val TYPING_TTL_MS = 3000L

/** Ephemeral presence for a collaborator in a document (relayed encrypted; never stored). */
@Serializable
data class OfficePresence(
    val id: String,
    val name: String,
    val typing: Boolean,
    val ts: Long,
    val caret: Int? = null,
    /** Non-text location label for sheets/slides, e.g. "Sheet 1 · B3" or "Slide 2". */
    val loc: String? = null,
    /** Local-only: when this peer was last seen typing (drives the "typing…" auto-clear). */
    val typingTs: Long = 0,
)

/** A member of a document (who has access + their role). Distributed as owner-signed records. */
@Serializable
data class OfficeMember(val id: String, val name: String = "", val role: String = OfficeRoles.EDITOR)

/** An owner-signed member record (only records with a valid owner signature are honored). */
@Serializable
data class SignedMember(val member: OfficeMember, val sig: String)

/** An owner-signed document title (latest valid one wins), for rename-after-share. */
@Serializable
data class SignedTitle(val title: String, val sig: String)

/**
 * An owner-signed key epoch for cryptographic read-revocation. On revoke the owner mints a new
 * content key, seals it to each remaining member (id → PQC-sealed key), and future ops use it. The
 * removed member never receives the new key, so they can't read new content. Op decryption is
 * unchanged (undecryptable ops are simply skipped), so a member who misses a key degrades to stale
 * sync rather than being locked out.
 */
@Serializable
data class KeyEpoch(val epoch: Int, val wraps: Map<String, String>, val sig: String)

/**
 * An ownership handoff, signed by the *current* owner: names the new owner's device id + public
 * bundle. Members follow the signed chain (each transfer signed by the then-current owner) from the
 * original owner key to determine the current owner. Whoever's bundle is the resolved owner key acts
 * as owner (can share, rename, change roles, revoke).
 */
@Serializable
data class OwnerTransfer(val newOwnerId: String, val newOwnerKey: String, val sig: String)

/** An author-signed CRDT op batch: author id, signature over [ops], and the ops JSON. */
@Serializable
data class SignedOp(val author: String, val sig: String, val ops: String)

class OfficeViewModel(application: Application) : AndroidViewModel(application) {
    internal val _state = MutableStateFlow<ViewState>(ViewState.Empty)
    val state: StateFlow<ViewState> = _state

    internal val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode

    internal val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges

    internal val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    internal val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    internal val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    internal val _nightMode = MutableStateFlow(false)
    val nightMode: StateFlow<Boolean> = _nightMode

    enum class DocumentThemeMode {
        UNCHANGED,
        FOLLOW_SYSTEM
    }

    // Persisted document theme preference: view-only, does not mutate file (#479).
    // UNCHANGED = always show as authored; FOLLOW_SYSTEM = follow system dark theme via view inversion.
    internal val _documentThemeMode = MutableStateFlow(DocumentThemeMode.UNCHANGED)
    val documentThemeMode: StateFlow<DocumentThemeMode> = _documentThemeMode

    // Legacy in-memory toggle (kept for View menu compat). Derived from persisted theme when needed.
    // Google-Docs-style dark mode: inverts the displayed colors of the document so the
    // paper appears dark, without modifying the document itself. View-only inversion.
    internal val _documentDarkMode = MutableStateFlow(false)
    val documentDarkMode: StateFlow<Boolean> = _documentDarkMode

    // Incremented whenever the document changes shape via undo/redo so the UI can
    // reset/clamp hoisted selection state (active cell/slide/element). (A4)
    internal val _selectionInvalidation = MutableStateFlow(0)
    val selectionInvalidation: StateFlow<Int> = _selectionInvalidation

    /** App-private read cache of the open document; used as the source package when re-writing. */
    internal var documentUri: Uri? = null

    /** The document the user actually opened. Save writes back here, never to [documentUri]. */
    internal var originalUri: Uri? = null

    /** Current open document (null unless [state] holds [ViewState.Loaded]). */
    val document: StateFlow<OdfDocument?> = _state
        .map { (it as? ViewState.Loaded)?.document }
        .stateIn(viewModelScope, SharingStarted.Eagerly, (_state.value as? ViewState.Loaded)?.document)

    // --- Auto-save ---
    internal var autoSaveJob: Job? = null
    internal var _autoSaveEnabled = MutableStateFlow(false)
    val autoSaveEnabled: StateFlow<Boolean> = _autoSaveEnabled
    internal var autoSaveIntervalMs: Long = 60_000L // default 1 minute

    fun setAutoSave(enabled: Boolean, intervalSeconds: Int = 60) {
        autoSaveIntervalMs = intervalSeconds * 1000L
        _autoSaveEnabled.value = enabled
        autoSaveJob?.cancel()
        if (enabled) {
            autoSaveJob = viewModelScope.launch {
                while (true) {
                    delay(autoSaveIntervalMs)
                    if (_hasUnsavedChanges.value && !needsSaveAs()) save()
                }
            }
        }
    }

    // --- Settings ---
    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
        val autoSave = prefs.getBoolean("auto_save", false)
        val interval = prefs.getInt("auto_save_interval", 60)
        setAutoSave(autoSave, interval)
        val themeName = prefs.getString("document_theme_mode", DocumentThemeMode.UNCHANGED.name)
        val mode = try { DocumentThemeMode.valueOf(themeName ?: DocumentThemeMode.UNCHANGED.name) } catch (_: Exception) { DocumentThemeMode.UNCHANGED }
        val legacyDark = if (prefs.contains("document_dark_mode")) prefs.getBoolean("document_dark_mode", false) else null
        val resolved = if (legacyDark != null && !prefs.contains("document_theme_mode")) {
            if (legacyDark) DocumentThemeMode.FOLLOW_SYSTEM else DocumentThemeMode.UNCHANGED
        } else mode
        _documentThemeMode.value = resolved
        _documentDarkMode.value = resolved == DocumentThemeMode.FOLLOW_SYSTEM
    }

    fun saveSettings(context: Context, autoSave: Boolean, autoSaveInterval: Int, defaultFontSize: Float) {
        saveSettings(context, autoSave, autoSaveInterval, defaultFontSize, _documentThemeMode.value)
    }

    fun saveSettings(context: Context, autoSave: Boolean, autoSaveInterval: Int, defaultFontSize: Float, documentThemeMode: DocumentThemeMode) {
        context.getSharedPreferences("office_settings", Context.MODE_PRIVATE).edit {
            putBoolean("auto_save", autoSave)
            putInt("auto_save_interval", autoSaveInterval)
            putFloat("default_font_size", defaultFontSize)
            putString("document_theme_mode", documentThemeMode.name)
        }
        setAutoSave(autoSave, autoSaveInterval)
        _documentThemeMode.value = documentThemeMode
        _documentDarkMode.value = documentThemeMode == DocumentThemeMode.FOLLOW_SYSTEM
    }

    fun getDocumentThemeMode(context: Context): DocumentThemeMode {
        val prefs = context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
        val name = prefs.getString("document_theme_mode", DocumentThemeMode.UNCHANGED.name)
        return try { DocumentThemeMode.valueOf(name ?: DocumentThemeMode.UNCHANGED.name) } catch (_: Exception) { DocumentThemeMode.UNCHANGED }
    }

    fun setDocumentThemeMode(context: Context, mode: DocumentThemeMode) {
        _documentThemeMode.value = mode
        _documentDarkMode.value = mode == DocumentThemeMode.FOLLOW_SYSTEM
        context.getSharedPreferences("office_settings", Context.MODE_PRIVATE).edit { putString("document_theme_mode", mode.name) }
    }

    fun getDefaultFontSize(context: Context): Float {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getFloat("default_font_size", 16f)
    }

    fun getAutoSaveInterval(context: Context): Int {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getInt("auto_save_interval", 60)
    }

    fun getAutoSaveEnabled(context: Context): Boolean {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getBoolean("auto_save", false)
    }

    // --- Undo/Redo ---
    internal val undoStack = ArrayDeque<OdfDocument>(maxOf(1, MAX_UNDO))
    internal val redoStack = ArrayDeque<OdfDocument>(maxOf(1, MAX_UNDO))

    internal fun pushUndo(doc: OdfDocument) {
        undoStack.addLast(doc)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    fun undo() {
        val current = (_state.value as? ViewState.Loaded)?.document ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        _state.value = ViewState.Loaded(previous)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _hasUnsavedChanges.value = true
        _selectionInvalidation.value++
    }

    fun redo() {
        val current = (_state.value as? ViewState.Loaded)?.document ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        _state.value = ViewState.Loaded(next)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _hasUnsavedChanges.value = true
        _selectionInvalidation.value++
    }

    /** Applies a paragraph-level mutation to every paragraph touched by the run selection. */

    fun mutateRunParagraphs(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfParagraph) -> OdfParagraph) {
        val doc = curText() ?: return
        updateDocument(doc.mutateRunParagraphs(start, endInclusive, gStart, gEnd, transform) ?: return)
    }

    internal fun updateDocument(newDoc: OdfDocument) {
        val current = (_state.value as? ViewState.Loaded)?.document ?: return
        pushUndo(current)
        // Keep ordered-list numbering live after every text edit (matches the markdown editor).
        val stored = if (newDoc is OdfDocument.TextDocument) renumberLists(newDoc) else newDoc
        // Shift remote collaborators' carets by this text change so they stay at the right spot until
        // the peer sends fresh presence (otherwise inserting/deleting before their caret misplaces it).
        remapRemoteCarets(current, stored)
        _state.value = ViewState.Loaded(stored)
        _hasUnsavedChanges.value = true
        // Bump the local-edit version for genuine user edits (not remote merges) so a background
        // sync/merge won't overwrite a keystroke that landed while it was running.
        if (!applyingRemote) editVersion++
        onLocalEdit()
    }

    /** Plain text of a text document (paragraphs joined by newlines), matching editor caret offsets. */
    internal fun docPlainText(doc: OdfDocument): String? =
        (doc as? OdfDocument.TextDocument)?.content
            ?.mapNotNull { (it as? OdfContentBlock.Paragraph)?.paragraph?.spans?.joinToString("") { s -> s.text } }
            ?.joinToString("\n")

    internal fun remapRemoteCarets(old: OdfDocument, new: OdfDocument) {
        val presence = _remotePresence.value
        if (presence.none { it.caret != null }) return
        val oldText = docPlainText(old) ?: return
        val newText = docPlainText(new) ?: return
        if (oldText == newText) return
        _remotePresence.value = presence.map { p ->
            if (p.caret != null) p.copy(caret = remapCaret(oldText, newText, p.caret)) else p
        }
    }

    // --- Recent files ---


    // --- Night mode ---
    fun toggleNightMode() { _nightMode.value = !_nightMode.value }

    fun toggleDocumentDarkMode() {
        val next = if (_documentThemeMode.value == DocumentThemeMode.UNCHANGED) DocumentThemeMode.FOLLOW_SYSTEM else DocumentThemeMode.UNCHANGED
        _documentThemeMode.value = next
        _documentDarkMode.value = next == DocumentThemeMode.FOLLOW_SYSTEM
        try {
            val app = getApplication<Application>()
            app.getSharedPreferences("office_settings", Context.MODE_PRIVATE).edit { putString("document_theme_mode", next.name) }
        } catch (_: Exception) {}
    }
    fun toggleDocumentDarkModePersisted(context: Context) {
        val next = if (_documentThemeMode.value == DocumentThemeMode.UNCHANGED) DocumentThemeMode.FOLLOW_SYSTEM else DocumentThemeMode.UNCHANGED
        setDocumentThemeMode(context, next)
    }

    // --- Load / Clear live in OfficeViewModelDocuments.kt ---

    fun toggleEditMode() { _isEditMode.value = !_isEditMode.value }

    // --- End-to-end-encrypted cloud sync & sharing (via OfficeSync + :library:e2ee-p2p) ---

    internal val _onlineDocs = MutableStateFlow<List<OfficeDocMeta>>(emptyList())
    /** Documents in the "online" folder: created/shared by you or shared with you. */
    val onlineDocs: StateFlow<List<OfficeDocMeta>> = _onlineDocs.asStateFlow()

    internal val _pendingRequests = MutableStateFlow<List<OfficeSync.JoinRequest>>(emptyList())
    /** Inbound join requests (from tapped share links) for documents this device owns. */
    val pendingRequests: StateFlow<List<OfficeSync.JoinRequest>> = _pendingRequests.asStateFlow()
    internal val requestsMutex = Mutex()

    /** The online doc id/key of the currently open document, if it lives online. */
    internal var currentDocId: String? = null
    internal var currentDocKey: ByteArray? = null
    internal var currentTree: DocumentTreeCrdt? = null
    internal var currentCharKind: String = "" // "", "text", "sheet", or "slide"
    internal var currentRole: String = OfficeRoles.OWNER
    internal var currentOwnerKey: ByteArray? = null
    internal var currentEpoch: Int = 0
    internal var currentBaseKey: ByteArray? = null // epoch-0 (invite) key, used to read the key-epoch channel context
    internal val currentMembers = java.util.concurrent.ConcurrentHashMap<String, String>() // id -> role
    internal val memberKeyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>() // id -> pubkey PEM
    internal val syncJson = Json { ignoreUnknownKeys = true }
    internal val indexMutex = Mutex()
    internal val syncMutex = Mutex()

    internal val _remotePresence = MutableStateFlow<List<OfficePresence>>(emptyList())
    /** Other people currently in the open document (name + typing), for the presence indicator. */
    val remotePresence: StateFlow<List<OfficePresence>> = _remotePresence.asStateFlow()

    internal val _onlineEnabled = MutableStateFlow(false)
    /**
     * Whether online sharing has been turned on. This is derived purely from whether this device's
     * encryption keys + device id already exist: they are created only when the user opts in (or, for
     * users from before the opt-in button existed, they already exist — so those users stay enabled).
     * Until enabled, no key generation, device id, or server registration happens; the app is offline.
     */
    val onlineEnabled: StateFlow<Boolean> = _onlineEnabled.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _onlineEnabled.value = hasOnlineIdentity()
        }
    }

    // Identity + presence + live channel live in OfficeViewModelSyncA.kt.

    internal val _isOnline = MutableStateFlow(false)
    /** True when the open document is an online (cloud-synced) document — hides Save, changes back nav. */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()
    internal var applyingRemote = false
    @Volatile internal var editVersion = 0
    internal var livePushJob: Job? = null
    internal var livePollJob: Job? = null
    internal var presenceTickJob: Job? = null
    internal var localCaret = 0
    internal var localLoc: String? = null
    internal var caretPresenceJob: Job? = null

    // Presence + live-channel helpers live in OfficeViewModelSyncA.kt;
    // index/invite/share/CRDT sync lives in OfficeViewModelSyncB.kt + OfficeViewModelSyncC.kt;
    // save lives in OfficeViewModelDocuments.kt.

    /** This device's sync id. Empty until [initSync] has run. */
    val syncDeviceId: String get() = OfficeSync.deviceId

    sealed class ViewState {
        data object Empty : ViewState()
        data object Loading : ViewState()
        data class Loaded(val document: OdfDocument) : ViewState()
        data class Error(val message: String) : ViewState()
    }

    // Split members live as extensions in OfficeViewModel* files (same package).

    companion object {
        const val MAX_UNDO = 30
        const val MAX_RECENT = 20
    }
}
