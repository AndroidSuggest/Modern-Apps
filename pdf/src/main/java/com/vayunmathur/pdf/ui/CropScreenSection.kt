package com.vayunmathur.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Composable
fun CropScreenSection(offset: Offset, onDragStart: () -> Unit = {}, onDragEnd: () -> Unit = {}, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSize = 24.dp
    val handleRadiusPx = with(density) { (handleSize / 2).toPx() }
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    Box(modifier = Modifier
        .offset { IntOffset((offset.x - handleRadiusPx).roundToInt(), (offset.y - handleRadiusPx).roundToInt()) }
        .size(handleSize)
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { currentOnDragStart() },
                onDragEnd = { currentOnDragEnd() },
                onDragCancel = { currentOnDragEnd() },
                onDrag = { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount)
                }
            )
        }
        .background(Color.White, androidx.compose.foundation.shape.CircleShape)
    )
}
