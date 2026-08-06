package com.vayunmathur.code.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.code.syntax.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
    uri: Uri,
    initialName: String,
    initialText: String,
    language: Language,
) {
    var uri by mutableStateOf(uri)
    var name by mutableStateOf(initialName)
    var language by mutableStateOf(language)
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
 * the ContentResolver and DataStore through the application context.
 *
 * It implements [CodeActions] and projects itself into a [CodeUiState] so the screens can be
 * rendered without it; reading [uiState] inside a composable subscribes to the same
 * `mutableStateOf`/`mutableStateListOf` members the screens used to read directly.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application), CodeActions {

    private val context get() = getApplication<Application>()
    private val prefs = EditorPrefs(context)

    // ---- File tree ----
    var treeUri by mutableStateOf<Uri?>(null)
        private set
    var rootName by mutableStateOf<String?>(null)
        private set
    private var rootDocumentId: String? = null
    val nodes = mutableStateListOf<TreeNode>()

    // ---- Tabs ----
    val tabs = mutableStateListOf<OpenTab>()
    var currentIndex by mutableStateOf(-1)
        private set
    val currentTab: OpenTab? get() = tabs.getOrNull(currentIndex)

    // ---- Preferences ----
    var softWrap by mutableStateOf(false)
        private set
    var fontSize by mutableStateOf(EditorPrefs.DEFAULT_FONT_SIZE)
        private set
    var tabWidth by mutableStateOf(EditorPrefs.DEFAULT_TAB_WIDTH)
        private set
    var themeMode by mutableStateOf(EditorPrefs.THEME_SYSTEM)
        private set
    var autoIndent by mutableStateOf(true)
        private set
    var autoCloseBrackets by mutableStateOf(true)
        private set

    // ---- Project search ----
    val searchResults = mutableStateListOf<SearchResult>()
    var isSearching by mutableStateOf(false)
        private set
    private var searchJob: Job? = null

    /** Snapshot of everything the screens draw; rebuilt on every read, as Compose expects. */
    val uiState: CodeUiState
        get() = CodeUiState(
            tabs = tabs.map {
                TabUiState(
                    name = it.name,
                    value = it.value,
                    language = it.language,
                    isDirty = it.isDirty,
                    canUndo = it.canUndo,
                    canRedo = it.canRedo,
                )
            },
            currentIndex = currentIndex,
            softWrap = softWrap,
            rootName = rootName,
            folderOpen = treeUri != null,
            nodes = nodes.map {
                TreeRowUiState(
                    name = it.entry.name,
                    depth = it.depth,
                    isDirectory = it.entry.isDirectory,
                    expanded = it.expanded,
                )
            },
            fontSize = fontSize,
            tabWidth = tabWidth,
            autoIndent = autoIndent,
            autoCloseBrackets = autoCloseBrackets,
            searchResults = searchResults.toList(),
            isSearching = isSearching,
        )

    init {
        viewModelScope.launch { softWrap = prefs.softWrap.first() }
        viewModelScope.launch { fontSize = prefs.fontSize.first() }
        viewModelScope.launch { tabWidth = prefs.tabWidth.first() }
        viewModelScope.launch { themeMode = prefs.themeMode.first() }
        viewModelScope.launch { autoIndent = prefs.autoIndent.first() }
        viewModelScope.launch { autoCloseBrackets = prefs.autoCloseBrackets.first() }
        viewModelScope.launch {
            val stored = prefs.folderUri.first() ?: return@launch
            val uri = stored.toUri()
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
        rootDocumentId = null
        nodes.clear()
        viewModelScope.launch { prefs.clearFolderUri() }
    }

    private suspend fun loadFolder(uri: Uri) {
        val root = withContext(Dispatchers.IO) { SafFiles.rootEntry(context, uri) }
        val children = withContext(Dispatchers.IO) { SafFiles.listChildren(context, uri, root.documentId) }
        treeUri = uri
        rootName = root.name
        rootDocumentId = root.documentId
        nodes.clear()
        nodes.addAll(children.map { TreeNode(it, depth = 0) })
    }

    /** Expands/collapses a directory row, or opens a file row in a tab. */
    override fun toggleNode(index: Int) {
        val node = nodes.getOrNull(index) ?: return
        if (!node.entry.isDirectory) {
            openFile(node.entry.uri, node.entry.name)
            return
        }
        val tree = treeUri ?: return

        if (node.expanded) {
            node.expanded = false
            removeDescendants(index)
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

    // ---- File operations ----

    /** Removes the rows that are descendants of the row at [index] (depth strictly greater). */
    private fun removeDescendants(index: Int) {
        val depth = nodes[index].depth
        while (index + 1 < nodes.size && nodes[index + 1].depth > depth) {
            nodes.removeAt(index + 1)
        }
    }

    /**
     * Re-lists the children of a folder and rebuilds that subtree in [nodes]. Expansion state
     * and already-loaded descendant rows of immediate child folders are preserved by document id.
     * [parentIndex] null refreshes the tree root.
     */
    private suspend fun refreshChildren(parentIndex: Int?) {
        val tree = treeUri ?: return
        val parentDocId = if (parentIndex == null) rootDocumentId ?: return
        else nodes.getOrNull(parentIndex)?.entry?.documentId ?: return
        val parentDepth = if (parentIndex == null) -1 else nodes[parentIndex].depth
        val childDepth = parentDepth + 1

        val blockStart = (parentIndex ?: -1) + 1
        var blockEnd = blockStart
        while (blockEnd < nodes.size && nodes[blockEnd].depth > parentDepth) blockEnd++

        // Preserve existing immediate children (and their loaded subtrees) by document id.
        val preservedNode = HashMap<String, TreeNode>()
        val preservedSubtree = HashMap<String, List<TreeNode>>()
        var i = blockStart
        while (i < blockEnd) {
            val child = nodes[i]
            if (child.depth == childDepth) {
                var j = i + 1
                while (j < blockEnd && nodes[j].depth > childDepth) j++
                preservedNode[child.entry.documentId] = child
                preservedSubtree[child.entry.documentId] = nodes.subList(i + 1, j).toList()
                i = j
            } else {
                i++
            }
        }

        val entries = withContext(Dispatchers.IO) { SafFiles.listChildren(context, tree, parentDocId) }

        val rebuilt = ArrayList<TreeNode>()
        for (entry in entries) {
            val existing = preservedNode[entry.documentId]
            if (existing != null) {
                rebuilt.add(existing)
                rebuilt.addAll(preservedSubtree[entry.documentId].orEmpty())
            } else {
                rebuilt.add(TreeNode(entry, childDepth))
            }
        }

        for (k in blockEnd - 1 downTo blockStart) nodes.removeAt(k)
        nodes.addAll(blockStart, rebuilt)
    }

    /** Resolves the document id of a create target: the tree root, or a directory row. */
    private fun parentDocId(parentIndex: Int?): String? =
        if (parentIndex == null) rootDocumentId else nodes.getOrNull(parentIndex)?.entry?.documentId

    override fun createFile(parentIndex: Int?, name: String) {
        val tree = treeUri ?: return
        val parentDoc = parentDocId(parentIndex) ?: return
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                SafFiles.createDocument(context, tree, parentDoc, name, SafFiles.mimeForFileName(name))
            } ?: return@launch
            nodes.getOrNull(parentIndex ?: -1)?.expanded = true
            refreshChildren(parentIndex)
            openFile(uri, name)
        }
    }

    override fun createFolder(parentIndex: Int?, name: String) {
        val tree = treeUri ?: return
        val parentDoc = parentDocId(parentIndex) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SafFiles.createDocument(
                    context, tree, parentDoc, name, DocumentsContract.Document.MIME_TYPE_DIR,
                )
            } ?: return@launch
            nodes.getOrNull(parentIndex ?: -1)?.expanded = true
            refreshChildren(parentIndex)
        }
    }

    override fun renameNode(index: Int, newName: String) {
        val node = nodes.getOrNull(index) ?: return
        val oldUri = node.entry.uri
        viewModelScope.launch {
            val newUri = withContext(Dispatchers.IO) {
                SafFiles.renameDocument(context, oldUri, newName)
            } ?: return@launch
            val newDocId = runCatching { DocumentsContract.getDocumentId(newUri) }
                .getOrDefault(node.entry.documentId)
            val at = nodes.indexOf(node)
            if (at >= 0) {
                // A renamed directory's descendant ids may shift; drop them so a re-expand re-lists.
                if (node.entry.isDirectory) removeDescendants(at)
                val newEntry = node.entry.copy(documentId = newDocId, name = newName, uri = newUri)
                nodes[at] = TreeNode(newEntry, node.depth)
            }
            tabs.firstOrNull { it.uri == oldUri }?.let { tab ->
                tab.uri = newUri
                tab.name = newName
                tab.language = Language.fromFileName(newName)
            }
        }
    }

    override fun deleteNode(index: Int) {
        val node = nodes.getOrNull(index) ?: return
        val uri = node.entry.uri
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { SafFiles.deleteDocument(context, uri) }
            if (!ok) return@launch
            val at = nodes.indexOf(node)
            if (at >= 0) {
                removeDescendants(at)
                nodes.removeAt(at)
            }
            closeTabsUnder(uri)
        }
    }

    /** Closes any open tab whose file is [uri] or lives beneath it (for a deleted directory). */
    private fun closeTabsUnder(uri: Uri) {
        val target = uri.toString()
        for (i in tabs.indices.reversed()) {
            val tabUri = tabs[i].uri.toString()
            if (tabUri == target || tabUri.startsWith("$target%2F") || tabUri.startsWith("$target/")) {
                closeTab(i)
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

    override fun selectTab(index: Int) {
        if (index in tabs.indices) currentIndex = index
    }

    override fun closeTab(index: Int) {
        if (index !in tabs.indices) return
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

    override fun onEditorChange(new: TextFieldValue) {
        val tab = currentTab ?: return
        val indentUnit = " ".repeat(tabWidth)
        val processed = applyEditorInput(tab.value, new, indentUnit, autoIndent, autoCloseBrackets)
        if (processed.text != tab.value.text) tab.pushUndo(tab.value)
        tab.value = processed
    }

    /** Moves the caret to the start of [line] (1-based), without recording an undo step. */
    override fun goToLine(line: Int) {
        val tab = currentTab ?: return
        val offset = lineStartOffset(tab.value.text, line)
        setSelection(TextRange(offset))
    }

    /** Moves the selection without recording an undo step (used by find navigation). */
    override fun setSelection(range: TextRange) {
        val tab = currentTab ?: return
        tab.value = tab.value.copy(selection = range)
    }

    override fun undo() {
        currentTab?.undo()
    }

    override fun redo() {
        currentTab?.redo()
    }

    override fun save() {
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
    override fun insertText(insert: String) {
        val tab = currentTab ?: return
        val v = tab.value
        val start = v.selection.min
        val end = v.selection.max
        val newText = v.text.substring(0, start) + insert + v.text.substring(end)
        onEditorChange(TextFieldValue(newText, TextRange(start + insert.length)))
    }

    override fun toggleSoftWrap() {
        softWrap = !softWrap
        viewModelScope.launch { prefs.setSoftWrap(softWrap) }
    }

    override fun setFontSize(size: Int) {
        fontSize = size
        viewModelScope.launch { prefs.setFontSize(size) }
    }

    override fun setTabWidth(width: Int) {
        tabWidth = width
        viewModelScope.launch { prefs.setTabWidth(width) }
    }

    override fun setThemeMode(mode: String) {
        themeMode = mode
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    override fun setAutoIndent(enabled: Boolean) {
        autoIndent = enabled
        viewModelScope.launch { prefs.setAutoIndent(enabled) }
    }

    override fun setAutoCloseBrackets(enabled: Boolean) {
        autoCloseBrackets = enabled
        viewModelScope.launch { prefs.setAutoCloseBrackets(enabled) }
    }

    // ---- Find & replace ----

    override fun replaceRange(range: IntRange, replacement: String) {
        val tab = currentTab ?: return
        val text = tab.value.text
        if (range.first < 0 || range.last + 1 > text.length) return
        val newText = text.substring(0, range.first) + replacement + text.substring(range.last + 1)
        onEditorChange(TextFieldValue(newText, TextRange(range.first + replacement.length)))
    }

    override fun replaceAll(matches: List<IntRange>, replacement: String) {
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

    private fun buildRegex(pattern: String, caseSensitive: Boolean): Regex =
        Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))

    override fun replaceMatchRegex(range: IntRange, pattern: String, replacement: String, caseSensitive: Boolean) {
        val tab = currentTab ?: return
        val text = tab.value.text
        if (range.first < 0 || range.last + 1 > text.length) return
        val regex = runCatching { buildRegex(pattern, caseSensitive) }.getOrNull() ?: return
        val sub = text.substring(range.first, range.last + 1)
        val replaced = runCatching { regex.replace(sub, replacement) }.getOrNull() ?: return
        replaceRange(range, replaced)
    }

    override fun replaceAllRegex(pattern: String, replacement: String, caseSensitive: Boolean) {
        val tab = currentTab ?: return
        val regex = runCatching { buildRegex(pattern, caseSensitive) }.getOrNull() ?: return
        val text = tab.value.text
        val newText = runCatching { regex.replace(text, replacement) }.getOrNull() ?: return
        if (newText == text) return
        onEditorChange(TextFieldValue(newText, TextRange(newText.length)))
    }

    // ---- Project search ----

    override fun searchProject(query: String, caseSensitive: Boolean, useRegex: Boolean) {
        val tree = treeUri ?: return
        val rootId = rootDocumentId ?: return
        searchJob?.cancel()
        if (query.isBlank()) {
            searchResults.clear()
            isSearching = false
            return
        }
        isSearching = true
        searchResults.clear()
        searchJob = viewModelScope.launch {
            val collected = withContext(Dispatchers.IO) {
                val out = ArrayList<SearchResult>()
                val stack = ArrayDeque<String>()
                stack.addLast(rootId)
                while (stack.isNotEmpty() && out.size < MAX_SEARCH_RESULTS) {
                    coroutineContext.ensureActive()
                    val dirId = stack.removeLast()
                    val children = runCatching {
                        SafFiles.listChildren(context, tree, dirId)
                    }.getOrDefault(emptyList())
                    for (child in children) {
                        if (out.size >= MAX_SEARCH_RESULTS) break
                        if (child.isDirectory) {
                            if (child.name !in SKIP_DIRS) stack.addLast(child.documentId)
                            continue
                        }
                        if (Language.fromFileName(child.name) == Language.PLAINTEXT) continue
                        val text = runCatching { SafFiles.readText(context, child.uri) }.getOrNull() ?: continue
                        if (text.length > MAX_SEARCH_FILE_SIZE) continue
                        val matches = findLineMatches(text, query, caseSensitive, useRegex, MAX_MATCHES_PER_FILE)
                        for (m in matches) {
                            out.add(SearchResult(child.uri, child.name, m.line, m.preview))
                            if (out.size >= MAX_SEARCH_RESULTS) break
                        }
                    }
                }
                out
            }
            searchResults.clear()
            searchResults.addAll(collected)
            isSearching = false
        }
    }

    override fun openSearchResult(result: SearchResult) {
        val existing = tabs.indexOfFirst { it.uri == result.uri }
        if (existing >= 0) {
            currentIndex = existing
            goToLine(result.line)
            return
        }
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { SafFiles.readText(context, result.uri) }.getOrDefault("")
            }
            tabs.add(OpenTab(result.uri, result.name, text, Language.fromFileName(result.name)))
            currentIndex = tabs.lastIndex
            goToLine(result.line)
        }
    }

    private companion object {
        const val MAX_SEARCH_RESULTS = 500
        const val MAX_MATCHES_PER_FILE = 50
        const val MAX_SEARCH_FILE_SIZE = 500_000
        val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea")
    }
}
