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
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** One expandable row in the file-tree pane; the tree is stored as a flat, ordered list. */
class TreeNode(val entry: FileEntry, val depth: Int) {
    var expanded by mutableStateOf(false)
    var loading by mutableStateOf(false)
}

/**
 * One open file. Editor content lives in [value]; [savedText] is the last persisted text so
 * [isDirty] can drive the unsaved-dot. Undo/redo are plain deques (not observed directly);
 * [canUndo]/[canRedo] mirror their emptiness as state so the toolbar buttons stay reactive.
 *
 * Most tabs are backed by a real [file]. Files opened through a VIEW/EDIT intent from another
 * app arrive as a `content://` [externalUri] instead and are [readOnly] (no `save` target).
 */
class OpenTab(
    file: File?,
    externalUri: Uri? = null,
    val readOnly: Boolean = false,
    initialName: String,
    initialText: String,
    language: Language,
) {
    var file by mutableStateOf(file)
    var externalUri by mutableStateOf(externalUri)
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

    /** Stable identity used to dedup tabs: the file path, or the external URI string. */
    val key: String get() = file?.absolutePath ?: externalUri.toString()

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
 *
 * The file backend is real [File] paths (the app holds `MANAGE_EXTERNAL_STORAGE`), so folder,
 * tree and file operations all go through [FileFiles].
 */
class EditorViewModel(application: Application) : AndroidViewModel(application), CodeActions {

    private val context get() = getApplication<Application>()
    private val prefs = EditorPrefs(context)

    // ---- File tree ----
    var rootDir by mutableStateOf<File?>(null)
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

    // These five are read as `fontSize`/`tabWidth`/... but written through the CodeActions
    // `setFontSize`/`setTabWidth`/... methods. Backing them by a private MutableState (rather than
    // a `var ... private set`) avoids a JVM signature clash between the generated property setter
    // and the same-named interface method.
    private val _fontSize = mutableStateOf(EditorPrefs.DEFAULT_FONT_SIZE)
    val fontSize: Int get() = _fontSize.value
    private val _tabWidth = mutableStateOf(EditorPrefs.DEFAULT_TAB_WIDTH)
    val tabWidth: Int get() = _tabWidth.value
    private val _themeMode = mutableStateOf(EditorPrefs.THEME_SYSTEM)
    val themeMode: String get() = _themeMode.value
    private val _autoIndent = mutableStateOf(true)
    val autoIndent: Boolean get() = _autoIndent.value
    private val _autoCloseBrackets = mutableStateOf(true)
    val autoCloseBrackets: Boolean get() = _autoCloseBrackets.value

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
            folderOpen = rootDir != null,
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
        viewModelScope.launch { _fontSize.value = prefs.fontSize.first() }
        viewModelScope.launch { _tabWidth.value = prefs.tabWidth.first() }
        viewModelScope.launch { _themeMode.value = prefs.themeMode.first() }
        viewModelScope.launch { _autoIndent.value = prefs.autoIndent.first() }
        viewModelScope.launch { _autoCloseBrackets.value = prefs.autoCloseBrackets.first() }
        viewModelScope.launch {
            val stored = prefs.folderPath.first() ?: return@launch
            val dir = File(stored)
            if (dir.isDirectory) {
                runCatching { loadFolder(dir) }.onFailure { prefs.clearFolderPath() }
            } else {
                prefs.clearFolderPath()
            }
        }
    }

    // ---- Folder handling ----

    /** Opens [dir] as the project root and loads its top level, persisting it for relaunch. */
    fun openFolder(dir: File) {
        viewModelScope.launch {
            prefs.setFolderPath(dir.absolutePath)
            runCatching { loadFolder(dir) }
        }
    }

    fun closeFolder() {
        rootDir = null
        rootName = null
        nodes.clear()
        viewModelScope.launch { prefs.clearFolderPath() }
    }

    private suspend fun loadFolder(dir: File) {
        require(dir.isDirectory) { "Not a directory: $dir" }
        val children = withContext(Dispatchers.IO) { FileFiles.listChildren(dir) }
        rootDir = dir
        rootName = dir.name
        nodes.clear()
        nodes.addAll(children.map { TreeNode(it, depth = 0) })
    }

    /** Expands/collapses a directory row, or opens a file row in a tab. */
    override fun toggleNode(index: Int) {
        val node = nodes.getOrNull(index) ?: return
        if (!node.entry.isDirectory) {
            openFile(node.entry.file)
            return
        }

        if (node.expanded) {
            node.expanded = false
            removeDescendants(index)
        } else {
            node.expanded = true
            node.loading = true
            viewModelScope.launch {
                val children = withContext(Dispatchers.IO) { FileFiles.listChildren(node.entry.file) }
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
     * and already-loaded descendant rows of immediate child folders are preserved by path.
     * [parentIndex] null refreshes the tree root.
     */
    private suspend fun refreshChildren(parentIndex: Int?) {
        val parentFile = if (parentIndex == null) rootDir ?: return
        else nodes.getOrNull(parentIndex)?.entry?.file ?: return
        val parentDepth = if (parentIndex == null) -1 else nodes[parentIndex].depth
        val childDepth = parentDepth + 1

        val blockStart = (parentIndex ?: -1) + 1
        var blockEnd = blockStart
        while (blockEnd < nodes.size && nodes[blockEnd].depth > parentDepth) blockEnd++

        // Preserve existing immediate children (and their loaded subtrees) by path.
        val preservedNode = HashMap<String, TreeNode>()
        val preservedSubtree = HashMap<String, List<TreeNode>>()
        var i = blockStart
        while (i < blockEnd) {
            val child = nodes[i]
            if (child.depth == childDepth) {
                var j = i + 1
                while (j < blockEnd && nodes[j].depth > childDepth) j++
                val key = child.entry.file.absolutePath
                preservedNode[key] = child
                preservedSubtree[key] = nodes.subList(i + 1, j).toList()
                i = j
            } else {
                i++
            }
        }

        val entries = withContext(Dispatchers.IO) { FileFiles.listChildren(parentFile) }

        val rebuilt = ArrayList<TreeNode>()
        for (entry in entries) {
            val key = entry.file.absolutePath
            val existing = preservedNode[key]
            if (existing != null) {
                rebuilt.add(existing)
                rebuilt.addAll(preservedSubtree[key].orEmpty())
            } else {
                rebuilt.add(TreeNode(entry, childDepth))
            }
        }

        for (k in blockEnd - 1 downTo blockStart) nodes.removeAt(k)
        nodes.addAll(blockStart, rebuilt)
    }

    /** Resolves the create target directory: the tree root, or a directory row. */
    private fun parentFileFor(parentIndex: Int?): File? =
        if (parentIndex == null) rootDir else nodes.getOrNull(parentIndex)?.entry?.file

    override fun createFile(parentIndex: Int?, name: String) {
        val parent = parentFileFor(parentIndex) ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { FileFiles.createFile(parent, name) } ?: return@launch
            nodes.getOrNull(parentIndex ?: -1)?.expanded = true
            refreshChildren(parentIndex)
            openFile(file)
        }
    }

    override fun createFolder(parentIndex: Int?, name: String) {
        val parent = parentFileFor(parentIndex) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { FileFiles.createDirectory(parent, name) } ?: return@launch
            nodes.getOrNull(parentIndex ?: -1)?.expanded = true
            refreshChildren(parentIndex)
        }
    }

    override fun renameNode(index: Int, newName: String) {
        val node = nodes.getOrNull(index) ?: return
        val oldFile = node.entry.file
        viewModelScope.launch {
            val newFile = withContext(Dispatchers.IO) { FileFiles.rename(oldFile, newName) } ?: return@launch
            val at = nodes.indexOf(node)
            if (at >= 0) {
                // A renamed directory's descendant paths shift; drop them so a re-expand re-lists.
                if (node.entry.isDirectory) removeDescendants(at)
                nodes[at] = TreeNode(FileEntry(newFile, newName, node.entry.isDirectory), node.depth)
            }
            // Repoint open tabs backed by the renamed file (or living under a renamed directory).
            val oldPath = oldFile.absolutePath
            val newPath = newFile.absolutePath
            for (tab in tabs) {
                val p = tab.file?.absolutePath ?: continue
                if (p == oldPath) {
                    tab.file = newFile
                    tab.name = newName
                    tab.language = Language.fromFileName(newName)
                } else if (p.startsWith(oldPath + File.separator)) {
                    tab.file = File(newPath + p.substring(oldPath.length))
                }
            }
        }
    }

    override fun deleteNode(index: Int) {
        val node = nodes.getOrNull(index) ?: return
        val file = node.entry.file
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { FileFiles.delete(file) }
            if (!ok) return@launch
            val at = nodes.indexOf(node)
            if (at >= 0) {
                removeDescendants(at)
                nodes.removeAt(at)
            }
            closeTabsUnder(file)
        }
    }

    /** Closes any open tab whose file is [file] or lives beneath it (for a deleted directory). */
    private fun closeTabsUnder(file: File) {
        val target = file.absolutePath
        val prefix = target + File.separator
        for (i in tabs.indices.reversed()) {
            val p = tabs[i].file?.absolutePath ?: continue
            if (p == target || p.startsWith(prefix)) closeTab(i)
        }
    }

    // ---- Tab handling ----

    /** Opens [file] in a tab (focusing it if already open), reading its text off the main thread. */
    fun openFile(file: File) {
        val key = file.absolutePath
        val existing = tabs.indexOfFirst { it.key == key }
        if (existing >= 0) {
            currentIndex = existing
            return
        }
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { FileFiles.readText(file) }.getOrDefault("")
            }
            tabs.add(OpenTab(file = file, initialName = file.name, initialText = text, language = Language.fromFileName(file.name)))
            currentIndex = tabs.lastIndex
        }
    }

    /** Handles a VIEW/EDIT intent that carries a single file URI. */
    fun openExternal(uri: Uri) {
        if (uri.scheme == "file") {
            uri.path?.let { openFile(File(it)) }
            return
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val key = uri.toString()
        val existing = tabs.indexOfFirst { it.key == key }
        if (existing >= 0) {
            currentIndex = existing
            return
        }
        viewModelScope.launch {
            val displayName = withContext(Dispatchers.IO) { FileFiles.queryDisplayName(context, uri) } ?: "untitled"
            val text = withContext(Dispatchers.IO) {
                runCatching { FileFiles.readTextFromUri(context, uri) }.getOrDefault("")
            }
            tabs.add(
                OpenTab(
                    file = null,
                    externalUri = uri,
                    readOnly = true,
                    initialName = displayName,
                    initialText = text,
                    language = Language.fromFileName(displayName),
                )
            )
            currentIndex = tabs.lastIndex
        }
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
        val file = tab.file ?: return // external read-only tabs have no save target
        val textToSave = tab.value.text
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { FileFiles.writeText(file, textToSave) }.isSuccess
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
        _fontSize.value = size
        viewModelScope.launch { prefs.setFontSize(size) }
    }

    override fun setTabWidth(width: Int) {
        _tabWidth.value = width
        viewModelScope.launch { prefs.setTabWidth(width) }
    }

    override fun setThemeMode(mode: String) {
        _themeMode.value = mode
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    override fun setAutoIndent(enabled: Boolean) {
        _autoIndent.value = enabled
        viewModelScope.launch { prefs.setAutoIndent(enabled) }
    }

    override fun setAutoCloseBrackets(enabled: Boolean) {
        _autoCloseBrackets.value = enabled
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
        val root = rootDir ?: return
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
                val stack = ArrayDeque<File>()
                stack.addLast(root)
                while (stack.isNotEmpty() && out.size < MAX_SEARCH_RESULTS) {
                    coroutineContext.ensureActive()
                    val dir = stack.removeLast()
                    val children = runCatching { FileFiles.listChildren(dir) }.getOrDefault(emptyList())
                    for (child in children) {
                        if (out.size >= MAX_SEARCH_RESULTS) break
                        if (child.isDirectory) {
                            if (child.name !in SKIP_DIRS) stack.addLast(child.file)
                            continue
                        }
                        if (Language.fromFileName(child.name) == Language.PLAINTEXT) continue
                        if (child.file.length() > MAX_SEARCH_FILE_SIZE) continue
                        val text = runCatching { FileFiles.readText(child.file) }.getOrNull() ?: continue
                        val matches = findLineMatches(text, query, caseSensitive, useRegex, MAX_MATCHES_PER_FILE)
                        for (m in matches) {
                            out.add(SearchResult(child.file.absolutePath, child.name, m.line, m.preview))
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
        val existing = tabs.indexOfFirst { it.key == result.path }
        if (existing >= 0) {
            currentIndex = existing
            goToLine(result.line)
            return
        }
        val file = File(result.path)
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { FileFiles.readText(file) }.getOrDefault("")
            }
            tabs.add(OpenTab(file = file, initialName = result.name, initialText = text, language = Language.fromFileName(result.name)))
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
