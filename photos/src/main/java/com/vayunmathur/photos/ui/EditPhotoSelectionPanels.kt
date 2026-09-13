package com.vayunmathur.photos.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.graphics.createBitmap
import androidx.compose.foundation.Image
import androidx.ink.strokes.Stroke as InkStroke
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.TextElement
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun SelectionOverlay(
    isEllipse: Boolean,
    dragStart: Offset?,
    dragCurrent: Offset?,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(isEllipse) {
            detectDragGestures(
                onDragStart = { onStart(it) },
                onDrag = { change, _ -> change.consume(); onDrag(change.position) },
                onDragEnd = { onEnd() },
            )
        },
    ) {
        val s = dragStart; val c = dragCurrent
        if (s != null && c != null) {
            val topLeft = Offset(minOf(s.x, c.x), minOf(s.y, c.y))
            val sz = Size(kotlin.math.abs(c.x - s.x), kotlin.math.abs(c.y - s.y))
            val effect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            if (isEllipse) drawOval(Color.White, topLeft, sz, style = Stroke(width = 2f, pathEffect = effect))
            else drawRect(Color.White, topLeft, sz, style = Stroke(width = 2f, pathEffect = effect))
        }
    }
}

@Composable
internal fun LassoOverlay(
    points: List<Offset>,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { onStart(it) },
                onDrag = { change, _ -> change.consume(); onDrag(change.position) },
                onDragEnd = { onEnd() },
            )
        },
    ) {
        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, Color.White, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
        }
    }
}

@Composable
internal fun PolygonOverlay(
    points: List<Offset>,
    onTap: (Offset) -> Unit,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { onTap(it) }
        },
    ) {
        if (points.isNotEmpty()) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, Color.White, style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            points.forEach { drawCircle(Color.White, radius = 5f, center = it) }
        }
    }
}

/**
 * Persistent overlay showing the current selection as a translucent tint (built
 * once from the mask). Works for every selection shape, including feathered and
 * non-rectangular ones.
 */
@Composable
internal fun SelectionMaskOverlay(selection: Selection) {
    val image = remember(selection) {
        val w = selection.width
        val h = selection.height
        val px = IntArray(w * h)
        for (i in px.indices) {
            val a = (selection.mask[i] * 100f).toInt().coerceIn(0, 255)
            // Translucent cyan tint where selected.
            px[i] = (a shl 24) or 0x33B5E5
        }
        val bmp = createBitmap(w, h)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        bmp.asImageBitmap()
    }
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
    )
}

/**
 * True if the crop rectangle — center [cx,cy] and half-extents [hx,hy] (all normalized to the image),
 * rotated by [angleDeg] around its center — lies entirely within the image bounds [0,w]×[0,h].
 * Used to reject a rotation (or 90° turn) that would pull a crop corner off the photo; the user must
 * shrink the crop first so it fits at the new angle.
 */
internal fun cropWithinImage(
    cx: Float,
    cy: Float,
    hx: Float,
    hy: Float,
    angleDeg: Float,
    w: Float,
    h: Float,
): Boolean {
    val a = Math.toRadians(angleDeg.toDouble())
    val ca = cos(a)
    val sa = sin(a)
    val cpx = cx * w
    val cpy = cy * h
    val phx = hx * w
    val phy = hy * h
    val eps = 0.5 // sub-pixel tolerance so touching the edge exactly still counts as inside
    for (sx in intArrayOf(-1, 1)) {
        for (sy in intArrayOf(-1, 1)) {
            val x = cpx + (sx * phx * ca - sy * phy * sa)
            val y = cpy + (sx * phx * sa + sy * phy * ca)
            if (x < -eps || x > w + eps || y < -eps || y > h + eps) return false
        }
    }
    return true
}

internal fun hitTestText(
    x: Float, y: Float, texts: List<TextElement>, viewportWidth: Float, viewportHeight: Float, density: Float,
): Int? {
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    for (i in texts.indices.reversed()) {
        val elem = texts[i]
        paint.textSize = elem.fontSize * density
        val textWidth = paint.measureText(elem.text)
        val textHeight = paint.textSize
        val ex = elem.x * viewportWidth
        val ey = elem.y * viewportHeight
        if (x in ex..(ex + textWidth) && y in ey..(ey + textHeight + 4f)) return i
    }
    return null
}

internal fun hitTestStroke(x: Float, y: Float, strokes: List<InkStroke>): Int? {
    val hitRadius = 20f
    for (i in strokes.indices.reversed()) {
        strokes[i].shape.computeBoundingBox()?.let { box ->
            if (box.xMin <= x + hitRadius && box.xMax >= x - hitRadius && box.yMin <= y + hitRadius && box.yMax >= y - hitRadius) return i
        }
    }
    return null
}
