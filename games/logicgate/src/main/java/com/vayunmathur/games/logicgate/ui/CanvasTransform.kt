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
  val half = pillW / 2f + padding
  val maxX = (canvasSize.width - half).coerceAtLeast(half)
  val minX = half
  val minY = padding + 22f
  val maxY = (canvasSize.height - padding - 22f).coerceAtLeast(minY)
  return Offset(center.x.coerceIn(minX, maxX), center.y.coerceIn(minY, maxY))
}

/**
 * Responsive default positions for I/O terminals — fixes portrait off-screen bug.
 * Inputs left side, outputs right edge relative to current canvasSize.
 */
fun defaultInputPos(idx: Int, canvasSize: Size, pillW: Float = 86f): Offset {
  val padding = 20f
  val spacing = if (canvasSize.height > 0f) {
    val available = canvasSize.height - padding * 2f - 44f
    val per = available / 8f
    per.coerceIn(42f, 62f)
  } else 56f
  return Offset(padding + pillW / 2f, padding + 44f + idx * spacing)
}

fun defaultOutputPos(idx: Int, canvasSize: Size, pillW: Float = 86f): Offset {
  val padding = 20f
  val spacing = if (canvasSize.height > 0f) {
    val available = canvasSize.height - padding * 2f - 44f
    val per = available / 8f
    per.coerceIn(42f, 62f)
  } else 56f
  val x = if (canvasSize.width > 0f) canvasSize.width - padding - pillW / 2f else 320f
  return Offset(x, padding + 44f + idx * spacing)
}
