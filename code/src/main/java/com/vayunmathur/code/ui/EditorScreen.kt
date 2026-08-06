package com.vayunmathur.code.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.code.R
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCode
import com.vayunmathur.library.ui.IconFindReplace
import com.vayunmathur.library.ui.IconFormatIndentIncrease
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconWrapText
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.OverflowMenu
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.rememberDrawerState
import kotlinx.coroutines.launch

/**
 * Binds [EditorViewModel] to the stateless [EditorScreen].
 *
 * The two document pickers stay here: they need an activity result launcher, which is
 * exactly what a `@Preview` cannot provide.
 */
@Composable
fun EditorPage(
    viewModel: EditorViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenPreview: () -> Unit = {},
) {
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::openExternal) }
    val activity = LocalContext.current as? android.app.Activity

    EditorScreen(
        state = viewModel.uiState,
        actions = viewModel,
        onOpenFolder = onOpenFolder,
        onOpenFile = { fileLauncher.launch(arrayOf("*/*")) },
        onOpenSettings = onOpenSettings,
        onOpenSearch = onOpenSearch,
        onOpenGit = onOpenGit,
        onOpenTerminal = onOpenTerminal,
        onOpenPreview = onOpenPreview,
        onExitApp = { activity?.finish() },
    )
}

/**
 * Top-level editor scaffold: a navigation drawer holding the [FileTreePane], a top bar to
 * open it, then a tab strip, toolbar, optional find bar and the [CodeEditor] itself. Opening a
 * folder navigates to the in-app folder browser; single files still use the system file picker.
 *
 * No dependency on the ViewModel, so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun EditorScreen(
    state: CodeUiState,
    actions: CodeActions,
    onOpenFolder: () -> Unit = {},
    onOpenFile: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenPreview: () -> Unit = {},
    onExitApp: () -> Unit = {},
    /**
     * Seeds for the screen's own UI-only state (is the drawer showing, is the find bar open
     * and on what query). The app always takes the defaults; previews set them so a given
     * screen can be captured without driving the UI to get there.
     */
    initialDrawerOpen: Boolean = false,
    initialFind: String? = null,
) {
    val drawerState = rememberDrawerState(
        if (initialDrawerOpen) DrawerValue.Open else DrawerValue.Closed
    )
    val scope = rememberCoroutineScope()
    var showFind by remember { mutableStateOf(initialFind != null) }
    var showGoToLine by remember { mutableStateOf(false) }
    var showQuickOpen by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var showExitGuard by remember { mutableStateOf(false) }
    val anyDirty = state.tabs.any { it.isDirty }

    // Guard back/close when there are unsaved changes; offer Save all / Discard / Cancel.
    BackHandler(enabled = anyDirty) { showExitGuard = true }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    Key.P -> {
                        if (event.isShiftPressed) {
                            showPalette = true
                        } else {
                            actions.refreshProjectFiles()
                            showQuickOpen = true
                        }
                        true
                    }
                    else -> false
                }
            },
    ) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                FileTreePane(
                    state = state,
                    actions = actions,
                    onOpenFolder = onOpenFolder,
                    onOpenFile = onOpenFile,
                    onFileOpened = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.currentTab?.name ?: "Code") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { IconMenu() }
                    },
                    actions = {
                        IconButton(onClick = {
                            actions.refreshProjectFiles()
                            showQuickOpen = true
                        }) { IconSearch() }
                        IconButton(onClick = onOpenSettings) { IconSettings() }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                val tab = state.currentTab
                if (tab == null) {
                    EmptyEditorState(onOpenFolder = onOpenFolder, onOpenFile = onOpenFile)
                } else {
                    TabStrip(state, actions)
                    HorizontalDivider()
                    EditorToolbar(
                        state = state,
                        actions = actions,
                        onToggleFind = { showFind = !showFind },
                        onGoToLine = { showGoToLine = true },
                        onOpenSearch = onOpenSearch,
                        onOpenGit = onOpenGit,
                        onOpenTerminal = onOpenTerminal,
                        onOpenPreview = onOpenPreview,
                        onOpenQuickOpen = {
                            actions.refreshProjectFiles()
                            showQuickOpen = true
                        },
                        onOpenPalette = { showPalette = true },
                    )
                    HorizontalDivider()
                    if (tab.changedOnDisk) {
                        DiskChangedBanner(
                            onReload = { actions.reloadFromDisk() },
                            onKeep = { actions.dismissDiskChange() },
                        )
                        HorizontalDivider()
                    }
                    CodeEditor(
                        tab = tab,
                        actions = actions,
                        softWrap = state.softWrap,
                        fontSize = state.fontSize,
                        showFind = showFind,
                        onCloseFind = { showFind = false },
                        modifier = Modifier.weight(1f),
                        initialQuery = initialFind.orEmpty(),
                        completions = state.completions,
                        showCompletions = state.showCompletions,
                        editorTheme = state.editorTheme,
                    )
                }
            }
        }
    }

    if (showQuickOpen) {
        FuzzyPickerDialog(
            title = stringResource(R.string.quick_open),
            placeholder = stringResource(R.string.quick_open_hint),
            items = quickOpenItems(state) { actions.openPath(it) },
            emptyQueryItems = recentOpenItems(state) { actions.openPath(it) },
            onDismiss = { showQuickOpen = false },
        )
    }
    if (showPalette) {
        FuzzyPickerDialog(
            title = stringResource(R.string.command_palette),
            placeholder = stringResource(R.string.command_palette_hint),
            items = editorCommands(
                actions = actions,
                onToggleFind = { showFind = true },
                onGoToLine = { showGoToLine = true },
                onQuickOpen = {
                    actions.refreshProjectFiles()
                    showQuickOpen = true
                },
                onOpenSearch = onOpenSearch,
                onOpenGit = onOpenGit,
                onOpenTerminal = onOpenTerminal,
                onOpenPreview = onOpenPreview,
                onOpenSettings = onOpenSettings,
                onOpenFolder = onOpenFolder,
                onOpenFile = onOpenFile,
            ),
            onDismiss = { showPalette = false },
        )
    }
    if (showGoToLine) {
        GoToLineDialog(
            onGo = { actions.goToLine(it) },
            onDismiss = { showGoToLine = false },
        )
    }
    if (showExitGuard) {
        AlertDialog(
            onDismissRequest = { showExitGuard = false },
            title = { Text(stringResource(R.string.exit_unsaved_title)) },
            text = { Text(stringResource(R.string.exit_unsaved_message)) },
            confirmButton = {
                Row {
                    TextButton(onClick = { showExitGuard = false; onExitApp() }) {
                        Text(stringResource(R.string.discard))
                    }
                    TextButton(onClick = { actions.saveAll(); showExitGuard = false; onExitApp() }) {
                        Text(stringResource(R.string.save_all))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitGuard = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    }
}

/** Shown when nothing is open: prompts the user to open a folder or a single file. */
@Composable
private fun EmptyEditorState(onOpenFolder: () -> Unit, onOpenFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconCode(Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.no_file_open), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.open_a_folder_to_browse_your_project_or),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpenFolder) { Text(stringResource(R.string.open_folder)) }
            Button(onClick = onOpenFile) { Text(stringResource(R.string.open_file)) }
        }
    }
}

/** Horizontally scrollable strip of open tabs, each with a dirty indicator and close button. */
@Composable
private fun TabStrip(state: CodeUiState, actions: CodeActions) {
    var pendingCloseIndex by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        state.tabs.forEachIndexed { index, tab ->
            val selected = index == state.currentIndex
            val background =
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            Row(
                modifier = Modifier
                    .background(background)
                    .clickable { actions.selectTab(index) }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                if (tab.isDirty) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                IconButton(
                    onClick = {
                        if (tab.isDirty) pendingCloseIndex = index else actions.closeTab(index)
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    IconClose(Modifier.size(16.dp))
                }
            }
        }
    }

    pendingCloseIndex?.let { index ->
        ConfirmDialog(
            title = stringResource(R.string.discard_changes_title),
            confirmLabel = stringResource(R.string.discard),
            dismissLabel = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = { actions.closeTab(index) },
            onDismiss = { pendingCloseIndex = null },
        )
    }
}

/** Undo/redo, save, find, soft-wrap, tab-insert, an overflow menu and a language indicator. */
@Composable
private fun EditorToolbar(
    state: CodeUiState,
    actions: CodeActions,
    onToggleFind: () -> Unit,
    onGoToLine: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenPreview: () -> Unit = {},
    onOpenQuickOpen: () -> Unit = {},
    onOpenPalette: () -> Unit = {},
) {
    val tab = state.currentTab ?: return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { actions.undo() }, enabled = tab.canUndo) { IconUndo() }
        IconButton(onClick = { actions.redo() }, enabled = tab.canRedo) { IconRedo() }
        IconButton(onClick = { actions.save() }, enabled = tab.isDirty) { IconSave() }
        IconButton(onClick = onToggleFind) { IconFindReplace() }
        IconButton(onClick = { actions.toggleSoftWrap() }) {
            IconWrapText(
                tint = if (state.softWrap) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { actions.insertText(" ".repeat(state.tabWidth)) }) { IconFormatIndentIncrease() }
        OverflowMenu(icon = { IconMoreVert() }) {
            Item(text = stringResource(R.string.command_palette)) { onOpenPalette() }
            Item(text = stringResource(R.string.quick_open)) { onOpenQuickOpen() }
            Item(text = stringResource(R.string.go_to_line)) { onGoToLine() }
            Item(text = stringResource(R.string.toggle_comment)) { actions.toggleComment() }
            Item(text = stringResource(R.string.duplicate_line)) { actions.duplicateLine() }
            Item(text = stringResource(R.string.move_line_up)) { actions.moveLineUp() }
            Item(text = stringResource(R.string.move_line_down)) { actions.moveLineDown() }
            Item(text = stringResource(R.string.delete_line)) { actions.deleteLine() }
            Item(text = stringResource(R.string.search_in_project)) { onOpenSearch() }
            Item(text = stringResource(R.string.source_control)) { onOpenGit() }
            Item(text = stringResource(R.string.terminal)) { onOpenTerminal() }
            Item(text = stringResource(R.string.preview)) { onOpenPreview() }
            Item(text = stringResource(R.string.format_document)) { actions.formatDocument() }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = tab.language.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 12.dp),
        )
        Text(
            text = "${tab.charsetName} \u00B7 ${tab.lineEndingName}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** A banner shown when the open file changed on disk under unsaved edits: reload or keep. */
@Composable
private fun DiskChangedBanner(onReload: () -> Unit, onKeep: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.disk_changed),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReload) { Text(stringResource(R.string.reload)) }
        TextButton(onClick = onKeep) { Text(stringResource(R.string.keep_mine)) }
    }
}

/** A small dialog that reads a line number and jumps the caret to it. */
@Composable
private fun GoToLineDialog(onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val line = value.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_line)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { new -> value = new.filter { it.isDigit() } },
                singleLine = true,
                label = { Text(stringResource(R.string.line)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { line?.let(onGo); onDismiss() },
                enabled = line != null && line > 0,
            ) { Text(stringResource(R.string.go)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The command-palette registry: named actions mapped to editor callbacks and nav destinations. */
@Composable
private fun editorCommands(
    actions: CodeActions,
    onToggleFind: () -> Unit,
    onGoToLine: () -> Unit,
    onQuickOpen: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFolder: () -> Unit,
    onOpenFile: () -> Unit,
): List<PickerItem> = listOf(
    PickerItem(stringResource(R.string.quick_open)) { onQuickOpen() },
    PickerItem(stringResource(R.string.save)) { actions.save() },
    PickerItem(stringResource(R.string.save_all)) { actions.saveAll() },
    PickerItem(stringResource(R.string.find)) { onToggleFind() },
    PickerItem(stringResource(R.string.go_to_line)) { onGoToLine() },
    PickerItem(stringResource(R.string.toggle_comment)) { actions.toggleComment() },
    PickerItem(stringResource(R.string.duplicate_line)) { actions.duplicateLine() },
    PickerItem(stringResource(R.string.move_line_up)) { actions.moveLineUp() },
    PickerItem(stringResource(R.string.move_line_down)) { actions.moveLineDown() },
    PickerItem(stringResource(R.string.delete_line)) { actions.deleteLine() },
    PickerItem(stringResource(R.string.format_document)) { actions.formatDocument() },
    PickerItem(stringResource(R.string.soft_wrap)) { actions.toggleSoftWrap() },
    PickerItem(stringResource(R.string.undo)) { actions.undo() },
    PickerItem(stringResource(R.string.redo)) { actions.redo() },
    PickerItem(stringResource(R.string.search_in_project)) { onOpenSearch() },
    PickerItem(stringResource(R.string.source_control)) { onOpenGit() },
    PickerItem(stringResource(R.string.terminal)) { onOpenTerminal() },
    PickerItem(stringResource(R.string.preview)) { onOpenPreview() },
    PickerItem(stringResource(R.string.settings)) { onOpenSettings() },
    PickerItem(stringResource(R.string.open_folder)) { onOpenFolder() },
    PickerItem(stringResource(R.string.open_file)) { onOpenFile() },
)
