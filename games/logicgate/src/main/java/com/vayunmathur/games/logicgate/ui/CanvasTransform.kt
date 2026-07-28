package com.vayunmathur.games.logicgate.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Viewport + clamping — density-aware refactor from plan Phase 2+4.
 * Fixes bug: hardcoded 12f px in clampGate, hardcoded 920f output off-screen portrait.
 */
data class CanvasViewport(val offset: Offset = Offset.Zero, val scale: Float = 1f) {
  fun worldToScreen(p: Offset): Offset = (p - offset) * scale
  fun screenToWorld(p: Offset): Offset = p / scale + offset
}

fun clampGate(
  pos: Offset,
  w: Float,
  h: Float,
  canvasSize: Size,
  padding: Dp = 12.dp,
  density: Density? = null
): Offset {
  if (canvasSize.width <= 0f || canvasSize.height <= 0f) return Offset(pos.x.coerceAtLeast(0f), pos.y.coerceAtLeast(0f))
  val padPx = density?.let { with(it) { padding.toPx() } } ?: 12f
  val minX = padPx
  val minY = padPx
  val maxX = (canvasSize.width - w - padPx).coerceAtLeast(minX)
  val maxY = (canvasSize.height - h - padPx).coerceAtLeast(minY)
  return Offset(pos.x.coerceIn(minX, maxX), pos.y.coerceIn(minY, maxY))
}

fun clampTerm(
  center: Offset,
  pillW: Float,
  canvasSize: Size,
  padding: Float,
  density: Density? = null
): Offset {
  if (canvasSize.width <= 0f || canvasSize.height <= 0f) return center
  // Large virtual work area (matches CircuitCanvas.CANVAS_MARGIN) so terminals can be moved off-viewport.
  val margin = 4000f
  return Offset(
    center.x.coerceIn(-margin, canvasSize.width + margin),
    center.y.coerceIn(-margin, canvasSize.height + margin)
  )
}

/**
 * Responsive default positions for I/O terminals — fixes portrait off-screen bug.
 * Inputs left side, outputs right edge relative to current canvasSize.
 */
// Center the terminal stack vertically and space rows by the block height so they never overlap.
private fun defaultStackY(idx: Int, count: Int, canvasSize: Size, itemH: Float): Float {
  val padding = 16f
  val spacing = itemH + 22f
  val totalH = spacing * count.coerceAtLeast(1)
  val startY = ((canvasSize.height - totalH) / 2f).coerceAtLeast(padding) + spacing / 2f
  return startY + idx * spacing
}

fun defaultInputPos(idx: Int, count: Int, canvasSize: Size, pillW: Float = 86f, itemH: Float = 56f): Offset {
  val padding = 20f
  return Offset(padding + pillW / 2f, defaultStackY(idx, count, canvasSize, itemH))
}

fun defaultOutputPos(idx: Int, count: Int, canvasSize: Size, pillW: Float = 86f, itemH: Float = 56f): Offset {
  val padding = 20f
  val x = if (canvasSize.width > 0f) canvasSize.width - padding - pillW / 2f else 320f
  return Offset(x, defaultStackY(idx, count, canvasSize, itemH))
}
