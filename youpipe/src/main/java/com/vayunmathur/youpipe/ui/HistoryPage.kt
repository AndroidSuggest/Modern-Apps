package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.util.YouPipeViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryPage(backStack: NavBackStack<Route>, youPipeViewModel: YouPipeViewModel) {
    val history by youPipeViewModel.historyVideosByRecency.collectAsState()

    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val inSelectionMode = selectedIds.isNotEmpty()
    var showClearAllDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                if (inSelectionMode) Text(stringResource(R.string.selected, selectedIds.size))
                else Text(stringResource(R.string.title_history))
            },
            navigationIcon = {
                if (inSelectionMode) {
                    IconNavigation { selectedIds = emptySet() }
                }
            },
            actions = {
                IconButton(onClick = {
                    if (inSelectionMode) {
                        youPipeViewModel.deleteHistoryVideos(selectedIds.toList())
                        selectedIds = emptySet()
                    } else {
                        showClearAllDialog = true
                    }
                }) {
                    IconDelete()
                }
            }
        )
    }, bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.History) }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            items(history, key = { it.id }) { historyItem ->
                val isSelected = historyItem.id in selectedIds
                VideoItem(
                    backStack, youPipeViewModel, historyItem.videoItem, true,
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            if (inSelectionMode) {
                                selectedIds = if (isSelected) selectedIds - historyItem.id
                                else selectedIds + historyItem.id
                            } else {
                                backStack.add(Route.VideoPage(historyItem.videoItem.videoID))
                            }
                        },
                        onLongClick = {
                            selectedIds = selectedIds + historyItem.id
                        }
                    ),
                    backupOnClick = false,
                    trailingContent = if (inSelectionMode) {
                        {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    selectedIds = if (isSelected) selectedIds - historyItem.id
                                    else selectedIds + historyItem.id
                                }
                            )
                        }
                    } else null,
                )
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(stringResource(R.string.clear_history)) },
            text = { Text(stringResource(R.string.are_you_sure_you_want_to_clear_all_watch)) },
            confirmButton = {
                TextButton(onClick = {
                    youPipeViewModel.clearHistory()
                    showClearAllDialog = false
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
