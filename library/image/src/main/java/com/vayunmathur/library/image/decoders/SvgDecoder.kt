package com.vayunmathur.library.image.decoders

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.vayunmathur.library.image.ImageRequest
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure Android stdlib SVG decoder – own implementation.
 *
 * Only uses:
 * - android.graphics.* (Bitmap, Canvas, Paint, Path, Matrix, Color) – Android stdlib
 * - org.xmlpull.v1.XmlPullParser (Android runtime XML parser, part of Android stdlib)
 * - Kotlin stdlib / JetBrains stdlib (regex, math)
 */
object SvgDecoder {

    fun canDecode(bytes: ByteArray, dataHint: Any?): Boolean {
        if (dataHint is String) {
            val lower = dataHint.lowercase()
            if (lower.endsWith(".svg") || lower.contains(".svg?") || lower.contains("image/svg")) return true
        }
        return BitmapDecoder.isSvg(bytes)
    }

    suspend fun decode(bytes: ByteArray, request: ImageRequest): Bitmap? {
        return try {
            val svgString = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { String(bytes, Charsets.ISO_8859_1) }
            val rootInfo = parseSvgRoot(svgString)
            val reqSize = request.size
            val targetW = if (reqSize != null && !reqSize.isOriginal()) reqSize.width else 512
            val targetH = if (reqSize != null && !reqSize.isOriginal()) reqSize.height else 512
            val docW = rootInfo.docWidth
            val docH = rootInfo.docHeight
            val aspect: Float = if (docW > 0 && docH > 0) docW / docH else 1f
            val outW: Int
            val outH: Int
            if (targetW > 0 && targetH > 0) { outW = targetW; outH = targetH }
            else if (targetW > 0) { outW = targetW; outH = (targetW / aspect).toInt().coerceAtLeast(1) }
            else if (docW > 0 && docH > 0) { outW = docW.toInt().coerceAtLeast(1); outH = docH.toInt().coerceAtLeast(1) }
            else { outW = 512; outH = 512 }

            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)
            renderSvgToCanvas(canvas, svgString, outW, outH, rootInfo)
            bitmap
        } catch (_: Exception) { null }
    }
}

// --- internal models ---

private data class ViewBox(val minX: Float, val minY: Float, val width: Float, val height: Float)
private data class RootInfo(val docWidth: Float, val docHeight: Float, val viewBox: ViewBox?)
private data class EffectiveStyle(
    val fill: String?, val fillOpacity: Float, val stroke: String?, val strokeOpacity: Float,
    val strokeWidth: Float, val strokeLineCap: String, val strokeLineJoin: String,
    val strokeMiterLimit: Float, val fillRule: String, val display: String?, val visibility: String?,
    val effectiveOpacity: Float
) {
    companion object { fun default() = EffectiveStyle("black", 1f, null, 1f, 1f, "butt", "miter", 4f, "nonzero", null, null, 1f) }
}

private val leadingNumberRegex = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")
private val numberRegex = Regex("""[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?""")

private fun parseLength(v: String?): Float? {
    if (v == null) return null
    val s = v.trim(); if (s.isEmpty()) return null
    return leadingNumberRegex.find(s)?.value?.toFloatOrNull()
}
private fun parseDocLength(v: String?): Float? {
    if (v == null) return null
    val s = v.trim(); if (s.isEmpty() || s.contains("%")) return null
    return leadingNumberRegex.find(s)?.value?.toFloatOrNull()
}
private fun parseViewBox(vb: String?): ViewBox? {
    if (vb == null) return null
    val parts = vb.replace(',', ' ').trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
    if (parts.size == 4 && parts[2] > 0 && parts[3] > 0) return ViewBox(parts[0], parts[1], parts[2], parts[3])
    return null
}
private fun parseNumbersList(s: String): List<Float> {
    if (s.isBlank()) return emptyList()
    return numberRegex.findAll(s).mapNotNull { it.value.toFloatOrNull() }.toList()
}
private fun parseStyleAttribute(style: String): Map<String, String> {
    val m = mutableMapOf<String, String>()
    for (decl in style.split(';')) {
        val t = decl.trim(); if (t.isEmpty()) continue
        val c = t.indexOf(':'); if (c <= 0) continue
        val prop = t.substring(0, c).trim().lowercase()
        val value = t.substring(c + 1).trim()
        if (prop.isNotEmpty() && value.isNotEmpty()) m[prop] = value
    }
    return m
}
private fun parseRgbComp(c: String): Int {
    val t = c.trim()
    return if (t.endsWith("%")) {
        val v = t.removeSuffix("%").toFloatOrNull() ?: 0f
        (v * 255f / 100f).toInt().coerceIn(0, 255)
    } else t.toFloatOrNull()?.toInt()?.coerceIn(0, 255) ?: 0
}
private fun parseAlphaComp(c: String): Float {
    val t = c.trim()
    return if (t.endsWith("%")) {
        val v = t.removeSuffix("%").toFloatOrNull() ?: 1f
        (v / 100f).coerceIn(0f, 1f)
    } else {
        val f = t.toFloatOrNull() ?: 1f
        if (f > 1f) (f / 255f).coerceIn(0f, 1f) else f.coerceIn(0f, 1f)
    }
}
private fun parseColorString(cs: String?): Int? {
    if (cs == null) return null
    val s = cs.trim(); if (s.isEmpty()) return null
    val lower = s.lowercase()
    if (lower == "none") return null
    if (lower == "transparent") return Color.TRANSPARENT
    if (lower == "currentcolor") return null
    if (lower.startsWith("#")) { try { return Color.parseColor(s) } catch (_: Exception) {} }
    if (lower.startsWith("rgb(") || lower.startsWith("rgba(")) {
        try {
            val inner = s.substringAfter('(').substringBeforeLast(')').replace('/', ' ').trim()
            val parts = inner.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                val r = parseRgbComp(parts[0]); val g = parseRgbComp(parts[1]); val b = parseRgbComp(parts[2])
                val a = if (parts.size >= 4) parseAlphaComp(parts[3]) else 1f
                return Color.argb((a * 255).toInt().coerceIn(0, 255), r, g, b)
            }
        } catch (_: Exception) {}
    }
    try { return Color.parseColor(s) } catch (_: Exception) {
        return when (lower) {
            "red" -> Color.RED; "green" -> Color.GREEN; "blue" -> Color.BLUE
            "black" -> Color.BLACK; "white" -> Color.WHITE
            "gray", "grey" -> Color.GRAY; "yellow" -> Color.YELLOW
            "cyan", "aqua" -> Color.CYAN; "magenta", "fuchsia" -> Color.MAGENTA
            else -> null
        }
    }
}
private fun parseTransform(tr: String?): Matrix? {
    if (tr.isNullOrBlank()) return null
    val result = Matrix(); result.reset()
    val regex = Regex("""([a-zA-Z]+)\s*\(([^)]*)\)""")
    var found = false
    for (m in regex.findAll(tr)) {
        val name = m.groupValues[1].trim().lowercase()
        val args = parseNumbersList(m.groupValues[2])
        val local = Matrix()
        when (name) {
            "matrix" -> { if (args.size >= 6) local.setValues(floatArrayOf(args[0], args[2], args[4], args[1], args[3], args[5], 0f, 0f, 1f)) else continue }
            "translate" -> when {
                args.size >= 2 -> local.setTranslate(args[0], args[1])
                args.size == 1 -> local.setTranslate(args[0], 0f)
                else -> continue
            }
            "scale" -> when {
                args.size >= 2 -> local.setScale(args[0], args[1])
                args.size == 1 -> local.setScale(args[0], args[0])
                else -> continue
            }
            "rotate" -> when {
                args.size >= 3 -> local.setRotate(args[0], args[1], args[2])
                args.size == 1 -> local.setRotate(args[0])
                else -> continue
            }
            "skewx" -> { if (args.isNotEmpty()) { val t = tan(Math.toRadians(args[0].toDouble())).toFloat(); local.setSkew(t, 0f) } else continue }
            "skewy" -> { if (args.isNotEmpty()) { val t = tan(Math.toRadians(args[0].toDouble())).toFloat(); local.setSkew(0f, t) } else continue }
            else -> continue
        }
        result.postConcat(local); found = true
    }
    return if (found) result else null
}
private fun computeEffective(parent: EffectiveStyle, attrs: Map<String, String>): EffectiveStyle {
    val rawFill = attrs["fill"]
    val fill: String? = when {
        rawFill == null -> parent.fill
        rawFill.equals("none", ignoreCase = true) -> null
        rawFill.equals("inherit", ignoreCase = true) -> parent.fill
        rawFill.equals("currentcolor", ignoreCase = true) -> parent.fill
        else -> rawFill
    }
    val fillOpacity = attrs["fill-opacity"]?.toFloatOrNull() ?: parent.fillOpacity
    val rawStroke = attrs["stroke"]
    val stroke: String? = when {
        rawStroke == null -> parent.stroke
        rawStroke.equals("none", ignoreCase = true) -> null
        rawStroke.equals("inherit", ignoreCase = true) -> parent.stroke
        rawStroke.equals("currentcolor", ignoreCase = true) -> parent.stroke
        else -> rawStroke
    }
    val strokeOpacity = attrs["stroke-opacity"]?.toFloatOrNull() ?: parent.strokeOpacity
    val strokeWidth = attrs["stroke-width"]?.let { parseLength(it) } ?: parent.strokeWidth
    val strokeLineCap = attrs["stroke-linecap"] ?: parent.strokeLineCap
    val strokeLineJoin = attrs["stroke-linejoin"] ?: parent.strokeLineJoin
    val strokeMiterLimit = attrs["stroke-miterlimit"]?.toFloatOrNull() ?: parent.strokeMiterLimit
    val fillRule = attrs["fill-rule"] ?: parent.fillRule
    val display = attrs["display"]
    val visibility = attrs["visibility"]
    val localOpacity = attrs["opacity"]?.toFloatOrNull() ?: 1f
    val eff = (parent.effectiveOpacity * localOpacity).coerceIn(0f, 1f)
    return EffectiveStyle(
        fill, fillOpacity.coerceIn(0f, 1f), stroke, strokeOpacity.coerceIn(0f, 1f),
        strokeWidth, strokeLineCap, strokeLineJoin, strokeMiterLimit, fillRule, display, visibility, eff
    )
}
private fun drawPathWithStyle(canvas: Canvas, path: Path, style: EffectiveStyle) {
    if (path.isEmpty) return
    path.fillType = if (style.fillRule.equals("evenodd", ignoreCase = true)) Path.FillType.EVEN_ODD else Path.FillType.WINDING
    if (style.fill != null) {
        val resolved = parseColorString(style.fill) ?: Color.BLACK
        val baseAlpha = Color.alpha(resolved) / 255f
        val finalAlpha = (baseAlpha * style.fillOpacity * style.effectiveOpacity * 255f).toInt().coerceIn(0, 255)
        if (finalAlpha > 0) {
            val finalColor = (finalAlpha shl 24) or (resolved and 0x00FFFFFF)
            val paint = Paint().apply { isAntiAlias = true; this.style = Paint.Style.FILL; color = finalColor }
            canvas.drawPath(path, paint)
        }
    }
    if (style.stroke != null && style.strokeWidth > 0.001f) {
        val resolved = parseColorString(style.stroke) ?: Color.BLACK
        val baseAlpha = Color.alpha(resolved) / 255f
        val finalAlpha = (baseAlpha * style.strokeOpacity * style.effectiveOpacity * 255f).toInt().coerceIn(0, 255)
        if (finalAlpha > 0) {
            val finalColor = (finalAlpha shl 24) or (resolved and 0x00FFFFFF)
            val paint = Paint().apply {
                isAntiAlias = true; this.style = Paint.Style.STROKE; color = finalColor; strokeWidth = style.strokeWidth
                strokeCap = when (style.strokeLineCap.lowercase()) { "round" -> Paint.Cap.ROUND; "square" -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
                strokeJoin = when (style.strokeLineJoin.lowercase()) { "round" -> Paint.Join.ROUND; "bevel" -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
                strokeMiter = style.strokeMiterLimit
            }
            canvas.drawPath(path, paint)
        }
    }
}
private fun parseSvgRoot(svgContent: String): RootInfo {
    try {
        val factory = XmlPullParserFactory.newInstance(); factory.isNamespaceAware = false
        val parser = factory.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(svgContent))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("svg", ignoreCase = true)) {
                var docW = 0f; var docH = 0f; var vb: ViewBox? = null
                for (i in 0 until parser.attributeCount) {
                    val an = parser.getAttributeName(i)?.lowercase(); val av = parser.getAttributeValue(i) ?: continue
                    when (an) { "width" -> parseDocLength(av)?.let { docW = it }; "height" -> parseDocLength(av)?.let { docH = it }; "viewbox" -> vb = parseViewBox(av) }
                }
                if ((docW <= 0 || docH <= 0) && vb != null) { if (docW <= 0) docW = vb.width; if (docH <= 0) docH = vb.height }
                return RootInfo(docW, docH, vb)
            }
            event = parser.next()
        }
    } catch (_: Exception) {}
    return RootInfo(0f, 0f, null)
}
private fun renderSvgToCanvas(canvas: Canvas, svgContent: String, outW: Int, outH: Int, rootInfo: RootInfo) {
    val factory = XmlPullParserFactory.newInstance(); factory.isNamespaceAware = false
    val parser = factory.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(StringReader(svgContent))
    val styleStack = ArrayDeque<EffectiveStyle>(); var currentStyle = EffectiveStyle.default()
    val canvasSaveStack = ArrayDeque<Int>(); var skipDepth: Int? = null
    val nonRendering = setOf("defs", "clippath", "mask", "pattern", "filter", "style", "script", "title", "desc", "metadata", "lineargradient", "radialgradient", "stop", "image")
    val baseSave = canvas.save()
    if (rootInfo.viewBox != null) {
        val vb = rootInfo.viewBox; val scaleX = outW / vb.width; val scaleY = outH / vb.height; val scale = kotlin.math.min(scaleX, scaleY)
        val scaledW = vb.width * scale; val scaledH = vb.height * scale
        val tx = (outW - scaledW) / 2f; val ty = (outH - scaledH) / 2f
        canvas.translate(tx, ty); canvas.scale(scale, scale); canvas.translate(-vb.minX, -vb.minY)
    } else if (rootInfo.docWidth > 0 && rootInfo.docHeight > 0) {
        val sx = outW / rootInfo.docWidth; val sy = outH / rootInfo.docHeight
        if (kotlin.math.abs(sx - 1f) > 0.001f || kotlin.math.abs(sy - 1f) > 0.001f) canvas.scale(sx, sy)
    }
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG) {
            val depth = parser.depth
            if (skipDepth != null && depth > skipDepth) { eventType = parser.next(); continue }
            val tagName = parser.name?.lowercase() ?: ""
            if (tagName in nonRendering) { skipDepth = depth; eventType = parser.next(); continue }
            val attrs = mutableMapOf<String, String>()
            for (i in 0 until parser.attributeCount) { val an = parser.getAttributeName(i)?.lowercase() ?: continue; val av = parser.getAttributeValue(i) ?: continue; attrs[an] = av }
            val styleAttr = attrs["style"]
            if (!styleAttr.isNullOrBlank()) { val sm = parseStyleAttribute(styleAttr); for ((k, v) in sm) attrs[k] = v; attrs.remove("style") }
            if (attrs["display"]?.trim()?.lowercase() == "none") { skipDepth = depth; eventType = parser.next(); continue }
            val vis = attrs["visibility"]?.trim()?.lowercase()
            if (vis == "hidden" || vis == "collapse") { skipDepth = depth; eventType = parser.next(); continue }
            val effective = computeEffective(parent = currentStyle, attrs = attrs)
            if (effective.effectiveOpacity <= 0.01f) { skipDepth = depth; eventType = parser.next(); continue }
            val saveCount = canvas.save(); canvasSaveStack.addLast(saveCount); styleStack.addLast(currentStyle); currentStyle = effective
            parseTransform(attrs["transform"])?.let { canvas.concat(it) }
            if (tagName == "svg" && depth > 1) {
                val nx = parseLength(attrs["x"]) ?: 0f; val ny = parseLength(attrs["y"]) ?: 0f; if (nx != 0f || ny != 0f) canvas.translate(nx, ny)
                val nestedVb = parseViewBox(attrs["viewbox"]); val nestedW = parseLength(attrs["width"]); val nestedH = parseLength(attrs["height"])
                if (nestedVb != null && nestedW != null && nestedH != null && nestedW > 0 && nestedH > 0) {
                    val sx = nestedW / nestedVb.width; val sy = nestedH / nestedVb.height; val s = kotlin.math.min(sx, sy)
                    val sw = nestedVb.width * s; val sh = nestedVb.height * s; val tx = (nestedW - sw) / 2f; val ty = (nestedH - sh) / 2f
                    canvas.translate(tx, ty); canvas.scale(s, s); canvas.translate(-nestedVb.minX, -nestedVb.minY)
                } else if (nestedVb != null) { canvas.translate(-nestedVb.minX, -nestedVb.minY) }
            }
            try {
                when (tagName) {
                    "path" -> { val d = attrs["d"]; if (!d.isNullOrBlank()) { val p = parsePathData(d); if (!p.isEmpty) drawPathWithStyle(canvas, p, currentStyle) } }
                    "rect" -> {
                        val x = parseLength(attrs["x"]) ?: 0f; val y = parseLength(attrs["y"]) ?: 0f; val w = parseLength(attrs["width"]) ?: 0f; val h = parseLength(attrs["height"]) ?: 0f
                        if (w > 0 && h > 0) {
                            val rx = parseLength(attrs["rx"]); val ry = parseLength(attrs["ry"]); val path = Path(); val rect = RectF(x, y, x + w, y + h)
                            if ((rx != null && rx > 0) || (ry != null && ry > 0)) {
                                val rxV = rx ?: ry ?: 0f; val ryV = ry ?: rx ?: 0f; val crx = rxV.coerceAtMost(w / 2f); val cry = ryV.coerceAtMost(h / 2f)
                                path.addRoundRect(rect, crx, cry, Path.Direction.CW)
                            } else path.addRect(rect, Path.Direction.CW)
                            drawPathWithStyle(canvas, path, currentStyle)
                        }
                    }
                    "circle" -> { val cx = parseLength(attrs["cx"]) ?: 0f; val cy = parseLength(attrs["cy"]) ?: 0f; val r = parseLength(attrs["r"]) ?: 0f; if (r > 0) { val path = Path(); path.addCircle(cx, cy, r, Path.Direction.CW); drawPathWithStyle(canvas, path, currentStyle) } }
                    "ellipse" -> { val cx = parseLength(attrs["cx"]) ?: 0f; val cy = parseLength(attrs["cy"]) ?: 0f; val rx = parseLength(attrs["rx"]) ?: 0f; val ry = parseLength(attrs["ry"]) ?: 0f; if (rx > 0 && ry > 0) { val path = Path(); val oval = RectF(cx - rx, cy - ry, cx + rx, cy + ry); path.addOval(oval, Path.Direction.CW); drawPathWithStyle(canvas, path, currentStyle) } }
                    "line" -> { val x1 = parseLength(attrs["x1"]) ?: 0f; val y1 = parseLength(attrs["y1"]) ?: 0f; val x2 = parseLength(attrs["x2"]) ?: 0f; val y2 = parseLength(attrs["y2"]) ?: 0f; val path = Path(); path.moveTo(x1, y1); path.lineTo(x2, y2); drawPathWithStyle(canvas, path, currentStyle) }
                    "polyline" -> { val pts = attrs["points"]; if (!pts.isNullOrBlank()) { val nums = parseNumbersList(pts); if (nums.size >= 4) { val path = Path(); path.moveTo(nums[0], nums[1]); var i = 2; while (i + 1 < nums.size) { path.lineTo(nums[i], nums[i + 1]); i += 2 }; drawPathWithStyle(canvas, path, currentStyle) } } }
                    "polygon" -> { val pts = attrs["points"]; if (!pts.isNullOrBlank()) { val nums = parseNumbersList(pts); if (nums.size >= 4) { val path = Path(); path.moveTo(nums[0], nums[1]); var i = 2; while (i + 1 < nums.size) { path.lineTo(nums[i], nums[i + 1]); i += 2 }; path.close(); drawPathWithStyle(canvas, path, currentStyle) } } }
                }
            } catch (_: Exception) {}
        } else if (eventType == XmlPullParser.END_TAG) {
            val depth = parser.depth
            if (skipDepth != null) { if (depth == skipDepth) skipDepth = null; eventType = parser.next(); continue }
            if (canvasSaveStack.isNotEmpty()) { val sc = canvasSaveStack.removeLast(); canvas.restoreToCount(sc) }
            if (styleStack.isNotEmpty()) { currentStyle = styleStack.removeLast() }
        }
        eventType = parser.next()
    }
    canvas.restoreToCount(baseSave)
}

private fun parsePathData(d: String): Path {
    val path = Path(); var i = 0; val n = d.length; var curX = 0f; var curY = 0f; var startX = 0f; var startY = 0f; var lastCubicX2 = 0f; var lastCubicY2 = 0f; var lastQuadX1 = 0f; var lastQuadY1 = 0f; var prevCmd: Char = ' '
    fun skipSeparators() { while (i < n && (d[i].isWhitespace() || d[i] == ',')) i++ }
    fun peekIsNumberStart(): Boolean { var j = i; while (j < n && (d[j].isWhitespace() || d[j] == ',')) j++; if (j >= n) return false; val c = d[j]; return c.isDigit() || c == '.' || c == '+' || c == '-' }
    fun parseNumber(): Float? { skipSeparators(); if (i >= n) return null; val sub = d.substring(i); val m = leadingNumberRegex.find(sub); if (m != null && m.range.first == 0) { val v = m.value.toFloatOrNull(); i += m.value.length; return v }; return null }
    fun parseFlag(): Int? { skipSeparators(); if (i >= n) return null; val c = d[i]; if (c == '0' || c == '1') { i++; return c - '0' }; return null }
    var cmd: Char = ' '
    while (i < n) {
        skipSeparators(); if (i >= n) break; val c = d[i]
        if (c in "MmZzLlHhVvCcSsQqTtAa") { cmd = c; i++ } else if (peekIsNumberStart() && cmd != ' ') {} else { i++; continue }
        val isRelative = cmd.isLowerCase()
        when (cmd.uppercaseChar()) {
            'M' -> { var first = true; while (peekIsNumberStart()) { val x = parseNumber() ?: break; val y = parseNumber() ?: break; val nx = if (isRelative) curX + x else x; val ny = if (isRelative) curY + y else y; if (first) { path.moveTo(nx, ny); startX = nx; startY = ny; first = false } else path.lineTo(nx, ny); curX = nx; curY = ny; prevCmd = 'M' }; if (!first) cmd = if (isRelative) 'l' else 'L' }
            'L' -> { while (peekIsNumberStart()) { val x = parseNumber() ?: break; val y = parseNumber() ?: break; val nx = if (isRelative) curX + x else x; val ny = if (isRelative) curY + y else y; path.lineTo(nx, ny); curX = nx; curY = ny; prevCmd = 'L' } }
            'H' -> { while (peekIsNumberStart()) { val x = parseNumber() ?: break; curX = if (isRelative) curX + x else x; path.lineTo(curX, curY); prevCmd = 'H' } }
            'V' -> { while (peekIsNumberStart()) { val y = parseNumber() ?: break; curY = if (isRelative) curY + y else y; path.lineTo(curX, curY); prevCmd = 'V' } }
            'C' -> { while (peekIsNumberStart()) { val x1 = parseNumber() ?: break; val y1 = parseNumber() ?: break; val x2 = parseNumber() ?: break; val y2 = parseNumber() ?: break; val x = parseNumber() ?: break; val y = parseNumber() ?: break; val c1x = if (isRelative) curX + x1 else x1; val c1y = if (isRelative) curY + y1 else y1; val c2x = if (isRelative) curX + x2 else x2; val c2y = if (isRelative) curY + y2 else y2; val nx = if (isRelative) curX + x else x; val ny = if (isRelative) curY + y else y; path.cubicTo(c1x, c1y, c2x, c2y, nx, ny); lastCubicX2 = c2x; lastCubicY2 = c2y; curX = nx; curY = ny; prevCmd = 'C' } }
            'S' -> { while (peekIsNumberStart()) { val x2 = parseNumber() ?: break; val y2 = parseNumber() ?: break; val x = parseNumber() ?: break; val y = parseNumber() ?: break; val c2x = if (isRelative) curX + x2 else x2; val c2y = if (isRelative) curY + y2 else y2; val nx = if (isRelative) curX + x else x; val ny = if (isRelative) curY + y else y; val c1x: Float; val c1y: Float; if (prevCmd.uppercaseChar() == 'C' || prevCmd.uppercaseChar() == 'S') { c1x = curX * 2 - lastCubicX2; c1y = curY * 2 - lastCubicY2 } else { c1x = curX; c1y = curY }; path.cubicTo(c1x, c1y, c2x, c2y, nx, ny); lastCubicX2 = c2x; lastCubicY2 = c2y; curX = nx; curY = ny; prevCmd = 'S' } }
            'Q' -> { while (peekIsNumberStart()) { val x1 = parseNumber() ?: break; val y1 = parseNumber() ?: break; val x = parseNumber() ?: break; val y = parseNumber() ?: break; val c1x = if (isRelative) curX + x1 else x1; val c1y = if (isRelative) curY + y1 else y1; val nx = if (isRelative) curX + x else x; val ny = if (isRelative) curY + y else y; path.quadTo(c1x, c1y, nx, ny); lastQuadX1 = c1x; lastQuadY1 = c1y; curX = nx; curY = ny; prevCmd = 'Q' } }
            'T' -> { while (peekIsNumberStart()) { val x = parseNumber() ?: break; val y = parseNumber() ?: break; val nx = if (isRelative) curX + x else x; val ny = if (isRelative) curY + y else y; val c1x: Float; val c1y: Float; if (prevCmd.uppercaseChar() == 'Q' || prevCmd.uppercaseChar() == 'T') { c1x = curX * 2 - lastQuadX1; c1y = curY * 2 - lastQuadY1 } else { c1x = curX; c1y = curY }; path.quadTo(c1x, c1y, nx, ny); lastQuadX1 = c1x; lastQuadY1 = c1y; curX = nx; curY = ny; prevCmd = 'T' } }
            'A' -> {
                while (peekIsNumberStart()) {
                    val rx = parseNumber() ?: break; val ry = parseNumber() ?: break; val ang = parseNumber() ?: break; val largeArc = parseFlag() ?: break; val sweep = parseFlag() ?: break; val x = parseNumber() ?: break; val y = parseNumber() ?: break
                    val nx = if (isRelative) curX + x else x; val ny = if (isRelative) curY + y else y
                    arcTo(path, curX, curY, rx, ry, ang, largeArc, sweep, nx, ny); curX = nx; curY = ny; prevCmd = 'A'
                }
            }
            'Z' -> { path.close(); curX = startX; curY = startY; prevCmd = 'Z' }
        }
    }
    return path
}
private fun arcTo(path: Path, x0: Float, y0: Float, rxIn: Float, ryIn: Float, angle: Float, largeArcFlag: Int, sweepFlag: Int, x1: Float, y1: Float) {
    if (rxIn == 0f || ryIn == 0f) { path.lineTo(x1, y1); return }
    var rx = abs(rxIn).toDouble(); var ry = abs(ryIn).toDouble()
    val phi = Math.toRadians(angle.toDouble()); val cosPhi = cos(phi); val sinPhi = sin(phi)
    val dx2 = (x0 - x1) / 2.0; val dy2 = (y0 - y1) / 2.0
    val x1p = cosPhi * dx2 + sinPhi * dy2; val y1p = -sinPhi * dx2 + cosPhi * dy2
    var rxSq = rx * rx; var rySq = ry * ry; val x1pSq = x1p * x1p; val y1pSq = y1p * y1p
    var lambda = x1pSq / rxSq + y1pSq / rySq
    if (lambda > 1.0) { val s = sqrt(lambda); rx *= s; ry *= s; rxSq = rx * rx; rySq = ry * ry }
    val sign = if (largeArcFlag == sweepFlag) -1 else 1
    var num = rxSq * rySq - rxSq * y1pSq - rySq * x1pSq; if (num < 0) num = 0.0
    val denom = rxSq * y1pSq + rySq * x1pSq
    val factor = if (denom != 0.0) sign * sqrt(num / denom) else 0.0
    val cxp = factor * (rx * y1p / ry); val cyp = factor * (-ry * x1p / rx)
    val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x1) / 2.0
    val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y1) / 2.0
    val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry; val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry
    var theta1 = atan2(uy, ux); var deltaTheta = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
    if (sweepFlag == 0 && deltaTheta > 0) deltaTheta -= 2 * Math.PI
    if (sweepFlag == 1 && deltaTheta < 0) deltaTheta += 2 * Math.PI
    val segs = ceil(abs(deltaTheta) / (Math.PI / 2)).toInt().coerceAtLeast(1)
    val delta = deltaTheta / segs; var curTheta = theta1
    for (i in 0 until segs) {
        val nextTheta = curTheta + delta
        val cosCur = cos(curTheta); val sinCur = sin(curTheta); val cosNext = cos(nextTheta); val sinNext = sin(nextTheta)
        val e2x = cx + rx * cosNext * cosPhi - ry * sinNext * sinPhi; val e2y = cy + rx * cosNext * sinPhi + ry * sinNext * cosPhi
        val dx1 = -rx * sinCur; val dy1 = ry * cosCur; val dx1r = dx1 * cosPhi - dy1 * sinPhi; val dy1r = dx1 * sinPhi + dy1 * cosPhi
        val dx2 = -rx * sinNext; val dy2 = ry * cosNext; val dx2r = dx2 * cosPhi - dy2 * sinPhi; val dy2r = dx2 * sinPhi + dy2 * cosPhi
        val k = (4.0 / 3.0) * tan(delta / 4.0)
        val e1x = cx + rx * cosCur * cosPhi - ry * sinCur * sinPhi; val e1y = cy + rx * cosCur * sinPhi + ry * sinCur * cosPhi
        val c1x = e1x + k * dx1r; val c1y = e1y + k * dy1r; val c2x = e2x - k * dx2r; val c2y = e2y - k * dy2r
        path.cubicTo(c1x.toFloat(), c1y.toFloat(), c2x.toFloat(), c2y.toFloat(), e2x.toFloat(), e2y.toFloat())
        curTheta = nextTheta
    }
}
