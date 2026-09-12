package com.vayunmathur.code.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/** A narrow overview: one dim bar per source line (width ∝ length) plus a draggable viewport rect. */
@Composable
internal fun Minimap(
    lines: List<String>,
    lineHeight: Float,
    scrollY: Float,
    maxScrollY: Float,
    viewportHeight: Float,
    totalHeight: Float,
    color: Color,
    viewportColor: Color,
    onScrollTo: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier
            .background(color.copy(alpha = 0.05f))
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    onScrollTo((change.position.y / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos -> onScrollTo((pos.y / size.height).coerceIn(0f, 1f)) }
            },
    ) {
        if (lines.isEmpty()) return@Canvas
        val rowH = (size.height / lines.size).coerceAtMost(3f)
        val maxLen = (lines.maxOfOrNull { it.length } ?: 1).coerceAtLeast(1)
        for (i in lines.indices) {
            val len = lines[i].length
            if (len == 0) continue
            val w = (len.toFloat() / maxLen) * (size.width - 4f)
            drawRect(color, topLeft = Offset(2f, i * rowH), size = Size(w, (rowH - 0.5f).coerceAtLeast(0.5f)))
        }
        // Viewport rectangle.
        if (totalHeight > 0f && viewportHeight > 0f) {
            val top = (scrollY / totalHeight) * size.height
            val h = (viewportHeight / totalHeight) * size.height
            drawRect(viewportColor, topLeft = Offset(0f, top), size = Size(size.width, h))
        }
    }
}
