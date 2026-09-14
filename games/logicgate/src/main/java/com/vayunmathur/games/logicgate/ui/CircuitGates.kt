package com.vayunmathur.games.logicgate.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.data.ChipDef
import com.vayunmathur.games.logicgate.data.PlacedChip
import com.vayunmathur.games.logicgate.data.WireEnd
import kotlin.math.max

// Logic-gate silhouettes (Turing-Complete style): triangle=NOT/buffer, D=AND/NAND,
// bullet=OR/NOR, bullet+double-back=XOR/XNOR, rounded rect=everything else.
// Inverting gates (NAND/NOR/XNOR/NOT) also get the negation bubble at the output.
internal enum class GateShape { TRIANGLE, DSHAPE, ORSHAPE, RECT }
internal data class GateStyle(val shape: GateShape, val inverting: Boolean, val doubleBack: Boolean)

internal fun gateStyleFor(def: ChipDef): GateStyle {
    // All components render as compact rounded rectangles.
    return GateStyle(GateShape.RECT, inverting = false, doubleBack = false)
}

// Size a placed component (px): width fits the label, height grows per pin for spacing. Shared by the canvas and the drag ghost.
internal fun gatePlacedSizePx(def: ChipDef, density: Density, textMeasurer: TextMeasurer): Pair<Float, Float> {
    val maxPins = max(def.inputCount, def.outputCount)
    val measured = try {
        textMeasurer.measure(def.displayName, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold)).size.width.toFloat()
    } catch (_: Exception) { def.displayName.length * with(density) { 8.dp.toPx() } }
    val wPx = (measured + with(density) { 16.dp.toPx() }).coerceIn(with(density) { 44.dp.toPx() }, with(density) { 104.dp.toPx() })
    val hDp = if (maxPins <= 1) 30.dp else (20.dp * maxPins + 12.dp)
    return wPx to with(density) { hDp.toPx() }
}

// Builds a gate body path occupying [left, left+w] x [0,h].
private fun buildGateBody(shape: GateShape, left: Float, w: Float, h: Float): Path {
    val p = Path()
    when (shape) {
        GateShape.DSHAPE -> {
            val r = (h / 2f).coerceAtMost(w / 2f)
            p.moveTo(left, 0f); p.lineTo(left + w - r, 0f)
            p.arcTo(Rect(left + w - 2f * r, 0f, left + w, h), -90f, 180f, false)
            p.lineTo(left, h); p.close()
        }
        GateShape.ORSHAPE -> {
            p.moveTo(left, 0f)
            p.quadraticTo(left + w * 0.30f, h * 0.5f, left, h)   // concave back
            p.quadraticTo(left + w * 0.72f, h, left + w, h * 0.5f) // bottom to tip
            p.quadraticTo(left + w * 0.72f, 0f, left, 0f)          // tip to top
            p.close()
        }
        GateShape.TRIANGLE -> {
            p.moveTo(left, 0f); p.lineTo(left + w, h / 2f); p.lineTo(left, h); p.close()
        }
        GateShape.RECT -> {}
    }
    return p
}

internal fun DrawScope.drawGate(style: GateStyle, fill: Color, stroke: Color, strokeW: Float) {
    val w = size.width; val h = size.height
    if (style.shape == GateShape.RECT) {
        val cr = androidx.compose.ui.geometry.CornerRadius(with(this) { 8.dp.toPx() }, with(this) { 8.dp.toPx() })
        drawRoundRect(fill, size = size, cornerRadius = cr)
        drawRoundRect(stroke, size = size, cornerRadius = cr, style = Stroke(strokeW))
        return
    }
    val bubbleR = if (style.inverting) h * 0.13f else 0f
    val backGap = if (style.doubleBack) h * 0.16f else 0f
    val left = backGap
    val bodyW = (w - bubbleR * 2f - backGap).coerceAtLeast(h * 0.6f)
    val body = buildGateBody(style.shape, left, bodyW, h)
    drawPath(body, fill)
    drawPath(body, stroke, style = Stroke(strokeW))
    if (style.doubleBack) {
        val back = Path().apply {
            moveTo(0f, 0f)
            quadraticTo(bodyW * 0.30f, h * 0.5f, 0f, h)
        }
        drawPath(back, stroke, style = Stroke(strokeW))
    }
    if (style.inverting) {
        val cx = left + bodyW + bubbleR; val cy = h / 2f
        drawCircle(fill, bubbleR, Offset(cx, cy))
        drawCircle(stroke, bubbleR, Offset(cx, cy), style = Stroke(strokeW))
    }
}

data class GateBox(val chip: PlacedChip, val left: Float, val top: Float, val w: Float, val h: Float, val pinOut: Float = 0f) {
    fun inputPos(i: Int, count: Int): Offset = Offset(left - pinOut, if (count <= 1) top + h / 2f else top + h / (count + 1) * (i + 1))
    fun outputPos(i: Int, count: Int): Offset = Offset(left + w + pinOut, if (count <= 1) top + h / 2f else top + h / (count + 1) * (i + 1))
    fun inputPosLocal(count: Int, pinIdx: Int): Offset = Offset(-pinOut, if (count <= 1) h / 2f else h / (count + 1) * (pinIdx + 1))
    fun outputPosLocal(count: Int, pinIdx: Int): Offset = Offset(w + pinOut, if (count <= 1) h / 2f else h / (count + 1) * (pinIdx + 1))
}

data class TerminalBox(val idx: Int, val center: Offset, val name: String, val isInput: Boolean, val pillW: Float)
data class HitInput(val end: WireEnd, val pos: Offset)

// Large virtual work area so gates can be dragged well beyond the viewport (pan/zoom to reach them).
private const val CANVAS_MARGIN = 4000f

internal fun clampGateWithPin(pos: Offset, w: Float, h: Float, pinOut: Float, canvasSize: Size, padding: Dp, density: Density): Offset {
    if (canvasSize.width <= 0f || canvasSize.height <= 0f) return pos
    return Offset(
        pos.x.coerceIn(-CANVAS_MARGIN, canvasSize.width + CANVAS_MARGIN),
        pos.y.coerceIn(-CANVAS_MARGIN, canvasSize.height + CANVAS_MARGIN)
    )
}
