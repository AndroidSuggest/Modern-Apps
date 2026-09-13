package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CropOverlay(
    cx: Float,
    cy: Float,
    hx: Float,
    hy: Float,
    angleDeg: Float,
    onChange: (cx: Float, cy: Float, hx: Float, hy: Float) -> Unit,
    onAngle: (Float) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val onChangeNow by rememberUpdatedState(onChange)
        val onAngleNow by rememberUpdatedState(onAngle)
        val armGapPx = with(LocalDensity.current) { 40.dp.toPx() }
        val minPx = with(LocalDensity.current) { 24.dp.toPx() }

        val a = Math.toRadians(angleDeg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        // rotate a vector by +angle
        fun rot(vx: Float, vy: Float) = Offset(vx * ca - vy * sa, vx * sa + vy * ca)
        // rotate a vector by -angle (into local frame)
        fun unrot(vx: Float, vy: Float) = Offset(vx * ca + vy * sa, -vx * sa + vy * ca)

        val cpx = cx * width
        val cpy = cy * height
        val phx = hx * width
        val phy = hy * height
        fun corner(sx: Int, sy: Int): Offset {
            val r = rot(sx * phx, sy * phy)
            return Offset(cpx + r.x, cpy + r.y)
        }
        val tl = corner(-1, -1)
        val tr = corner(1, -1)
        val br = corner(1, 1)
        val bl = corner(-1, 1)
        fun mid(p: Offset, q: Offset) = Offset((p.x + q.x) / 2f, (p.y + q.y) / 2f)
        val topMid = mid(tl, tr)
        val up = rot(0f, -1f)
        val rotateHandle = Offset(topMid.x + up.x * armGapPx, topMid.y + up.y * armGapPx)

        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val quad = Path().apply {
                moveTo(tl.x, tl.y); lineTo(tr.x, tr.y); lineTo(br.x, br.y); lineTo(bl.x, bl.y); close()
            }
            val dim = Path().apply {
                addRect(Rect(0f, 0f, width, height)); addPath(quad); fillType = PathFillType.EvenOdd
            }
            drawPath(dim, Color.Black.copy(alpha = 0.5f))
            drawPath(quad, Color.White, style = Stroke(width = 2.dp.toPx()))
            drawLine(Color.White, topMid, rotateHandle, strokeWidth = 2.dp.toPx())
        }

        // Body drag (move the whole quad).
        Handle(Offset(cpx, cpy)) { d ->
            onChangeNow((cpx + d.x) / width, (cpy + d.y) / height, hx, hy)
        }

        // Corner handles: keep the opposite corner fixed.
        fun cornerDrag(sx: Int, sy: Int, d: Offset) {
            val opp = corner(-sx, -sy)
            val newC = Offset(corner(sx, sy).x + d.x, corner(sx, sy).y + d.y)
            val nCenter = Offset((opp.x + newC.x) / 2f, (opp.y + newC.y) / 2f)
            val local = unrot(newC.x - opp.x, newC.y - opp.y)
            val nhpx = (abs(local.x) / 2f).coerceAtLeast(minPx)
            val nhpy = (abs(local.y) / 2f).coerceAtLeast(minPx)
            onChangeNow(nCenter.x / width, nCenter.y / height, nhpx / width, nhpy / height)
        }
        Handle(tl) { d -> cornerDrag(-1, -1, d) }
        Handle(tr) { d -> cornerDrag(1, -1, d) }
        Handle(br) { d -> cornerDrag(1, 1, d) }
        Handle(bl) { d -> cornerDrag(-1, 1, d) }

        // Edge handles: move one edge along its local normal, opposite edge fixed.
        fun edgeDrag(edge: Int, d: Offset) {
            val local = unrot(d.x, d.y)
            var nhpx = phx
            var nhpy = phy
            var shiftLocalX = 0f
            var shiftLocalY = 0f
            when (edge) {
                0 -> { nhpx = phx - local.x / 2f; shiftLocalX = local.x / 2f } // left
                1 -> { nhpx = phx + local.x / 2f; shiftLocalX = local.x / 2f } // right
                2 -> { nhpy = phy - local.y / 2f; shiftLocalY = local.y / 2f } // top
                3 -> { nhpy = phy + local.y / 2f; shiftLocalY = local.y / 2f } // bottom
            }
            nhpx = nhpx.coerceAtLeast(minPx)
            nhpy = nhpy.coerceAtLeast(minPx)
            val shift = rot(shiftLocalX, shiftLocalY)
            onChangeNow((cpx + shift.x) / width, (cpy + shift.y) / height, nhpx / width, nhpy / height)
        }
        Handle(mid(tl, bl)) { d -> edgeDrag(0, d) }
        Handle(mid(tr, br)) { d -> edgeDrag(1, d) }
        Handle(topMid) { d -> edgeDrag(2, d) }
        Handle(mid(bl, br)) { d -> edgeDrag(3, d) }

        // Rotate handle.
        Handle(rotateHandle) { d ->
            val px = rotateHandle.x + d.x
            val py = rotateHandle.y + d.y
            val ang = Math.toDegrees(atan2((px - cpx).toDouble(), (cpy - py).toDouble())).toFloat()
            onAngleNow(ang)
        }
    }
}

@Composable
internal fun Handle(offset: Offset, onDrag: (Offset) -> Unit) {
    val density = LocalDensity.current
    val handleSize = 24.dp
    val handleRadiusPx = with(density) { (handleSize / 2).toPx() }
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .offset { IntOffset((offset.x - handleRadiusPx).roundToInt(), (offset.y - handleRadiusPx).roundToInt()) }
            .size(handleSize)
            .background(Color.White, CircleShape)
            .border(1.dp, Color.Black, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount -> change.consume(); currentOnDrag(dragAmount) }
            },
    )
}
