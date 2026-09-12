package com.vayunmathur.games.logicgate.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.math.abs
import kotlin.math.min

internal fun wireStyleForWidth(busWidth: Int, isCompact: Boolean = false): Pair<Color, Float> =
    when {
        busWidth >= 8 -> Turing.wireBlue to 5.2f
        busWidth >= 2 -> Turing.wireOrange to 3.8f
        else -> Turing.wireThin to if (isCompact) 3.2f else 2.6f
    }

internal fun DrawScope.drawOrthWire(from: Offset, to: Offset, color: Color, thickPx: Float, isGhost: Boolean, dash: Boolean) {
    val dx = to.x - from.x; val stub = 18f
    val path = Path().apply {
        moveTo(from.x, from.y); lineTo(from.x + stub, from.y)
        if (abs(to.y - from.y) > 6f) {
            val ix = if (abs(dx) < 50f) (from.x + to.x) / 2f else from.x + 32f + (dx * 0.15f).coerceIn(0f, 80f)
            lineTo(ix, from.y); lineTo(ix, to.y); lineTo(to.x - 6f, to.y)
        } else lineTo(to.x - 6f, to.y)
        lineTo(to.x, to.y)
    }
    if (!isGhost) drawPath(path, color.copy(alpha = 0.18f), style = Stroke(width = thickPx + 4.5f))
    if (dash) drawPath(path, if (color == Turing.ghostBad) color else Turing.wireYellow.copy(alpha = 0.9f), style = Stroke(width = thickPx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)))
    else drawPath(path, color, style = Stroke(width = thickPx))
    drawCircle(color, thickPx * 0.55f + 1.2f, to); drawCircle(Color.White.copy(alpha = 0.9f), 1.8f, to)
    if (abs(to.y - from.y) > 18f) {
        val ix = if (abs(dx) < 50f) (from.x + to.x) / 2f else from.x + 32f + (dx * 0.15f).coerceIn(0f, 80f)
        drawCircle(color, 3.2f, Offset(ix, from.y)); drawCircle(color, 3.2f, Offset(ix, to.y))
        drawCircle(Color.White.copy(alpha = 0.7f), 1f, Offset(ix, from.y)); drawCircle(Color.White.copy(alpha = 0.7f), 1f, Offset(ix, to.y))
    }
}

private fun distPointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ap = p - a; val ab = b - a; val ab2 = ab.x * ab.x + ab.y * ab.y
    if (ab2 == 0f) return (p - a).getDistance()
    var t = (ap.x * ab.x + ap.y * ab.y) / ab2; t = t.coerceIn(0f, 1f)
    val proj = Offset(a.x + ab.x * t, a.y + ab.y * t)
    return (p - proj).getDistance()
}

internal fun distPointToOrth(p: Offset, from: Offset, to: Offset): Float {
    val dx = to.x - from.x; val ix = if (abs(dx) < 50f) (from.x + to.x) / 2f else from.x + 32f + (dx * 0.15f).coerceIn(0f, 80f)
    val p1 = from; val p2 = Offset(from.x + 18f, from.y); val p3 = Offset(ix, from.y); val p4 = Offset(ix, to.y); val p5 = Offset(to.x, to.y)
    var best = distPointToSegment(p, p1, p2)
    best = min(best, distPointToSegment(p, p2, p3)); best = min(best, distPointToSegment(p, p3, p4)); best = min(best, distPointToSegment(p, p4, p5))
    return best
}

internal suspend fun AwaitPointerEventScope.awaitFirstDown(requireUnconsumed: Boolean = true): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        if (event.type == PointerEventType.Press) {
            val down = event.changes.firstOrNull { if (requireUnconsumed) !it.isConsumed else true } ?: continue; return down
        }
    }
}
