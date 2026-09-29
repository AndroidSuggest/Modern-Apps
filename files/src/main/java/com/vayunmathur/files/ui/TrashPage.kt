package com.vayunmathur.files.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vayunmathur.files.platform.FilesActions
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.files.platform.TrashUiState
import com.vayunmathur.library.ui.SnackbarHostState

/**
 * Binds [FilesViewModel] to the stateless [TrashScreen].
 *
 * Same shape as [DirectoryPage]: collects flows and freezes the outgoing screen with the
 * `lastShown` pattern while it animates away. The system trash-consent launcher and the
 * permanent-delete dialog live in [FilesShell], above the nav host, so there is exactly one
 * collector no matter which screen initiated the trash operation.
 */
@Composable
fun TrashPage(
    viewModel: FilesViewModel,
    actions: FilesActions,
    isCurrent: Boolean,
    onOpenDrawer: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val trashedItems by viewModel.trashedItems.collectAsState()
    val trashSelection by viewModel.trashSelection.collectAsState()

    LaunchedEffect(snackbarHostState) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val liveState = TrashUiState(
        items = trashedItems,
        selectedItems = trashSelection,
    )
    var lastShown by remember { mutableStateOf(liveState) }
    if (isCurrent) SideEffect { lastShown = liveState }

    TrashScreen(
        state = if (isCurrent) liveState else lastShown,
        actions = actions,
        snackbarHostState = snackbarHostState,
        onOpenDrawer = onOpenDrawer,
    )
}
