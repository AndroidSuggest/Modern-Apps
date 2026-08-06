package com.vayunmathur.library.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.annotation.ColorInt
import androidx.core.graphics.createBitmap
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlin.math.max

/**
 * Rasterises one of the Material 3 expressive shapes (`MaterialShapes.Pill`, `.Cookie4Sided`, …)
 * into a square bitmap so a Glance widget can use it as a background.
 *
 * RemoteViews has no equivalent of a Compose `Shape`, so the alternative is hand-authoring a
 * vector drawable per shape and letting it drift from the real Material geometry. The polygon is
 * scaled to fill [sizePx] while keeping its aspect ratio and staying centred.
 */
fun RoundedPolygon.toWidgetBitmap(sizePx: Int, @ColorInt color: Int): Bitmap {
    // Exact (not control-point) bounds, so the shape touches the edges of the bitmap.
    val bounds = calculateBounds(approximate = false)
    val width = bounds[2] - bounds[0]
    val height = bounds[3] - bounds[1]
    val scale = sizePx / max(width, height)

    val path = toPath().apply {
        transform(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    (sizePx - width * scale) / 2f - bounds[0] * scale,
                    (sizePx - height * scale) / 2f - bounds[1] * scale,
                )
            }
        )
    }

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    return createBitmap(sizePx, sizePx)
        .also { Canvas(it).drawPath(path, paint) }
}
