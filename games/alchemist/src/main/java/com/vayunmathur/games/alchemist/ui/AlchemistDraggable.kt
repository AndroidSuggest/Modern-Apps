package com.vayunmathur.games.alchemist.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.alchemist.platform.PlacedItem
import com.vayunmathur.games.alchemist.ui.components.DynamicAlchemyIcon
import kotlin.math.roundToInt

@Composable
fun DraggableElement(
    item: PlacedItem,
    onDragStart: () -> Unit,
    onDragEnd: (Offset) -> Unit,
    onLongClick: () -> Unit,
    onDoubleTap: () -> Unit
) {
    var currentOffset by remember(item.key) { mutableStateOf(item.offset) }

    Box(
        Modifier
            .offset {
                IntOffset(currentOffset.x.roundToInt(), currentOffset.y.roundToInt())
            }
            .size(72.dp)
            .combinedClickable(onLongClick = onLongClick, onClick = {})
            .pointerInput(item.key) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
            .pointerInput(item.key) {
                detectDragGestures(
                    onDragStart = { _ -> onDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOffset += dragAmount
                    },
                    onDragEnd = { onDragEnd(currentOffset) },
                    onDragCancel = { onDragEnd(currentOffset) }
                )
            }) { DynamicAlchemyIcon(item.id) }
}
