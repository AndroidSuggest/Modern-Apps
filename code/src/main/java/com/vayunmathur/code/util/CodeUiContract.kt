package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vayunmathur.code.syntax.Language

/**
 * The UI contract between [EditorViewModel] and the editor screens.
 *
 * The screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the ViewModel implements [CodeActions].
 *
 * Note that the file paths, undo stacks and IO plumbing behind these values stay in `util`:
 * nothing the UI draws needs them, and a preview could not supply them.
 */

/** One open file as the editor draws it. */
data class TabUiState(
    val name: String,
    val value: TextFieldValue = TextFieldValue(),
    val language: Language = Language.PLAINTEXT,
    val isDirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/** One row of the flat, lazily-expanded file tree. */
data class TreeRowUiState(
    val name: String,
    val depth: Int = 0,
    val isDirectory: Boolean = false,
    val expanded: Boolean = false,
)

/** One hit from a project-wide search: the file path, the 1-based line and a preview of that line. */
data class SearchResult(
    val path: String,
    val name: String,
    val line: Int,
    val preview: String,
)

/** Everything the editor screen draws. */
data class CodeUiState(
    val tabs: List<TabUiState> = emptyList(),
    val currentIndex: Int = -1,
    val softWrap: Boolean = false,
    /** Display name of the opened folder, shown as the file pane's header. */
    val rootName: String? = null,
    /** False until a folder has been picked, which is when the tree replaces its empty state. */
    val folderOpen: Boolean = false,
    val nodes: List<TreeRowUiState> = emptyList(),
    val fontSize: Int = 14,
    val tabWidth: Int = 4,
    val autoIndent: Boolean = true,
    val autoCloseBrackets: Boolean = true,
    val searchResults: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
) {
    val currentTab: TabUiState? get() = tabs.getOrNull(currentIndex)
}

/**
 * Editor callbacks. Every method has a no-op default so a preview can render the screens
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * Tabs and tree rows are addressed by index because that is all the state carries; the
 * ViewModel still owns the [OpenTab]/[TreeNode] behind each one.
 */
interface CodeActions {
    fun selectTab(index: Int) {}
    fun closeTab(index: Int) {}

    /** Expand/collapse a directory row, or open a file row in a tab. */
    fun toggleNode(index: Int) {}

    fun undo() {}
    fun redo() {}
    fun save() {}
    fun toggleSoftWrap() {}
    fun insertText(insert: String) {}
    fun onEditorChange(new: TextFieldValue) {}

    // ---- Line editing ----

    /** Toggle the line comment on the line(s) the selection touches (no-op if the language has none). */
    fun toggleComment() {}

    /** Duplicate the line(s) the selection touches. */
    fun duplicateLine() {}

    /** Move the line(s) the selection touches up one line. */
    fun moveLineUp() {}

    /** Move the line(s) the selection touches down one line. */
    fun moveLineDown() {}

    /** Delete the line(s) the selection touches. */
    fun deleteLine() {}

    /** Move the selection without recording an undo step (used by find navigation). */
    fun setSelection(range: TextRange) {}

    /** Move the caret to the start of [line] (1-based). */
    fun goToLine(line: Int) {}

    fun replaceRange(range: IntRange, replacement: String) {}
    fun replaceAll(matches: List<IntRange>, replacement: String) {}

    /** Replace the match at [range] using regex substitution (supports `$1` group refs). */
    fun replaceMatchRegex(range: IntRange, pattern: String, replacement: String, caseSensitive: Boolean) {}

    /** Replace every regex match in the current file (supports `$1` group refs). */
    fun replaceAllRegex(pattern: String, replacement: String, caseSensitive: Boolean) {}

    // ---- Project search ----

    /** Search every text file under the open folder for [query]. */
    fun searchProject(query: String, caseSensitive: Boolean, useRegex: Boolean) {}

    /** Open the file for a search result and jump to its line. */
    fun openSearchResult(result: SearchResult) {}

    // ---- File operations ----

    /** Create a file under the given parent row (null = tree root) and open it in a tab. */
    fun createFile(parentIndex: Int?, name: String) {}

    /** Create a folder under the given parent row (null = tree root). */
    fun createFolder(parentIndex: Int?, name: String) {}

    /** Rename the file/folder at [index]. */
    fun renameNode(index: Int, newName: String) {}

    /** Delete the file/folder at [index], closing any tabs it (or its descendants) backs. */
    fun deleteNode(index: Int) {}

    // ---- Settings ----

    fun setFontSize(size: Int) {}
    fun setTabWidth(width: Int) {}
    fun setThemeMode(mode: String) {}
    fun setAutoIndent(enabled: Boolean) {}
    fun setAutoCloseBrackets(enabled: Boolean) {}

    companion object {
        val Noop: CodeActions = object : CodeActions {}
    }
}
