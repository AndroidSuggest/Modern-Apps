package com.vayunmathur.photos.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.photos.data.VideoEditState

@Composable
internal fun CropOverlay(
    state: VideoEditState,
    onCrop: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var rect by remember {
        mutableStateOf(
            Rect(
                state.cropLeft ?: 0f, state.cropTop ?: 0f,
                state.cropRight ?: 1f, state.cropBottom ?: 1f,
            )
        )
    }
    var active by remember { mutableIntStateOf(-1) }
    val minSize = 0.15f

    Box(
        modifier = modifier
            .onGloballyPositioned { boxSize = it.size }
            .pointerInput(boxSize) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = boxSize.width.toFloat().coerceAtLeast(1f)
                        val h = boxSize.height.toFloat().coerceAtLeast(1f)
                        val corners = listOf(
                            Offset(rect.left * w, rect.top * h),
                            Offset(rect.right * w, rect.top * h),
                            Offset(rect.left * w, rect.bottom * h),
                            Offset(rect.right * w, rect.bottom * h),
                        )
                        active = corners.indexOfFirst { (it - pos).getDistance() < 100f }
                    },
                    onDrag = { change, drag ->
                        if (active < 0) return@detectDragGestures
                        change.consume()
                        val w = boxSize.width.toFloat().coerceAtLeast(1f)
                        val h = boxSize.height.toFloat().coerceAtLeast(1f)
                        val dx = drag.x / w
                        val dy = drag.y / h
                        rect = when (active) {
                            0 -> Rect(
                                (rect.left + dx).coerceIn(0f, rect.right - minSize),
                                (rect.top + dy).coerceIn(0f, rect.bottom - minSize),
                                rect.right, rect.bottom,
                            )
                            1 -> Rect(
                                rect.left,
                                (rect.top + dy).coerceIn(0f, rect.bottom - minSize),
                                (rect.right + dx).coerceIn(rect.left + minSize, 1f),
                                rect.bottom,
                            )
                            2 -> Rect(
                                (rect.left + dx).coerceIn(0f, rect.right - minSize),
                                rect.top,
                                rect.right,
                                (rect.bottom + dy).coerceIn(rect.top + minSize, 1f),
                            )
                            else -> Rect(
                                rect.left,
                                rect.top,
                                (rect.right + dx).coerceIn(rect.left + minSize, 1f),
                                (rect.bottom + dy).coerceIn(rect.top + minSize, 1f),
                            )
                        }
                    },
                    onDragEnd = { active = -1; onCrop(rect) },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val l = rect.left * w
            val t = rect.top * h
            val r = rect.right * w
            val b = rect.bottom * h
            drawRect(
                color = Color.White,
                topLeft = Offset(l, t),
                size = androidx.compose.ui.geometry.Size(r - l, b - t),
                style = Stroke(width = 3.dp.toPx()),
            )
            listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach {
                drawCircle(Color.White, radius = 8.dp.toPx(), center = it)
            }
        }
    }
}
