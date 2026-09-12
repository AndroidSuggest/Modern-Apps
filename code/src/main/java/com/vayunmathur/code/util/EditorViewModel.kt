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
import com.vayunmathur.code.syntax.EditorThemes
import com.vayunmathur.code.syntax.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

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

    internal val context get() = getApplication<Application>()
    internal val prefs = EditorPrefs(context)

    // ---- File tree ----
    var rootDir by mutableStateOf<File?>(null)
        internal set
    var rootName by mutableStateOf<String?>(null)
        internal set
    val nodes = mutableStateListOf<TreeNode>()

    // ---- Tabs ----
    val tabs = mutableStateListOf<OpenTab>()
    var currentIndex by mutableStateOf(-1)
        internal set
    val currentTab: OpenTab? get() = tabs.getOrNull(currentIndex)

    /** When set, a second pane shows this tab beside the current one (split view). */
    var secondaryIndex by mutableStateOf<Int?>(null)
        internal set

    /** True when the secondary split pane holds focus, so shared actions target it instead. */
    var focusedSecondary by mutableStateOf(false)
        internal set

    /** The tab shared toolbar/find/navigation actions target: the focused pane's tab. */
    internal val activeTab: OpenTab?
        get() = if (focusedSecondary) secondaryIndex?.let { tabs.getOrNull(it) } else currentTab

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
    private val _autoSave = mutableStateOf(false)
    val autoSave: Boolean get() = _autoSave.value
    private val _trimTrailingOnSave = mutableStateOf(false)
    val trimTrailingOnSave: Boolean get() = _trimTrailingOnSave.value
    private val _finalNewlineOnSave = mutableStateOf(false)
    val finalNewlineOnSave: Boolean get() = _finalNewlineOnSave.value
    private val _editorTheme = mutableStateOf(EditorThemes.DEFAULT)
    val editorTheme: String get() = _editorTheme.value
    private val _experimentalEditor = mutableStateOf(false)
    val experimentalEditor: Boolean get() = _experimentalEditor.value
    private val _showWhitespace = mutableStateOf(false)
    val showWhitespace: Boolean get() = _showWhitespace.value
    private val _showIndentGuides = mutableStateOf(false)
    val showIndentGuides: Boolean get() = _showIndentGuides.value
    private val _showMinimap = mutableStateOf(false)
    val showMinimap: Boolean get() = _showMinimap.value
    internal var autoSaveJob: Job? = null

    // ---- Project search ----
    val searchResults = mutableStateListOf<SearchResult>()
    var isSearching by mutableStateOf(false)
        internal set
    internal var searchJob: Job? = null

    // ---- Autocomplete ----
    val completions = mutableStateListOf<Completion>()
    var showCompletions by mutableStateOf(false)
        internal set
    internal var completionsJob: Job? = null

    // ---- Diagnostics ----
    val diagnostics = mutableStateListOf<Diagnostic>()
    internal var diagnosticsJob: Job? = null

    // ---- User snippets ----
    val userSnippets = mutableStateListOf<UserSnippet>()

    /** Persisted per-file collapsed fold headers (absolute path -> header lines). */
    internal val foldStateByPath = HashMap<String, Set<Int>>()

    // ---- Git ----
    var gitIsRepo by mutableStateOf(false)
        internal set
    var gitStatus by mutableStateOf<GitStatus?>(null)
        internal set
    val gitLog = mutableStateListOf<GitCommitInfo>()
    val gitBranches = mutableStateListOf<String>()
    var gitBusy by mutableStateOf(false)
        internal set
    var gitMessage by mutableStateOf<String?>(null)
        internal set
    var gitDiff by mutableStateOf<String?>(null)
        internal set
    var gitDiffRows by mutableStateOf<List<DiffRow>?>(null)
        internal set

    internal val _gitUsername = mutableStateOf("")
    val gitUsername: String get() = _gitUsername.value
    internal val _gitToken = mutableStateOf("")
    val gitToken: String get() = _gitToken.value
    internal val _gitAuthorName = mutableStateOf("")
    val gitAuthorName: String get() = _gitAuthorName.value
    internal val _gitAuthorEmail = mutableStateOf("")
    val gitAuthorEmail: String get() = _gitAuthorEmail.value

    // ---- Terminal ----
    val terminalLines = mutableStateListOf<String>()
    var terminalRunning by mutableStateOf(false)
        internal set
    internal var terminal: TerminalSession? = null

    // ---- Quick-open ----
    val projectFiles = mutableStateListOf<ProjectFileEntry>()
    internal val recentPaths = mutableStateListOf<String>()
    internal var projectFilesJob: Job? = null

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
                    changedOnDisk = it.changedOnDisk,
                    charsetName = it.charset.name(),
                    lineEndingName = it.lineEnding.name,
                    foldedHeaders = it.foldedHeaders,
                )
            },
            currentIndex = currentIndex,
            secondaryIndex = secondaryIndex ?: -1,
            focusedSecondary = focusedSecondary,
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
            completions = completions.toList(),
            showCompletions = showCompletions,
            editorTheme = editorTheme,
            experimentalEditor = experimentalEditor,
            showWhitespace = showWhitespace,
            showIndentGuides = showIndentGuides,
            showMinimap = showMinimap,
            projectFiles = projectFiles.toList(),
            recentFiles = recentPaths.map { toProjectEntry(File(it)) },
            diagnostics = diagnostics.toList(),
        )

    init {
        viewModelScope.launch { softWrap = prefs.softWrap.first() }
        viewModelScope.launch { _fontSize.value = prefs.fontSize.first() }
        viewModelScope.launch { _tabWidth.value = prefs.tabWidth.first() }
        viewModelScope.launch { _themeMode.value = prefs.themeMode.first() }
        viewModelScope.launch { _autoIndent.value = prefs.autoIndent.first() }
        viewModelScope.launch { _autoCloseBrackets.value = prefs.autoCloseBrackets.first() }
        viewModelScope.launch { _autoSave.value = prefs.autoSave.first() }
        viewModelScope.launch { _trimTrailingOnSave.value = prefs.trimTrailingOnSave.first() }
        viewModelScope.launch { _finalNewlineOnSave.value = prefs.finalNewlineOnSave.first() }
        viewModelScope.launch { _editorTheme.value = prefs.editorTheme.first() }
        viewModelScope.launch { _experimentalEditor.value = prefs.experimentalEditor.first() }
        viewModelScope.launch { _showWhitespace.value = prefs.showWhitespace.first() }
        viewModelScope.launch { _showIndentGuides.value = prefs.showIndentGuides.first() }
        viewModelScope.launch { _showMinimap.value = prefs.showMinimap.first() }
        viewModelScope.launch { _gitUsername.value = prefs.gitUsername.first() }
        viewModelScope.launch { _gitToken.value = prefs.gitToken.first() }
        viewModelScope.launch { _gitAuthorName.value = prefs.gitAuthorName.first() }
        viewModelScope.launch { _gitAuthorEmail.value = prefs.gitAuthorEmail.first() }
        viewModelScope.launch { recentPaths.addAll(prefs.recentFiles.first()) }
        viewModelScope.launch { userSnippets.addAll(prefs.userSnippets.first()) }
        viewModelScope.launch {
            prefs.foldState.first().forEach { (path, lines) -> foldStateByPath[path] = lines.toSet() }
            // Apply to any tabs already restored before the fold state finished loading.
            for (tab in tabs) {
                val path = tab.file?.absolutePath ?: continue
                foldStateByPath[path]?.let { tab.foldedHeaders = it }
            }
        }
        viewModelScope.launch {
            val stored = prefs.folderPath.first() ?: return@launch
            val dir = File(stored)
            if (dir.isDirectory) {
                runCatching { loadFolder(dir) }.onFailure { prefs.clearFolderPath() }
            } else {
                prefs.clearFolderPath()
            }
        }
        viewModelScope.launch { restoreSession() }
    }

    /** Reopens the tabs from the previous session (files that still exist), off the main thread. */
    private suspend fun restoreSession() {
        val paths = prefs.sessionPaths.first()
        if (paths.isEmpty()) return
        val current = prefs.sessionCurrent.first()
        for (path in paths) {
            val file = File(path)
            if (!file.isFile) continue
            if (tabs.any { it.key == path }) continue
            tabs.add(makeFileTab(file))
        }
        currentIndex = tabs.indexOfFirst { it.file?.absolutePath == current }
            .takeIf { it >= 0 } ?: if (tabs.isEmpty()) -1 else 0
        scheduleDiagnostics()
    }

    /** Persists the current set of file-backed tabs and the foreground tab for session restore. */
    internal fun saveSession() {
        val paths = tabs.mapNotNull { it.file?.absolutePath }
        val current = currentTab?.file?.absolutePath
        viewModelScope.launch { prefs.setSession(paths, current) }
    }

    // ---- Folder handling ----
    // Implementations live in EditorFileTreeOps.kt; these overrides keep CodeActions conformance.

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
    // Helpers live in EditorFileTreeOps.kt; these overrides keep CodeActions conformance.

    /**
     * Re-lists the children of a folder and rebuilds that subtree in [nodes]. Expansion state
     * and already-loaded descendant rows of immediate child folders are preserved by path.
     * [parentIndex] null refreshes the tree root.
     */
    // Implemented in EditorFileTreeOps.kt as an internal extension.

    override fun createFile(parentIndex: Int?, name: String) = createFileImpl(parentIndex, name)

    override fun createFolder(parentIndex: Int?, name: String) = createFolderImpl(parentIndex, name)

    override fun renameNode(index: Int, newName: String) = renameNodeImpl(index, newName)

    override fun deleteNode(index: Int) = deleteNodeImpl(index)

    /** Closes any open tab whose file is [file] or lives beneath it (for a deleted directory). */
    // Implemented in EditorFileTreeOps.kt as an internal extension.

    // ---- Tab handling ----
    // selectTab/closeTab overrides stay here (CodeActions); open helpers live in EditorFileTreeOps.kt.

    override fun selectTab(index: Int) = selectTabImpl(index)

    override fun closeTab(index: Int) = closeTabImpl(index)

    // ---- Editing ----

    override fun onEditorChange(new: TextFieldValue) {
        editTab(currentTab ?: return, new, isPrimary = true)
    }

    override fun onSecondaryEditorChange(new: TextFieldValue) {
        val tab = secondaryIndex?.let { tabs.getOrNull(it) } ?: return
        editTab(tab, new, isPrimary = false)
    }

    /** Applies smart input to [tab] as a single undo step; only the primary pane drives completions. */
    private fun editTab(tab: OpenTab, new: TextFieldValue, isPrimary: Boolean) {
        val indentUnit = " ".repeat(tabWidth)
        val processed = applyEditorInput(tab.value, new, indentUnit, autoIndent, autoCloseBrackets)
        if (processed.text != tab.value.text) tab.pushUndo(tab.value)
        tab.value = processed
        if (autoSave) scheduleAutoSave()
        if (isPrimary) updateCompletions()
        scheduleDiagnostics()
    }

    /** Opens/closes the second editor pane, choosing an adjacent tab as the secondary. */
    override fun toggleSplit() {
        if (secondaryIndex != null) {
            secondaryIndex = null
            focusedSecondary = false
            return
        }
        if (tabs.size < 2 || currentIndex < 0) return
        val other = (currentIndex + 1).takeIf { it in tabs.indices } ?: (currentIndex - 1)
        secondaryIndex = other.takeIf { it in tabs.indices && it != currentIndex }
    }

    /** Records which split pane holds focus, so shared toolbar/find/nav actions target it. */
    override fun focusPane(secondary: Boolean) {
        focusedSecondary = secondary && secondaryIndex != null
    }

    // ---- Autocomplete ----

    override fun requestCompletions() = updateCompletions()

    /**
     * Recomputes the completion list from the caret's word prefix and the open buffers.
     *
     * Debounced and computed off the main thread: scanning every open buffer for identifiers is
     * O(all open documents), so doing it inline on each keystroke stalled typing in a large file.
     */
    private fun updateCompletions() {
        completionsJob?.cancel()
        val tab = currentTab
        if (tab == null || !tab.value.selection.collapsed) {
            dismissCompletions()
            return
        }
        val prefix = currentWordPrefix(tab.value.text, tab.value.selection.start)
        if (prefix.length < MIN_COMPLETION_PREFIX) {
            dismissCompletions()
            return
        }
        val buffers = tabs.map { it.value.text }.filter { it.length <= MAX_COMPLETION_BUFFER_CHARS }
        val language = tab.language
        val snippets = userSnippets.toList()
        completionsJob = viewModelScope.launch {
            delay(COMPLETIONS_DELAY_MS)
            val list = withContext(Dispatchers.Default) {
                computeCompletions(prefix, language, buffers, MAX_COMPLETIONS, snippets)
            }
            completions.clear()
            completions.addAll(list)
            showCompletions = list.isNotEmpty()
        }
    }

    override fun acceptCompletion(item: Completion) {
        val tab = currentTab ?: return
        val v = tab.value
        val caret = v.selection.start
        val prefix = currentWordPrefix(v.text, caret)
        val start = caret - prefix.length
        val newText = v.text.substring(0, start) + item.insertText + v.text.substring(caret)
        val newCaret = (start + item.caretOffset).coerceIn(0, newText.length)
        tab.pushUndo(v)
        tab.value = TextFieldValue(newText, TextRange(newCaret))
        dismissCompletions()
        if (autoSave) scheduleAutoSave()
        scheduleDiagnostics()
    }

    override fun dismissCompletions() {
        completionsJob?.cancel()
        if (completions.isNotEmpty()) completions.clear()
        showCompletions = false
    }

    // ---- User snippets ----

    fun addSnippet(snippet: UserSnippet) {
        userSnippets.add(snippet)
        persistSnippets()
    }

    fun updateSnippet(index: Int, snippet: UserSnippet) {
        if (index in userSnippets.indices) {
            userSnippets[index] = snippet
            persistSnippets()
        }
    }

    fun deleteSnippet(index: Int) {
        if (index in userSnippets.indices) {
            userSnippets.removeAt(index)
            persistSnippets()
        }
    }

    private fun persistSnippets() {
        viewModelScope.launch { prefs.setUserSnippets(userSnippets.toList()) }
    }

    // ---- Git ----
    // Operations live in EditorGitOps.kt as extensions.

    // ---- Terminal ----
    // Operations live in EditorTerminalOps.kt as extensions.

    override fun onCleared() {
        super.onCleared()
        terminal?.close()
    }

    /** Commits a find/replace edit to [tab] as one undo step, bypassing smart input. */
    private fun commitEdit(tab: OpenTab, new: TextFieldValue) {
        if (new.text != tab.value.text) tab.pushUndo(tab.value)
        tab.value = new
        if (autoSave) scheduleAutoSave()
        scheduleDiagnostics()
    }

    /** Debounced off-main recompute of diagnostics for the current tab. */
    internal fun scheduleDiagnostics() {
        diagnosticsJob?.cancel()
        val tab = currentTab
        if (tab == null) {
            diagnostics.clear()
            return
        }
        val text = tab.value.text
        val language = tab.language
        diagnosticsJob = viewModelScope.launch {
            delay(DIAGNOSTICS_DELAY_MS)
            val result = withContext(Dispatchers.Default) { computeDiagnostics(text, language) }
            diagnostics.clear()
            diagnostics.addAll(result)
        }
    }

    /** Applies a pure whole-line edit as a single undo step. */
    private fun applyLineEdit(transform: (TextFieldValue) -> TextFieldValue) {
        val tab = activeTab ?: return
        val next = transform(tab.value)
        if (next.text == tab.value.text && next.selection == tab.value.selection) return
        tab.pushUndo(tab.value)
        tab.value = next
        if (autoSave) scheduleAutoSave()
        scheduleDiagnostics()
    }

    override fun toggleComment() {
        val prefix = activeTab?.language?.lineCommentPrefix ?: return
        applyLineEdit { toggleLineComment(it, prefix) }
    }

    override fun duplicateLine() = applyLineEdit(::duplicateLine)

    override fun moveLineUp() = applyLineEdit(::moveLineUp)

    override fun moveLineDown() = applyLineEdit(::moveLineDown)

    override fun deleteLine() = applyLineEdit(::deleteLine)

    override fun formatDocument() {
        val tab = activeTab ?: return
        val formatted = when (tab.language) {
            Language.JSON -> formatJson(tab.value.text)
            Language.XML -> formatXml(tab.value.text)
            else -> null
        } ?: return
        if (formatted == tab.value.text) return
        tab.pushUndo(tab.value)
        tab.value = TextFieldValue(formatted, TextRange(formatted.length))
        if (autoSave) scheduleAutoSave()
        scheduleDiagnostics()
    }

    override fun resolveConflicts(resolutions: List<Resolution>) {
        val tab = activeTab ?: return
        val resolved = applyResolutions(tab.value.text, resolutions)
        if (resolved == tab.value.text) return
        tab.pushUndo(tab.value)
        tab.value = TextFieldValue(resolved, TextRange(resolved.length))
        if (autoSave) scheduleAutoSave()
        scheduleDiagnostics()
    }

    /** Moves the caret to the start of [line] (1-based), without recording an undo step. */
    override fun goToLine(line: Int) {
        val tab = activeTab ?: return
        val offset = lineStartOffset(tab.value.text, line)
        setSelection(TextRange(offset))
    }

    /** Moves the selection without recording an undo step (used by find navigation). */
    override fun setSelection(range: TextRange) {
        val tab = activeTab ?: return
        tab.value = tab.value.copy(selection = range)
    }

    // ---- Folding (experimental editor) ----

    override fun toggleFold(headerLine: Int) {
        val tab = activeTab ?: return
        tab.foldedHeaders =
            if (headerLine in tab.foldedHeaders) tab.foldedHeaders - headerLine
            else tab.foldedHeaders + headerLine
        persistFoldState(tab)
    }

    override fun foldAllInTab() {
        val tab = activeTab ?: return
        tab.foldedHeaders = computeFoldRegions(tab.value.text).map { it.startLine }.toSet()
        persistFoldState(tab)
    }

    override fun unfoldAll() {
        val tab = activeTab ?: return
        tab.foldedHeaders = emptySet()
        persistFoldState(tab)
    }

    /** Records [tab]'s fold headers in the per-path store and persists it (file-backed tabs only). */
    private fun persistFoldState(tab: OpenTab) {
        val path = tab.file?.absolutePath ?: return
        if (tab.foldedHeaders.isEmpty()) foldStateByPath.remove(path)
        else foldStateByPath[path] = tab.foldedHeaders
        viewModelScope.launch { prefs.setFoldState(foldStateByPath.mapValues { it.value.toList() }) }
    }

    override fun undo() {
        activeTab?.undo()
    }

    override fun redo() {
        activeTab?.redo()
    }

    override fun save() {
        val tab = activeTab ?: return
        saveTab(tab)
    }

    override fun saveAll() {
        for (tab in tabs) if (tab.file != null && tab.isDirty) saveTab(tab)
    }

    // Save/reload/external-change handling lives in EditorSaveOps.kt as extensions.

    override fun reloadFromDisk() {
        val tab = currentTab ?: return
        viewModelScope.launch { applyReload(tab) }
    }

    override fun dismissDiskChange() {
        val tab = currentTab ?: return
        viewModelScope.launch {
            val file = tab.file ?: return@launch
            val (modified, length) = withContext(Dispatchers.IO) { file.lastModified() to file.length() }
            tab.diskModified = modified
            tab.diskLength = length
            tab.changedOnDisk = false
        }
    }

    /** Inserts [insert] at the caret, replacing any current selection (used by the Tab button). */
    override fun insertText(insert: String) {
        val tab = activeTab ?: return
        val v = tab.value
        val start = v.selection.min
        val end = v.selection.max
        val newText = v.text.substring(0, start) + insert + v.text.substring(end)
        editTab(tab, TextFieldValue(newText, TextRange(start + insert.length)), isPrimary = tab === currentTab)
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

    fun setAutoSave(enabled: Boolean) {
        _autoSave.value = enabled
        viewModelScope.launch { prefs.setAutoSave(enabled) }
        if (enabled) scheduleAutoSave()
    }

    fun setTrimTrailingOnSave(enabled: Boolean) {
        _trimTrailingOnSave.value = enabled
        viewModelScope.launch { prefs.setTrimTrailingOnSave(enabled) }
    }

    fun setFinalNewlineOnSave(enabled: Boolean) {
        _finalNewlineOnSave.value = enabled
        viewModelScope.launch { prefs.setFinalNewlineOnSave(enabled) }
    }

    fun setEditorTheme(theme: String) {
        _editorTheme.value = theme
        viewModelScope.launch { prefs.setEditorTheme(theme) }
    }

    fun setExperimentalEditor(enabled: Boolean) {
        _experimentalEditor.value = enabled
        viewModelScope.launch { prefs.setExperimentalEditor(enabled) }
    }

    fun setShowWhitespace(enabled: Boolean) {
        _showWhitespace.value = enabled
        viewModelScope.launch { prefs.setShowWhitespace(enabled) }
    }

    fun setShowIndentGuides(enabled: Boolean) {
        _showIndentGuides.value = enabled
        viewModelScope.launch { prefs.setShowIndentGuides(enabled) }
    }

    fun setShowMinimap(enabled: Boolean) {
        _showMinimap.value = enabled
        viewModelScope.launch { prefs.setShowMinimap(enabled) }
    }

    // ---- Find & replace ----

    override fun replaceRange(range: IntRange, replacement: String) {
        val tab = activeTab ?: return
        val text = tab.value.text
        if (range.first < 0 || range.last + 1 > text.length) return
        val newText = text.substring(0, range.first) + replacement + text.substring(range.last + 1)
        commitEdit(tab, TextFieldValue(newText, TextRange(range.first + replacement.length)))
    }

    override fun replaceAll(matches: List<IntRange>, replacement: String) {
        val tab = activeTab ?: return
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
        commitEdit(tab, TextFieldValue(sb.toString(), TextRange(sb.length)))
    }

    private fun buildRegex(pattern: String, caseSensitive: Boolean): Regex =
        Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))

    override fun replaceMatchRegex(range: IntRange, pattern: String, replacement: String, caseSensitive: Boolean) {
        val tab = activeTab ?: return
        val text = tab.value.text
        if (range.first < 0 || range.last + 1 > text.length) return
        val regex = runCatching { buildRegex(pattern, caseSensitive) }.getOrNull() ?: return
        val sub = text.substring(range.first, range.last + 1)
        val replaced = runCatching { regex.replace(sub, replacement) }.getOrNull() ?: return
        replaceRange(range, replaced)
    }

    override fun replaceAllRegex(pattern: String, replacement: String, caseSensitive: Boolean) {
        val tab = activeTab ?: return
        val regex = runCatching { buildRegex(pattern, caseSensitive) }.getOrNull() ?: return
        val text = tab.value.text
        val newText = runCatching { regex.replace(text, replacement) }.getOrNull() ?: return
        if (newText == text) return
        commitEdit(tab, TextFieldValue(newText, TextRange(newText.length)))
    }

    // ---- Project search ----
    // Implementations live in EditorSearchOps.kt; these overrides keep CodeActions conformance.

    override fun searchProject(query: String, caseSensitive: Boolean, useRegex: Boolean) =
        searchProjectImpl(query, caseSensitive, useRegex)

    override fun openSearchResult(result: SearchResult) = openSearchResultImpl(result)

    // ---- Quick-open ----
    // Implementations live in EditorSearchOps.kt.

    override fun openPath(path: String) = openFile(File(path))

    override fun refreshProjectFiles() = refreshProjectFilesImpl()

    private companion object {
        internal const val DIAGNOSTICS_DELAY_MS = 400L
        const val COMPLETIONS_DELAY_MS = 150L
        const val MIN_COMPLETION_PREFIX = 1
        const val MAX_COMPLETIONS = 50
        // Buffers larger than this are left out of the identifier scan. A multi-megabyte file has
        // no useful completions in it anyway, and scanning it on every keystroke burns a core.
        const val MAX_COMPLETION_BUFFER_CHARS = 1_000_000
    }
}
