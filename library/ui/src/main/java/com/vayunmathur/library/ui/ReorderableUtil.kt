package com.vayunmathur.library.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Foundation-based replacement for sh.calvin.reorderable 3.1.0.
 * Implements minimal API surface used in this repo:
 * - rememberReorderableLazyListState / rememberReorderableLazyGridState
 * - ReorderableItem (list + grid)
 * - Modifier.draggableHandle / longPressDraggableHandle
 *
 * Drag handling is iterative: each drag gesture chunk that exceeds a small
 * threshold swaps the dragged item with its neighbour in the drag direction.
 * This preserves the previous position-interpolation logic in NotesListPage etc
 * without requiring exact pixel-to-index mapping.
 */

data class ReorderableItemInfo(val index: Int, val key: Any = Unit)

@Stable
class ReorderableLazyListState(
    val listState: LazyListState,
    private val onMoveInternal: (from: Int, to: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        internal set
    var isAnyItemDragging by mutableStateOf(false)
        internal set
    var draggingIndex by mutableIntStateOf(-1)
        internal set

    fun startDrag(key: Any, index: Int) {
        draggingKey = key
        draggingIndex = index
        isAnyItemDragging = true
    }

    fun stopDrag() {
        draggingKey = null
        draggingIndex = -1
        isAnyItemDragging = false
    }

    fun move(toIndex: Int) {
        val from = draggingIndex
        if (from == -1 || toIndex == from) return
        if (toIndex < 0) return
        onMoveInternal(from, toIndex)
        draggingIndex = toIndex
    }
}

@Stable
class ReorderableLazyGridState(
    val gridState: LazyGridState,
    private val onMoveInternal: (from: Int, to: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        internal set
    var isAnyItemDragging by mutableStateOf(false)
        internal set
    var draggingIndex by mutableIntStateOf(-1)
        internal set

    fun startDrag(key: Any, index: Int) {
        draggingKey = key
        draggingIndex = index
        isAnyItemDragging = true
    }

    fun stopDrag() {
        draggingKey = null
        draggingIndex = -1
        isAnyItemDragging = false
    }

    fun move(toIndex: Int) {
        val from = draggingIndex
        if (from == -1 || toIndex == from) return
        if (toIndex < 0) return
        onMoveInternal(from, toIndex)
        draggingIndex = toIndex
    }
}

@Composable
fun rememberReorderableLazyListState(
    lazyListState: LazyListState,
    onMove: (from: ReorderableItemInfo, to: ReorderableItemInfo) -> Unit,
): ReorderableLazyListState {
    return remember(lazyListState) {
        ReorderableLazyListState(lazyListState) { from, to ->
            onMove(ReorderableItemInfo(from), ReorderableItemInfo(to))
        }
    }
}

@Composable
fun rememberReorderableLazyGridState(
    gridState: LazyGridState,
    onMove: (from: ReorderableItemInfo, to: ReorderableItemInfo) -> Unit,
): ReorderableLazyGridState {
    return remember(gridState) {
        ReorderableLazyGridState(gridState) { from, to ->
            onMove(ReorderableItemInfo(from), ReorderableItemInfo(to))
        }
    }
}

@Composable
fun ReorderableItem(
    reorderState: ReorderableLazyListState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    val isDragging = reorderState.draggingKey == key
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        content(isDragging)
    }
}

@Composable
fun ReorderableItem(
    reorderState: ReorderableLazyGridState,
    key: Any,
    modifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean) -> Unit,
) {
    val isDragging = reorderState.draggingKey == key
    androidx.compose.foundation.layout.Box(modifier = modifier) {
        content(isDragging)
    }
}

private const val DRAG_THRESHOLD = 18f

fun Modifier.draggableHandle(
    reorderState: ReorderableLazyListState,
    key: Any,
    index: Int,
    onDragStarted: suspend CoroutineScope.(medium: Any) -> Unit = {},
    onDragStopped: suspend CoroutineScope.() -> Unit = {},
): Modifier = composed {
    val scope = rememberCoroutineScope()
    pointerInput(key, index) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                reorderState.startDrag(key, index)
                scope.launch { onDragStarted(Unit) }
            },
            onDragEnd = {
                reorderState.stopDrag()
                scope.launch { onDragStopped() }
            },
            onDragCancel = {
                reorderState.stopDrag()
                scope.launch { onDragStopped() }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (reorderState.draggingIndex == -1) return@detectDragGesturesAfterLongPress
                val dy = dragAmount.y
                if (dy > DRAG_THRESHOLD) {
                    reorderState.move(reorderState.draggingIndex + 1)
                } else if (dy < -DRAG_THRESHOLD) {
                    reorderState.move(reorderState.draggingIndex - 1)
                }
            }
        )
    }
}

fun Modifier.draggableHandle(
    reorderState: ReorderableLazyGridState,
    key: Any,
    index: Int,
    onDragStarted: suspend CoroutineScope.(medium: Any) -> Unit = {},
    onDragStopped: suspend CoroutineScope.() -> Unit = {},
): Modifier = composed {
    val scope = rememberCoroutineScope()
    pointerInput(key, index) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                reorderState.startDrag(key, index)
                scope.launch { onDragStarted(Unit) }
            },
            onDragEnd = {
                reorderState.stopDrag()
                scope.launch { onDragStopped() }
            },
            onDragCancel = {
                reorderState.stopDrag()
                scope.launch { onDragStopped() }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (reorderState.draggingIndex == -1) return@detectDragGesturesAfterLongPress
                val absX = kotlin.math.abs(dragAmount.x)
                val absY = kotlin.math.abs(dragAmount.y)
                if (absY > absX) {
                    if (dragAmount.y > DRAG_THRESHOLD) {
                        reorderState.move(reorderState.draggingIndex + 1)
                    } else if (dragAmount.y < -DRAG_THRESHOLD) {
                        reorderState.move(reorderState.draggingIndex - 1)
                    }
                } else {
                    if (dragAmount.x > DRAG_THRESHOLD) {
                        reorderState.move(reorderState.draggingIndex + 1)
                    } else if (dragAmount.x < -DRAG_THRESHOLD) {
                        reorderState.move(reorderState.draggingIndex - 1)
                    }
                }
            }
        )
    }
}

fun Modifier.longPressDraggableHandle(
    reorderState: ReorderableLazyListState,
    key: Any,
    index: Int,
): Modifier = draggableHandle(reorderState, key, index)

fun Modifier.longPressDraggableHandle(
    reorderState: ReorderableLazyGridState,
    key: Any,
    index: Int,
): Modifier = draggableHandle(reorderState, key, index)

// No-arg stubs for files that still import calvin but we migrate incrementally
@Deprecated("Use version with state param")
fun Modifier.draggableHandle(): Modifier = this

@Deprecated("Use version with state param")
fun Modifier.longPressDraggableHandle(): Modifier = this
