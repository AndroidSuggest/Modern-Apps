package com.vayunmathur.code.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.code.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.util.CodeActions
import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.code.util.TreeRowUiState
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconFile
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.IconFolderOpen
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconNewFile
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * The drawer's file browser: a header with folder actions and a lazily-built, flat file tree.
 * Tapping a directory expands/collapses it; tapping a file opens it (and the caller closes
 * the drawer). Shows an "open folder" empty state until a folder has been chosen.
 */
@Composable
fun FileTreePane(
    state: CodeUiState,
    actions: CodeActions,
    onOpenFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onFileOpened: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.rootName ?: "Explorer",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onOpenFile) { IconNewFile() }
            IconButton(onClick = onOpenFolder) { IconFolderOpen() }
        }
        HorizontalDivider()

        if (!state.folderOpen) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconFolder(Modifier.size(48.dp))
                Spacer(Modifier.size(12.dp))
                Text(
                    stringResource(R.string.no_folder_opened),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Button(onClick = onOpenFolder) { Text(stringResource(R.string.open_folder)) }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                // Positional keys: the flat tree can hold two files with the same name at the
                // same depth, and the rows themselves carry no state worth preserving.
                itemsIndexed(state.nodes) { index, node ->
                    TreeRow(node) {
                        val wasFile = !node.isDirectory
                        actions.toggleNode(index)
                        if (wasFile) onFileOpened()
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeRow(node: TreeRowUiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (node.depth * 16 + 8).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.isDirectory) {
            if (node.expanded) IconKeyboardArrowDown(Modifier.size(20.dp)) else IconChevronRight(Modifier.size(20.dp))
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Spacer(Modifier.width(4.dp))
        when {
            node.isDirectory && node.expanded -> IconFolderOpen(Modifier.size(20.dp))
            node.isDirectory -> IconFolder(Modifier.size(20.dp))
            else -> IconFile(Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = node.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
