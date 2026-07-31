package com.vayunmathur.code.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.code.R
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCode
import com.vayunmathur.library.ui.IconFindReplace
import com.vayunmathur.library.ui.IconFormatIndentIncrease
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.IconWrapText
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.rememberDrawerState
import kotlinx.coroutines.launch

/**
 * Top-level editor scaffold: a navigation drawer holding the [FileTreePane], a top bar to
 * open it, then a tab strip, toolbar, optional find bar and the [CodeEditor] itself. The
 * folder/file pickers use the Storage Access Framework so no storage permissions are needed.
 */
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::openFolder) }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::openExternal) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                FileTreePane(
                    viewModel = viewModel,
                    onOpenFolder = { folderLauncher.launch(null) },
                    onOpenFile = { fileLauncher.launch(arrayOf("*/*")) },
                    onFileOpened = { scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(viewModel.currentTab?.name ?: "Code") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { IconMenu() }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                val tab = viewModel.currentTab
                if (tab == null) {
                    EmptyEditorState(
                        onOpenFolder = { folderLauncher.launch(null) },
                        onOpenFile = { fileLauncher.launch(arrayOf("*/*")) },
                    )
                } else {
                    TabStrip(viewModel)
                    HorizontalDivider()
                    var showFind by remember { mutableStateOf(false) }
                    EditorToolbar(viewModel = viewModel, onToggleFind = { showFind = !showFind })
                    HorizontalDivider()
                    CodeEditor(
                        viewModel = viewModel,
                        tab = tab,
                        softWrap = viewModel.softWrap,
                        showFind = showFind,
                        onCloseFind = { showFind = false },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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
private fun TabStrip(viewModel: EditorViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        viewModel.tabs.forEachIndexed { index, tab ->
            val selected = index == viewModel.currentIndex
            val background =
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            Row(
                modifier = Modifier
                    .background(background)
                    .clickable { viewModel.selectTab(index) }
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
                IconButton(onClick = { viewModel.closeTab(tab) }, modifier = Modifier.size(32.dp)) {
                    IconClose(Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Undo/redo, save, find, soft-wrap, tab-insert and a language indicator. */
@Composable
private fun EditorToolbar(viewModel: EditorViewModel, onToggleFind: () -> Unit) {
    val tab = viewModel.currentTab ?: return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { viewModel.undo() }, enabled = tab.canUndo) { IconUndo() }
        IconButton(onClick = { viewModel.redo() }, enabled = tab.canRedo) { IconRedo() }
        IconButton(onClick = { viewModel.save() }, enabled = tab.isDirty) { IconSave() }
        IconButton(onClick = onToggleFind) { IconFindReplace() }
        IconButton(onClick = { viewModel.toggleSoftWrap() }) {
            IconWrapText(
                tint = if (viewModel.softWrap) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { viewModel.insertText("    ") }) { IconFormatIndentIncrease() }
        Spacer(Modifier.width(8.dp))
        Text(
            text = tab.language.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
