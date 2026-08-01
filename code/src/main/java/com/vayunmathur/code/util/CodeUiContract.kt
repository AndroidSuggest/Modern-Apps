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
 * Note that the URIs, undo stacks and SAF plumbing behind these values stay in `util`:
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

    /** Move the selection without recording an undo step (used by find navigation). */
    fun setSelection(range: TextRange) {}

    fun replaceRange(range: IntRange, replacement: String) {}
    fun replaceAll(matches: List<IntRange>, replacement: String) {}

    companion object {
        val Noop: CodeActions = object : CodeActions {}
    }
}
