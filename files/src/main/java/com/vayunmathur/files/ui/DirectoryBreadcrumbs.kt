package com.vayunmathur.files.ui

import android.content.ClipDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.files.R
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesUiState
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField

/** Title slot for [DirectoryScreen]: search field, category title, or breadcrumb trail. */
@Composable
internal fun DirectoryTitle(
    state: FilesUiState,
    actions: FilesActions,
    isCategory: Boolean,
    isReadOnly: Boolean,
) {
    val root = state.rootDirectory
    val breadcrumbs = remember(
        state.currentDirectory, state.zipPath, state.zipInternalPath, state.rootDisplayName
    ) {
        val crumbs = mutableListOf<Crumb>()
        val zp = state.zipPath
        if (zp == null) {
            fileAncestors(state.currentDirectory, root).forEach { f ->
                crumbs.add(Crumb(displayName = if (f.absolutePath == root.absolutePath) state.rootDisplayName else f.name, realFile = f, zipInternalPath = null))
            }
        } else {
            val parent = zp.parentFile ?: root
            fileAncestors(parent, root).forEach { f ->
                crumbs.add(Crumb(displayName = if (f.absolutePath == root.absolutePath) state.rootDisplayName else f.name, realFile = f, zipInternalPath = null))
            }
            crumbs.add(Crumb(displayName = zp.name, realFile = null, zipInternalPath = ""))
            var accum = ""
            for (seg in state.zipInternalPath.split("/").filter { it.isNotEmpty() }) {
                accum = if (accum.isEmpty()) seg else "$accum/$seg"
                crumbs.add(Crumb(displayName = seg, realFile = null, zipInternalPath = accum))
            }
        }
        crumbs
    }

    if (state.isSearchActive) {
        val searchFocus = remember { FocusRequester() }
        TextField(
            value = state.searchQuery,
            onValueChange = { actions.setSearchQuery(it) },
            placeholder = { Text(stringResource(R.string.search_files)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus),
        )
        LaunchedEffect(Unit) { searchFocus.requestFocus() }
    } else if (isCategory) {
        Text(state.categoryTitle.orEmpty(), style = MaterialTheme.typography.titleLarge)
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            breadcrumbs.forEachIndexed { index, crumb ->
                var isBreadcrumbDraggingOver by remember(crumb) {
                    mutableStateOf(false)
                }

                val canDrop = !isReadOnly && crumb.realFile != null && crumb.zipInternalPath == null

                Box(
                    modifier = Modifier
                        .background(
                            if (isBreadcrumbDraggingOver) MaterialTheme.colorScheme.primaryContainer.copy(
                                alpha = 0.5f
                            )
                            else Color.Transparent, shape = MaterialTheme.shapes.small
                        )
                        .then(
                            if (canDrop) {
                                Modifier.dragAndDropTarget(
                                    shouldStartDragAndDrop = { event ->
                                        event.mimeTypes().contains(
                                            ClipDescription.MIMETYPE_TEXT_PLAIN
                                        )
                                    },
                                    target = remember(crumb.realFile.absolutePath) {
                                        dropTarget(
                                            onDragStateChange = { isBreadcrumbDraggingOver = it },
                                            onDrop = { sources -> actions.moveToBreadcrumb(sources, crumb.realFile) }
                                        )
                                    })
                            } else Modifier
                        )
                        .clickable {
                            val rf = crumb.realFile
                            if (rf != null) {
                                if (state.zipPath != null) actions.navigateToZipParentRealFolder(rf)
                                else actions.navigateTo(rf)
                            } else {
                                actions.navigateToZipInternalPath(crumb.zipInternalPath ?: "")
                            }
                        }
                        .padding(4.dp)) {
                    Text(
                        text = crumb.displayName, style = MaterialTheme.typography.titleLarge
                    )
                }
                if (index < breadcrumbs.size - 1) {
                    IconChevronRight(tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
