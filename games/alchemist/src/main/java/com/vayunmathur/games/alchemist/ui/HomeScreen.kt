package com.vayunmathur.games.alchemist.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBarOverlay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.games.alchemist.R
import com.vayunmathur.games.alchemist.Route
import com.vayunmathur.games.alchemist.platform.AlchemistViewModel
import com.vayunmathur.games.alchemist.platform.HomeActions
import com.vayunmathur.games.alchemist.platform.HomeUiState
import com.vayunmathur.games.alchemist.ui.components.DynamicAlchemyIcon
import com.vayunmathur.library.util.NavBackStack
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Binds [AlchemistViewModel] to the stateless [HomeScreen]. */
@Composable
fun HomePage(
    backStack: NavBackStack<Route>,
    viewModel: AlchemistViewModel,
    onOpenCollection: () -> Unit,
    onOpenGameCenter: () -> Unit
) {
    val availableItems by viewModel.availableItems.collectAsState()
    val paletteItems by viewModel.paletteItems.collectAsState()
    val hideExhausted by viewModel.hideExhausted.collectAsState()
    val allItems by viewModel.allItems.collectAsState()
    val activeItems by viewModel.placedElements.collectAsState()

    HomeScreen(
        state = HomeUiState(
            placedItems = activeItems,
            paletteItems = paletteItems,
            discoveredCount = availableItems.size,
            totalCount = allItems.size,
            hideExhausted = hideExhausted
        ),
        actions = viewModel,
        onOpenCollection = onOpenCollection,
        onOpenGameCenter = onOpenGameCenter,
        onOpenItemDetails = { backStack.add(Route.ItemDetails(it.toInt())) }
    )
}

/**
 * The crafting board, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    onOpenCollection: () -> Unit,
    onOpenGameCenter: () -> Unit,
    onOpenItemDetails: (Long) -> Unit
) {
    val activeItems = state.placedItems
    val paletteItems = state.paletteItems

    val scope = rememberCoroutineScope()

    var bottomBarTopInWindow by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var playAreaOffsetInWindow by remember { mutableStateOf(Offset.Zero) }
    var isDraggingBoardItem by remember { mutableStateOf(false) }

    // Tracking for the current item being "pulled out" of the bottom bar
    var draggingInventoryId by remember { mutableStateOf<Long?>(null) }
    var draggingInventoryOffset by remember { mutableStateOf(Offset.Zero) }

    var contextMenuElementId by remember { mutableStateOf<Long?>(null) }
    var contextMenuExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }

    val lazyList = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
        ) {
            // 1. PLAY AREA (Full Screen)
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(if (isDraggingBoardItem) 1f else 0f)
                    .onGloballyPositioned {
                        playAreaOffsetInWindow = it.positionInWindow()
                    }) {
                activeItems.forEach { item ->
                    key(item.key) {
                        DraggableElement(item = item, onDragStart = {
                            isDraggingBoardItem = true
                        }, onDragEnd = { finalOffset ->
                            isDraggingBoardItem = false
                            val limitY = bottomBarTopInWindow - playAreaOffsetInWindow.y - 48f
                            // DELETION: Triggered if any part of the item touches the bottom bar
                            if (finalOffset.y > limitY) {
                                actions.removeElement(item.key)
                            } else {
                                actions.updateElementPosition(item.key, finalOffset)
                                actions.tryCombine(item.key, finalOffset)
                            }
                        }, onLongClick = {
                            contextMenuElementId = item.id
                            contextMenuExpanded = true
                        }, onDoubleTap = {
                            actions.duplicateElement(item.key)
                        })
                    }
                }
            }

            // 2. BOTTOM PANEL OVERLAY
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
                    .height(192.dp)
                    .fillMaxWidth(),
            ) {
                AlchemistInventoryPanel(
                    paletteItems = paletteItems,
                    discoveredCount = state.discoveredCount,
                    totalCount = state.totalCount,
                    isDraggingBoardItem = isDraggingBoardItem,
                    lazyList = lazyList,
                    onBarTopPositioned = { bottomBarTopInWindow = it },
                    onLetterJump = { index -> scope.launch { lazyList.animateScrollToItem(index) } },
                    onPlaceElement = { id, offset -> actions.placeElement(id, offset) },
                    onInventoryDragState = { id, offset ->
                        draggingInventoryId = id
                        draggingInventoryOffset = offset
                    },
                    playAreaOffsetInWindow = playAreaOffsetInWindow,
                    bottomBarTopInWindow = bottomBarTopInWindow,
                    onContextMenu = { id ->
                        contextMenuElementId = id
                        contextMenuExpanded = true
                    },
                )
            }

            // 3. GLOBAL DRAG OVERLAY (item being pulled out of the bottom bar)
            draggingInventoryId?.let { id ->
                Box(Modifier
                    .offset {
                        IntOffset(
                            draggingInventoryOffset.x.roundToInt(),
                            draggingInventoryOffset.y.roundToInt()
                        )
                    }
                    .size(72.dp)
                ) { DynamicAlchemyIcon(id) }
            }
        }

        TopAppBarOverlay(
            modifier = Modifier.align(Alignment.TopCenter),
            actions = buildList {
                if (activeItems.isNotEmpty()) {
                    add(
                        OverlayAction(
                            icon = { Icon(painterResource(id = android.R.drawable.ic_menu_close_clear_cancel), "Clear") },
                            contentDescription = "Clear",
                            onClick = { actions.clearElements() },
                        )
                    )
                }
                add(
                    OverlayAction(
                        icon = { Icon(painterResource(id = android.R.drawable.ic_menu_sort_by_size), "Collection") },
                        contentDescription = "Collection",
                        onClick = onOpenCollection,
                    )
                )
                add(
                    OverlayAction(
                        icon = { Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements") },
                        contentDescription = "Achievements",
                        onClick = onOpenGameCenter,
                    )
                )
                add(
                    OverlayAction(
                        icon = { IconMoreVert() },
                        contentDescription = "More",
                        onClick = { overflowExpanded = true },
                    )
                )
            }
        )

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
        ) {
            DropdownMenu(
                expanded = overflowExpanded,
                onDismissRequest = { overflowExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.hide_maxed_elements)) },
                    trailingIcon = { if (state.hideExhausted) IconCheck() },
                    onClick = {
                        actions.setHideExhausted(!state.hideExhausted)
                        overflowExpanded = false
                    }
                )
            }
        }

        if (contextMenuExpanded) {
            DropdownMenu(
                expanded = contextMenuExpanded,
                onDismissRequest = { contextMenuExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.see_details)) }, onClick = {
                    contextMenuExpanded = false
                    contextMenuElementId?.let(onOpenItemDetails)
                })
            }
        }
    }
}
