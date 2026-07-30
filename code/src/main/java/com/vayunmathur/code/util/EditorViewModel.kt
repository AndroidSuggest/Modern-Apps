package com.vayunmathur.code.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.code.syntax.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One expandable row in the file-tree pane; the tree is stored as a flat, ordered list. */
class TreeNode(val entry: DocEntry, val depth: Int) {
    var expanded by mutableStateOf(false)
    var loading by mutableStateOf(false)
}

/**
 * One open file. Editor content lives in [value]; [savedText] is the last persisted text so
 * [isDirty] can drive the unsaved-dot. Undo/redo are plain deques (not observed directly);
 * [canUndo]/[canRedo] mirror their emptiness as state so the toolbar buttons stay reactive.
 */
class OpenTab(
    val uri: Uri,
    initialName: String,
    initialText: String,
    val language: Language,
) {
    var name by mutableStateOf(initialName)
    var value by mutableStateOf(TextFieldValue(initialText))
    var savedText by mutableStateOf(initialText)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()

    val isDirty: Boolean get() = value.text != savedText

    fun pushUndo(previous: TextFieldValue) {
        undoStack.addLast(previous)
        if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        canUndo = true
        canRedo = false
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(value)
        value = previous
        canUndo = undoStack.isNotEmpty()
        canRedo = true
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(value)
        value = next
        canRedo = redoStack.isNotEmpty()
        canUndo = true
    }

    private companion object {
        const val UNDO_LIMIT = 100
    }
}

/**
 * Activity-scoped state for the editor: the open folder tree, the set of open tabs and the
 * editor preferences. An [AndroidViewModel] (obtained via `by viewModels()`) so it can reach
 * the ContentResolver and DataStore through the application context. Compose reads the
 * `mutableStateOf`/`mutableStateListOf` members directly.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()
    private val prefs = EditorPrefs(context)

    // ---- File tree ----
    var treeUri by mutableStateOf<Uri?>(null)
        private set
    var rootName by mutableStateOf<String?>(null)
        private set
    val nodes = mutableStateListOf<TreeNode>()

    // ---- Tabs ----
    val tabs = mutableStateListOf<OpenTab>()
    var currentIndex by mutableStateOf(-1)
        private set
    val currentTab: OpenTab? get() = tabs.getOrNull(currentIndex)

    // ---- Preferences ----
    var softWrap by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { softWrap = prefs.softWrap.first() }
        viewModelScope.launch {
            val stored = prefs.folderUri.first() ?: return@launch
            val uri = Uri.parse(stored)
            val loaded = runCatching { loadFolder(uri) }.isSuccess
            if (!loaded) prefs.clearFolderUri()
        }
    }

    // ---- Folder handling ----

    /** Persists read/write access to a freshly picked tree and loads its top level. */
    fun openFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        viewModelScope.launch {
            prefs.setFolderUri(uri.toString())
            loadFolder(uri)
        }
    }

    fun closeFolder() {
        treeUri = null
        rootName = null
        nodes.clear()
        viewModelScope.launch { prefs.clearFolderUri() }
    }

    private suspend fun loadFolder(uri: Uri) {
        val root = withContext(Dispatchers.IO) { SafFiles.rootEntry(context, uri) }
        val children = withContext(Dispatchers.IO) { SafFiles.listChildren(context, uri, root.documentId) }
        treeUri = uri
        rootName = root.name
        nodes.clear()
        nodes.addAll(children.map { TreeNode(it, depth = 0) })
    }

    /** Expands/collapses a directory row, or opens a file row in a tab. */
    fun toggle(node: TreeNode) {
        if (!node.entry.isDirectory) {
            openFile(node.entry.uri, node.entry.name)
            return
        }
        val tree = treeUri ?: return
        val index = nodes.indexOf(node)
        if (index < 0) return

        if (node.expanded) {
            node.expanded = false
            while (index + 1 < nodes.size && nodes[index + 1].depth > node.depth) {
                nodes.removeAt(index + 1)
            }
        } else {
            node.expanded = true
            node.loading = true
            viewModelScope.launch {
                val children = withContext(Dispatchers.IO) {
                    SafFiles.listChildren(context, tree, node.entry.documentId)
                }
                node.loading = false
                // The row may have been collapsed again while loading; only insert if still open.
                val at = nodes.indexOf(node)
                if (at >= 0 && node.expanded) {
                    nodes.addAll(at + 1, children.map { TreeNode(it, node.depth + 1) })
                }
            }
        }
    }

    // ---- Tab handling ----

    /** Opens [uri] in a tab (focusing it if already open), reading its text off the main thread. */
    fun openFile(uri: Uri, name: String? = null) {
        val existing = tabs.indexOfFirst { it.uri == uri }
        if (existing >= 0) {
            currentIndex = existing
            return
        }
        viewModelScope.launch {
            val displayName = name
                ?: withContext(Dispatchers.IO) { SafFiles.queryDisplayName(context, uri) }
                ?: "untitled"
            val text = withContext(Dispatchers.IO) {
                runCatching { SafFiles.readText(context, uri) }.getOrDefault("")
            }
            tabs.add(OpenTab(uri, displayName, text, Language.fromFileName(displayName)))
            currentIndex = tabs.lastIndex
        }
    }

    /** Handles a VIEW/EDIT intent that carries a single file URI. */
    fun openExternal(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        openFile(uri)
    }

    fun selectTab(index: Int) {
        if (index in tabs.indices) currentIndex = index
    }

    fun closeTab(tab: OpenTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val removingCurrent = index == currentIndex
        tabs.removeAt(index)
        currentIndex = when {
            tabs.isEmpty() -> -1
            index < currentIndex -> currentIndex - 1
            removingCurrent -> index.coerceAtMost(tabs.lastIndex)
            else -> currentIndex
        }
    }

    // ---- Editing ----

    fun onEditorChange(new: TextFieldValue) {
        val tab = currentTab ?: return
        if (new.text != tab.value.text) tab.pushUndo(tab.value)
        tab.value = new
    }

    /** Moves the selection without recording an undo step (used by find navigation). */
    fun setSelection(range: TextRange) {
        val tab = currentTab ?: return
        tab.value = tab.value.copy(selection = range)
    }

    fun undo() = currentTab?.undo()

    fun redo() = currentTab?.redo()

    fun save() {
        val tab = currentTab ?: return
        val textToSave = tab.value.text
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { SafFiles.writeText(context, tab.uri, textToSave) }.isSuccess
            }
            if (ok) tab.savedText = textToSave
        }
    }

    /** Inserts [insert] at the caret, replacing any current selection (used by the Tab button). */
    fun insertText(insert: String) {
        val tab = currentTab ?: return
        val v = tab.value
        val start = v.selection.min
        val end = v.selection.max
        val newText = v.text.substring(0, start) + insert + v.text.substring(end)
        onEditorChange(TextFieldValue(newText, TextRange(start + insert.length)))
    }

    fun toggleSoftWrap() {
        softWrap = !softWrap
        viewModelScope.launch { prefs.setSoftWrap(softWrap) }
    }

    // ---- Find & replace ----

    fun replaceRange(range: IntRange, replacement: String) {
        val tab = currentTab ?: return
        val text = tab.value.text
        if (range.first < 0 || range.last + 1 > text.length) return
        val newText = text.substring(0, range.first) + replacement + text.substring(range.last + 1)
        onEditorChange(TextFieldValue(newText, TextRange(range.first + replacement.length)))
    }

    fun replaceAll(matches: List<IntRange>, replacement: String) {
        val tab = currentTab ?: return
        if (matches.isEmpty()) return
        val text = tab.value.text
        val sb = StringBuilder(text.length)
        var last = 0
        for (m in matches.sortedBy { it.first }) {
            if (m.first < last) continue
            sb.append(text, last, m.first)
            sb.append(replacement)
            last = m.last + 1
        }
        sb.append(text, last, text.length)
        onEditorChange(TextFieldValue(sb.toString(), TextRange(sb.length)))
    }
}
