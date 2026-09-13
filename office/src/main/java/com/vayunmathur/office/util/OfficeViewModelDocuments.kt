package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.vayunmathur.office.R
import com.vayunmathur.office.util.OfficeViewModel.Companion.MAX_RECENT
import com.vayunmathur.office.util.OfficeViewModel.ViewState

fun OfficeViewModel.addToRecent(context: Context, uri: Uri, name: String) {
    val prefs = context.getSharedPreferences("office_recent", Context.MODE_PRIVATE)
    val existing = getRecentFiles(context).toMutableList()
    existing.removeAll { it.first == uri.toString() }
    existing.add(0, Pair(uri.toString(), name))
    if (existing.size > MAX_RECENT) existing.subList(MAX_RECENT, existing.size).clear()
    prefs.edit {
        putInt("count", existing.size)
        existing.forEachIndexed { i, (u, n) ->
            putString("uri_$i", u)
            putString("name_$i", n)
        }
    }
}

fun OfficeViewModel.getRecentFiles(context: Context): List<Pair<String, String>> {
    val prefs = context.getSharedPreferences("office_recent", Context.MODE_PRIVATE)
    val count = prefs.getInt("count", 0)
    return (0 until count).mapNotNull { i ->
        val uri = prefs.getString("uri_$i", null) ?: return@mapNotNull null
        val name = prefs.getString("name_$i", null) ?: return@mapNotNull null
        Pair(uri, name)
    }
}

fun OfficeViewModel.clearRecentFiles(context: Context) {
    context.getSharedPreferences("office_recent", Context.MODE_PRIVATE).edit { clear() }
}

fun OfficeViewModel.loadDocument(uri: Uri, fileName: String, onlineDocId: String? = null, onlineDocKey: ByteArray? = null) {
    _state.value = ViewState.Loading
    _isEditMode.value = true
    _hasUnsavedChanges.value = false
    undoStack.clear(); redoStack.clear()
    _canUndo.value = false; _canRedo.value = false
    documentUri = uri
    originalUri = uri
    // Track (or clear) the online identity of the document now open.
    currentDocId = onlineDocId
    currentDocKey = onlineDocKey
    currentTree?.close(); currentTree = null
    currentCharKind = ""
    _isOnline.value = onlineDocId != null
    if (onlineDocId == null) {
        // Offline document: you own your own local file and may edit it freely.
        currentRole = OfficeRoles.OWNER
        currentOwnerKey = null
        currentMembers.clear()
        memberKeyCache.clear()
    }
    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Copy the source into app-owned storage so a revoked SAF grant can't leave us
            // unable to re-read the untouched package parts at save time. This is only a read
            // cache -- [originalUri] stays the save target.
            val localUri = persistToAppStorage(uri, fileName)
            documentUri = localUri
            val doc = DocumentImporter.open(getApplication(), localUri, fileName)
            _state.value = ViewState.Loaded(doc)
            addToRecent(getApplication(), uri, fileName)
        } catch (e: Exception) {
            _state.value = ViewState.Error(e.message ?: "Unknown error")
        }
    }
}

internal fun OfficeViewModel.persistToAppStorage(uri: Uri, fileName: String): Uri {
    val ctx: Context = getApplication()
    val dir = java.io.File(ctx.filesDir, "documents").apply { mkdirs() }
    if (uri.scheme == "file" && uri.path?.startsWith(dir.absolutePath) == true) return uri
    val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document" }
    val dest = java.io.File(dir, "${System.currentTimeMillis()}_$safe")
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    } ?: throw java.io.IOException("Cannot read $uri")
    return Uri.fromFile(dest)
}

fun OfficeViewModel.clearDocument() {
    _state.value = ViewState.Empty
    _isEditMode.value = false
    _hasUnsavedChanges.value = false
    undoStack.clear(); redoStack.clear()
    _canUndo.value = false; _canRedo.value = false
    documentUri = null
    originalUri = null
    currentDocId = null
    currentDocKey = null
    currentTree?.close(); currentTree = null
    currentCharKind = ""
    OfficeSync.stopLive()
    livePollJob?.cancel()
    presenceTickJob?.cancel()
    _remotePresence.value = emptyList()
    _isOnline.value = false
    autoSaveJob?.cancel()
}

fun OfficeViewModel.createNewTextDocument() {
    currentDocId = null; currentDocKey = null; currentTree?.close(); currentTree = null; currentCharKind = ""
    currentRole = OfficeRoles.OWNER; currentOwnerKey = null; currentMembers.clear(); _isOnline.value = false
    undoStack.clear(); redoStack.clear()
    _canUndo.value = false; _canRedo.value = false
    val doc = OdfDocument.TextDocument(
        title = "Untitled Document",
        content = listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")))))
    )
    _state.value = ViewState.Loaded(doc)
    _isEditMode.value = true
    _hasUnsavedChanges.value = true
    documentUri = null
    originalUri = null
}

fun OfficeViewModel.createNewSpreadsheet() {
    currentDocId = null; currentDocKey = null; currentTree?.close(); currentTree = null; currentCharKind = ""
    currentRole = OfficeRoles.OWNER; currentOwnerKey = null; currentMembers.clear(); _isOnline.value = false
    undoStack.clear(); redoStack.clear()
    _canUndo.value = false; _canRedo.value = false
    val rows = (0 until 10).map { OdfRow(List(5) { OdfCell(text = "") }) }
    val doc = OdfDocument.Spreadsheet(
        title = "Untitled Spreadsheet",
        sheets = listOf(OdfSheet("Sheet 1", rows))
    )
    _state.value = ViewState.Loaded(doc)
    _isEditMode.value = true
    _hasUnsavedChanges.value = true
    documentUri = null
    originalUri = null
}

fun OfficeViewModel.createNewPresentation() {
    currentDocId = null; currentDocKey = null; currentTree?.close(); currentTree = null; currentCharKind = ""
    currentRole = OfficeRoles.OWNER; currentOwnerKey = null; currentMembers.clear(); _isOnline.value = false
    undoStack.clear(); redoStack.clear()
    _canUndo.value = false; _canRedo.value = false
    val doc = OdfDocument.Presentation(
        title = "Untitled Presentation",
        slides = listOf(OdfSlide(
            name = "Slide 1",
            elements = listOf(
                OdfSlideElement.Frame(OdfFrame(
                    x = 50f, y = 50f, width = 600f, height = 100f,
                    paragraphs = listOf(OdfParagraph(
                        listOf(OdfSpan(text = "Title", bold = true, fontSize = 36f)),
                        style = ParagraphStyle.HEADING1
                    ))
                ))
            )
        ))
    )
    _state.value = ViewState.Loaded(doc)
    _isEditMode.value = true
    _hasUnsavedChanges.value = true
    documentUri = null
    originalUri = null
}

fun OfficeViewModel.save(targetUri: Uri? = null) {
    val doc = (_state.value as? ViewState.Loaded)?.document ?: return
    // Source may be null for a brand-new document; the writer then builds the package from scratch.
    val source = documentUri
    // Write back to the document the user opened, not to the app-private read cache.
    val target = targetUri ?: originalUri ?: return
    _isSaving.value = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            OdfWriter.save(getApplication(), source, doc, target)
            _hasUnsavedChanges.value = false
            originalUri = target
            if (documentUri == null) documentUri = target
            // If this document lives online, push local edits + merge remote ones.
            if (currentDocId != null && currentDocKey != null) {
                runCatching {
                    OfficeSync.init(getApplication())
                    syncDoc(currentDocId!!, currentDocKey!!)
                }
            }
            launch(Dispatchers.Main) { AppMessages.show(getApplication<Application>().getString(R.string.saved)) }
        } catch (e: Exception) {
            launch(Dispatchers.Main) { AppMessages.show(getApplication<Application>().getString(R.string.save_failed, e.message)) }
        } finally {
            _isSaving.value = false
        }
    }
}

fun OfficeViewModel.needsSaveAs(): Boolean {
    val target = originalUri ?: return true
    return !isWritable(target)
}

internal fun OfficeViewModel.isWritable(uri: Uri): Boolean = when (uri.scheme) {
    "file" -> uri.path?.let { java.io.File(it).canWrite() } == true
    else -> getApplication<Application>().checkCallingOrSelfUriPermission(
        uri,
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    ) == PackageManager.PERMISSION_GRANTED
}
