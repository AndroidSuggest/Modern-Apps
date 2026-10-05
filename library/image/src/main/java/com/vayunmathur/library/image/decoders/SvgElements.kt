package com.vayunmathur.library.image.decoders

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF

internal fun drawElement(canvas: Canvas, tagName: String, attrs: Map<String, String>, style: EffectiveStyle) {
    when (tagName) {
        TAG_PATH -> drawPathElement(canvas, attrs, style)
        TAG_RECT -> drawRectElement(canvas, attrs, style)
        TAG_CIRCLE -> drawCircleElement(canvas, attrs, style)
        TAG_ELLIPSE -> drawEllipseElement(canvas, attrs, style)
        TAG_LINE -> drawLineElement(canvas, attrs, style)
        TAG_POLYLINE -> drawPolyElement(canvas, attrs, style, close = false)
        TAG_POLYGON -> drawPolyElement(canvas, attrs, style, close = true)
    }
}

internal const val TAG_PATH = "path"
internal const val TAG_RECT = "rect"
internal const val TAG_CIRCLE = "circle"
internal const val TAG_ELLIPSE = "ellipse"
internal const val TAG_LINE = "line"
internal const val TAG_POLYLINE = "polyline"
internal const val TAG_POLYGON = "polygon"

internal fun drawPathElement(canvas: Canvas, attrs: Map<String, String>, style: EffectiveStyle) {
    val data = attrs[ATTR_PATH_DATA] ?: return
    if (data.isBlank()) return
    val path = parsePathData(data)
    if (path.isEmpty) return
    drawPathWithStyle(canvas, path, style)
}

internal const val ATTR_PATH_DATA = "d"

internal fun drawRectElement(canvas: Canvas, attrs: Map<String, String>, style: EffectiveStyle) {
    val left = parseLength(attrs[ATTR_X]) ?: NO_OFFSET
    val top = parseLength(attrs[ATTR_Y]) ?: NO_OFFSET
    val width = parseLength(attrs[ATTR_WIDTH]) ?: NO_OFFSET
    val height = parseLength(attrs[ATTR_HEIGHT]) ?: NO_OFFSET
    if (width <= 0 || height <= 0) return
    val path = Path()
    val rect = RectF(left, top, left + width, top + height)
    addRectOrRoundRect(path, rect, attrs, width, height)
    drawPathWithStyle(canvas, path, style)
}

internal fun addRectOrRoundRect(path: Path, rect: RectF, attrs: Map<String, String>, width: Float, height: Float) {
    val radiusX = parseLength(attrs[ATTR_RADIUS_X])
    val radiusY = parseLength(attrs[ATTR_RADIUS_Y])
    val wantsRound = (radiusX != null && radiusX > 0) || (radiusY != null && radiusY > 0)
    if (!wantsRound) {
        path.addRect(rect, Path.Direction.CW)
        return
    }
    val resolvedX = radiusX ?: radiusY ?: NO_OFFSET
    val resolvedY = radiusY ?: radiusX ?: NO_OFFSET
    val cornerX = resolvedX.coerceAtMost(width / CENTER_DIVISOR)
    val cornerY = resolvedY.coerceAtMost(height / CENTER_DIVISOR)
    path.addRoundRect(rect, cornerX, cornerY, Path.Direction.CW)
}

internal const val ATTR_RADIUS_X = "rx"
internal const val ATTR_RADIUS_Y = "ry"

internal fun drawCircleElement(canvas: Canvas, attrs: Map<String, String>, style: EffectiveStyle) {
    val centerX = parseLength(attrs[ATTR_CENTER_X]) ?: NO_OFFSET
    val centerY = parseLength(attrs[ATTR_CENTER_Y]) ?: NO_OFFSET
    val radius = parseLength(attrs[ATTR_RADIUS]) ?: NO_OFFSET
    if (radius <= 0) return
    val path = Path()
    path.addCircle(centerX, centerY, radius, Path.Direction.CW)
    drawPathWithStyle(canvas, path, style)
}

internal const val ATTR_CENTER_X = "cx"
internal const val ATTR_CENTER_Y = "cy"
internal const val ATTR_RADIUS = "r"

internal fun drawEllipseElement(canvas: Canvas, attrs: Map<String, String>, style: EffectiveStyle) {
    val centerX = parseLength(attrs[ATTR_CENTER_X]) ?: NO_OFFSET
    val centerY = parseLength(attrs[ATTR_CENTER_Y]) ?: NO_OFFSET
    val radiusX = parseLength(attrs[ATTR_RADIUS_X]) ?: NO_OFFSET
    val radiusY = parseLength(attrs[ATTR_RADIUS_Y]) ?: NO_OFFSET
    if (radiusX <= 0 || radiusY <= 0) return
    val path = Path()
    val oval = RectF(centerX - radiusX, centerY - radiusY, centerX + radiusX, centerY + radiusY)
    path.addOval(oval, Path.Direction.CW)
    drawPathWithStyle(canvas, path, style)
}

internal fun drawLineElement(canvas: Canvas, attrs: Map<String, String>, style: EffectiveStyle) {
    val startX = parseLength(attrs[ATTR_X1]) ?: NO_OFFSET
    val startY = parseLength(attrs[ATTR_Y1]) ?: NO_OFFSET
    val endX = parseLength(attrs[ATTR_X2]) ?: NO_OFFSET
    val endY = parseLength(attrs[ATTR_Y2]) ?: NO_OFFSET
    val path = Path()
    path.moveTo(startX, startY)
    path.lineTo(endX, endY)
    drawPathWithStyle(canvas, path, style)
}

internal const val ATTR_X1 = "x1"
internal const val ATTR_Y1 = "y1"
internal const val ATTR_X2 = "x2"
internal const val ATTR_Y2 = "y2"

internal fun drawPolyElement(canvas: Canvas, attrs: Map<String, String>, style: EffectiveStyle, close: Boolean) {
    val points = attrs[ATTR_POINTS] ?: return
    if (points.isBlank()) return
    val numbers = parseNumbersList(points)
    if (numbers.size < MIN_POLYLINE_NUMBERS) return
    val path = Path()
    path.moveTo(numbers[POLY_FIRST_X], numbers[POLY_FIRST_Y])
    appendPolySegments(path, numbers)
    if (close) path.close()
    drawPathWithStyle(canvas, path, style)
}

internal const val ATTR_POINTS = "points"
internal const val MIN_POLYLINE_NUMBERS = 4
internal const val POLY_FIRST_X = 0
internal const val POLY_FIRST_Y = 1

internal fun appendPolySegments(path: Path, numbers: List<Float>) {
    var index = POLY_SEGMENT_START
    while (index + 1 < numbers.size) {
        path.lineTo(numbers[index], numbers[index + 1])
        index += POLY_SEGMENT_STEP
    }
}

internal const val POLY_SEGMENT_START = 2
internal const val POLY_SEGMENT_STEP = 2

