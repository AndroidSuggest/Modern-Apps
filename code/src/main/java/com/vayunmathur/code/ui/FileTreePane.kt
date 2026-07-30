package com.vayunmathur.code.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.code.util.TreeNode
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
    viewModel: EditorViewModel,
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
                text = viewModel.rootName ?: "Explorer",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onOpenFile) { IconNewFile() }
            IconButton(onClick = onOpenFolder) { IconFolderOpen() }
        }
        HorizontalDivider()

        if (viewModel.treeUri == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconFolder(Modifier.size(48.dp))
                Spacer(Modifier.size(12.dp))
                Text(
                    "No folder opened",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Button(onClick = onOpenFolder) { Text("Open folder") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(viewModel.nodes, key = { it.entry.uri.toString() }) { node ->
                    TreeRow(node) {
                        val wasFile = !node.entry.isDirectory
                        viewModel.toggle(node)
                        if (wasFile) onFileOpened()
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeRow(node: TreeNode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (node.depth * 16 + 8).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.entry.isDirectory) {
            if (node.expanded) IconKeyboardArrowDown(Modifier.size(20.dp)) else IconChevronRight(Modifier.size(20.dp))
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Spacer(Modifier.width(4.dp))
        when {
            node.entry.isDirectory && node.expanded -> IconFolderOpen(Modifier.size(20.dp))
            node.entry.isDirectory -> IconFolder(Modifier.size(20.dp))
            else -> IconFile(Modifier.size(20.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = node.entry.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
