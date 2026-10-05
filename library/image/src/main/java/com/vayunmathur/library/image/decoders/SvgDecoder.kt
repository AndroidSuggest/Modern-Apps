package com.vayunmathur.library.image.decoders

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import com.vayunmathur.library.image.ImageRequest
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
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
            if (isSvgReference(lower)) return true
        }
        return BitmapDecoder.isSvg(bytes)
    }

    private fun isSvgReference(lower: String): Boolean =
        lower.endsWith(SVG_EXTENSION) ||
            lower.contains(SVG_QUERY_MARKER) ||
            lower.contains(SVG_MIME_TYPE)

    suspend fun decode(bytes: ByteArray, request: ImageRequest): Bitmap? {
        return try {
            val svgString = decodeToString(bytes)
            val rootInfo = parseSvgRoot(svgString)
            val (outW, outH) = resolveOutputSize(request)

            val bitmap = createBitmap(outW, outH)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)
            renderSvgToCanvas(canvas, svgString, outW, outH, rootInfo)
            bitmap
        } catch (_: Exception) { null }
    }

    private fun decodeToString(bytes: ByteArray): String =
        try {
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }

    private fun resolveOutputSize(request: ImageRequest): Pair<Int, Int> {
        val reqSize = request.size
        if (reqSize != null && !reqSize.isOriginal()) {
            return reqSize.width to reqSize.height
        }
        return DEFAULT_OUTPUT_DIMENSION to DEFAULT_OUTPUT_DIMENSION
    }
}

private const val SVG_EXTENSION = ".svg"
private const val SVG_QUERY_MARKER = ".svg?"
private const val SVG_MIME_TYPE = "image/svg"
private const val DEFAULT_OUTPUT_DIMENSION = 512

// --- internal models ---

internal data class ViewBox(
    val minX: Float,
    val minY: Float,
    val width: Float,
    val height: Float,
)

internal data class RootInfo(
    val docWidth: Float,
    val docHeight: Float,
    val viewBox: ViewBox?,
)

internal data class EffectiveStyle(
    val fill: String?,
    val fillOpacity: Float,
    val stroke: String?,
    val strokeOpacity: Float,
    val strokeWidth: Float,
    val strokeLineCap: String,
    val strokeLineJoin: String,
    val strokeMiterLimit: Float,
    val fillRule: String,
    val display: String?,
    val visibility: String?,
    val effectiveOpacity: Float,
) {
    companion object {
        fun default() = EffectiveStyle(
            fill = DEFAULT_FILL,
            fillOpacity = FULL_OPACITY,
            stroke = null,
            strokeOpacity = FULL_OPACITY,
            strokeWidth = DEFAULT_STROKE_WIDTH,
            strokeLineCap = LINE_CAP_BUTT,
            strokeLineJoin = LINE_JOIN_MITER,
            strokeMiterLimit = DEFAULT_MITER_LIMIT,
            fillRule = FILL_RULE_NONZERO,
            display = null,
            visibility = null,
            effectiveOpacity = FULL_OPACITY,
        )
    }
}

internal const val DEFAULT_FILL = "black"
internal const val FULL_OPACITY = 1f
internal const val DEFAULT_STROKE_WIDTH = 1f
internal const val LINE_CAP_BUTT = "butt"
internal const val LINE_JOIN_MITER = "miter"
internal const val DEFAULT_MITER_LIMIT = 4f
internal const val FILL_RULE_NONZERO = "nonzero"

internal val leadingNumberRegex = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")
internal val numberRegex = Regex("""[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?""")

internal fun parseLength(v: String?): Float? {
    if (v == null) return null
    val text = v.trim()
    if (text.isEmpty()) return null
    return leadingNumberRegex.find(text)?.value?.toFloatOrNull()
}

internal fun parseDocLength(v: String?): Float? {
    if (v == null) return null
    val text = v.trim()
    if (text.isEmpty() || text.contains(PERCENT_SUFFIX)) return null
    return leadingNumberRegex.find(text)?.value?.toFloatOrNull()
}

internal const val PERCENT_SUFFIX = "%"

internal fun parseViewBox(vb: String?): ViewBox? {
    if (vb == null) return null
    val parts = vb.replace(',', ' ')
        .trim()
        .split(WHITESPACE_REGEX)
        .mapNotNull { it.toFloatOrNull() }
    val hasFourParts = parts.size == VIEW_BOX_PART_COUNT
    val hasPositiveSize = hasFourParts &&
        parts[VIEW_BOX_WIDTH_INDEX] > 0 &&
        parts[VIEW_BOX_HEIGHT_INDEX] > 0
    if (!hasPositiveSize) return null
    return ViewBox(
        minX = parts[VIEW_BOX_MIN_X_INDEX],
        minY = parts[VIEW_BOX_MIN_Y_INDEX],
        width = parts[VIEW_BOX_WIDTH_INDEX],
        height = parts[VIEW_BOX_HEIGHT_INDEX],
    )
}

internal val WHITESPACE_REGEX = Regex("\\s+")

internal const val VIEW_BOX_PART_COUNT = 4
internal const val VIEW_BOX_MIN_X_INDEX = 0
internal const val VIEW_BOX_MIN_Y_INDEX = 1
internal const val VIEW_BOX_WIDTH_INDEX = 2
internal const val VIEW_BOX_HEIGHT_INDEX = 3
internal fun parseNumbersList(s: String): List<Float> {
    if (s.isBlank()) return emptyList()
    return numberRegex.findAll(s).mapNotNull { it.value.toFloatOrNull() }.toList()
}
internal fun parseStyleAttribute(style: String): Map<String, String> {
    val declarations = mutableMapOf<String, String>()
    for (decl in style.split(STYLE_DECLARATION_SEPARATOR)) {
        parseStyleDeclaration(decl)?.let { declarations[it.first] = it.second }
    }
    return declarations
}

internal fun parseStyleDeclaration(decl: String): Pair<String, String>? {
    val text = decl.trim()
    if (text.isEmpty()) return null
    val separator = text.indexOf(STYLE_PROPERTY_SEPARATOR)
    if (separator <= 0) return null
    val prop = text.substring(0, separator).trim().lowercase()
    val value = text.substring(separator + 1).trim()
    if (prop.isEmpty() || value.isEmpty()) return null
    return prop to value
}

internal const val STYLE_DECLARATION_SEPARATOR = ';'
internal const val STYLE_PROPERTY_SEPARATOR = ':'
internal fun parseTransform(tr: String?): Matrix? {
    if (tr.isNullOrBlank()) return null
    val result = Matrix()
    result.reset()
    val operations = TRANSFORM_FUNCTION_REGEX.findAll(tr)
        .mapNotNull { parseTransformOperation(it) }
        .toList()
    if (operations.isEmpty()) return null
    for (operation in operations) {
        result.postConcat(operation)
    }
    return result
}

internal val TRANSFORM_FUNCTION_REGEX = Regex("""([a-zA-Z]+)\s*\(([^)]*)\)""")

internal fun parseTransformOperation(match: MatchResult): Matrix? {
    val name = match.groupValues[TRANSFORM_NAME_GROUP].trim().lowercase()
    val args = parseNumbersList(match.groupValues[TRANSFORM_ARGS_GROUP])
    val local = Matrix()
    val applied = applyTransformOperation(local, name, args)
    if (!applied) return null
    return local
}

internal const val TRANSFORM_NAME_GROUP = 1
internal const val TRANSFORM_ARGS_GROUP = 2

internal fun applyTransformOperation(local: Matrix, name: String, args: List<Float>): Boolean =
    when (name) {
        TRANSFORM_MATRIX -> applyMatrixTransform(local, args)
        TRANSFORM_TRANSLATE -> applyTranslateTransform(local, args)
        TRANSFORM_SCALE -> applyScaleTransform(local, args)
        TRANSFORM_ROTATE -> applyRotateTransform(local, args)
        TRANSFORM_SKEW_X -> applySkewTransform(local, args, horizontal = true)
        TRANSFORM_SKEW_Y -> applySkewTransform(local, args, horizontal = false)
        else -> false
    }

internal const val TRANSFORM_MATRIX = "matrix"
internal const val TRANSFORM_TRANSLATE = "translate"
internal const val TRANSFORM_SCALE = "scale"
internal const val TRANSFORM_ROTATE = "rotate"
internal const val TRANSFORM_SKEW_X = "skewx"
internal const val TRANSFORM_SKEW_Y = "skewy"

internal fun applyMatrixTransform(local: Matrix, args: List<Float>): Boolean {
    if (args.size < MIN_MATRIX_ARGS) return false
    local.setValues(
        floatArrayOf(
            args[MATRIX_A_INDEX],
            args[MATRIX_C_INDEX],
            args[MATRIX_E_INDEX],
            args[MATRIX_B_INDEX],
            args[MATRIX_D_INDEX],
            args[MATRIX_F_INDEX],
            MATRIX_PERSPECTIVE_ZERO,
            MATRIX_PERSPECTIVE_ZERO,
            MATRIX_PERSPECTIVE_ONE,
        ),
    )
    return true
}

internal const val MIN_MATRIX_ARGS = 6
internal const val MATRIX_A_INDEX = 0
internal const val MATRIX_B_INDEX = 1
internal const val MATRIX_C_INDEX = 2
internal const val MATRIX_D_INDEX = 3
internal const val MATRIX_E_INDEX = 4
internal const val MATRIX_F_INDEX = 5
internal const val MATRIX_PERSPECTIVE_ZERO = 0f
internal const val MATRIX_PERSPECTIVE_ONE = 1f

internal fun applyTranslateTransform(local: Matrix, args: List<Float>): Boolean {
    if (args.size >= MIN_PAIRED_ARGS) {
        local.setTranslate(args[FIRST_ARG_INDEX], args[SECOND_ARG_INDEX])
        return true
    }
    if (args.size == SINGLE_ARG_COUNT) {
        local.setTranslate(args[FIRST_ARG_INDEX], NO_OFFSET)
        return true
    }
    return false
}

internal fun applyScaleTransform(local: Matrix, args: List<Float>): Boolean {
    if (args.size >= MIN_PAIRED_ARGS) {
        local.setScale(args[FIRST_ARG_INDEX], args[SECOND_ARG_INDEX])
        return true
    }
    if (args.size == SINGLE_ARG_COUNT) {
        local.setScale(args[FIRST_ARG_INDEX], args[FIRST_ARG_INDEX])
        return true
    }
    return false
}

internal fun applyRotateTransform(local: Matrix, args: List<Float>): Boolean {
    if (args.size >= MIN_ROTATE_CENTER_ARGS) {
        local.setRotate(
            args[FIRST_ARG_INDEX],
            args[SECOND_ARG_INDEX],
            args[THIRD_ARG_INDEX],
        )
        return true
    }
    if (args.size == SINGLE_ARG_COUNT) {
        local.setRotate(args[FIRST_ARG_INDEX])
        return true
    }
    return false
}

internal const val MIN_PAIRED_ARGS = 2
internal const val SINGLE_ARG_COUNT = 1
internal const val MIN_ROTATE_CENTER_ARGS = 3
internal const val FIRST_ARG_INDEX = 0
internal const val SECOND_ARG_INDEX = 1
internal const val THIRD_ARG_INDEX = 2
internal const val NO_OFFSET = 0f

internal fun applySkewTransform(local: Matrix, args: List<Float>, horizontal: Boolean): Boolean {
    if (args.isEmpty()) return false
    val tangent = tan(Math.toRadians(args[FIRST_ARG_INDEX].toDouble())).toFloat()
    if (horizontal) {
        local.setSkew(tangent, NO_SKEW)
    } else {
        local.setSkew(NO_SKEW, tangent)
    }
    return true
}

internal const val NO_SKEW = 0f
internal fun computeEffective(parent: EffectiveStyle, attrs: Map<String, String>): EffectiveStyle {
    val fill = resolvePaint(attrs[ATTR_FILL], parent.fill)
    val stroke = resolvePaint(attrs[ATTR_STROKE], parent.stroke)
    val fillOpacity = attrs[ATTR_FILL_OPACITY]?.toFloatOrNull() ?: parent.fillOpacity
    val strokeOpacity = attrs[ATTR_STROKE_OPACITY]?.toFloatOrNull() ?: parent.strokeOpacity
    val strokeWidth = attrs[ATTR_STROKE_WIDTH]?.let { parseLength(it) } ?: parent.strokeWidth
    val strokeLineCap = attrs[ATTR_STROKE_LINE_CAP] ?: parent.strokeLineCap
    val strokeLineJoin = attrs[ATTR_STROKE_LINE_JOIN] ?: parent.strokeLineJoin
    val strokeMiterLimit = attrs[ATTR_STROKE_MITER_LIMIT]?.toFloatOrNull()
        ?: parent.strokeMiterLimit
    val fillRule = attrs[ATTR_FILL_RULE] ?: parent.fillRule
    val display = attrs[ATTR_DISPLAY]
    val visibility = attrs[ATTR_VISIBILITY]
    val localOpacity = attrs[ATTR_OPACITY]?.toFloatOrNull() ?: FULL_OPACITY
    val effective = (parent.effectiveOpacity * localOpacity).coerceIn(NO_OPACITY, FULL_OPACITY)
    return EffectiveStyle(
        fill = fill,
        fillOpacity = fillOpacity.coerceIn(NO_OPACITY, FULL_OPACITY),
        stroke = stroke,
        strokeOpacity = strokeOpacity.coerceIn(NO_OPACITY, FULL_OPACITY),
        strokeWidth = strokeWidth,
        strokeLineCap = strokeLineCap,
        strokeLineJoin = strokeLineJoin,
        strokeMiterLimit = strokeMiterLimit,
        fillRule = fillRule,
        display = display,
        visibility = visibility,
        effectiveOpacity = effective,
    )
}

internal const val ATTR_FILL = "fill"
internal const val ATTR_STROKE = "stroke"
internal const val ATTR_FILL_OPACITY = "fill-opacity"
internal const val ATTR_STROKE_OPACITY = "stroke-opacity"
internal const val ATTR_STROKE_WIDTH = "stroke-width"
internal const val ATTR_STROKE_LINE_CAP = "stroke-linecap"
internal const val ATTR_STROKE_LINE_JOIN = "stroke-linejoin"
internal const val ATTR_STROKE_MITER_LIMIT = "stroke-miterlimit"
internal const val ATTR_FILL_RULE = "fill-rule"
internal const val ATTR_DISPLAY = "display"
internal const val ATTR_VISIBILITY = "visibility"
internal const val ATTR_OPACITY = "opacity"

internal fun resolvePaint(raw: String?, inherited: String?): String? {
    if (raw == null) return inherited
    if (raw.equals(PAINT_NONE, ignoreCase = true)) return null
    if (raw.equals(PAINT_INHERIT, ignoreCase = true)) return inherited
    if (raw.equals(PAINT_CURRENT_COLOR, ignoreCase = true)) return inherited
    return raw
}

internal const val PAINT_NONE = "none"
internal const val PAINT_INHERIT = "inherit"
internal const val PAINT_CURRENT_COLOR = "currentcolor"
internal fun drawPathWithStyle(canvas: Canvas, path: Path, style: EffectiveStyle) {
    if (path.isEmpty) return
    path.fillType = resolveFillType(style.fillRule)
    drawFill(canvas, path, style)
    drawStroke(canvas, path, style)
}

internal fun resolveFillType(fillRule: String): Path.FillType =
    if (fillRule.equals(FILL_RULE_EVEN_ODD, ignoreCase = true)) {
        Path.FillType.EVEN_ODD
    } else {
        Path.FillType.WINDING
    }

internal const val FILL_RULE_EVEN_ODD = "evenodd"

internal fun drawFill(canvas: Canvas, path: Path, style: EffectiveStyle) {
    val paintSpec = style.fill ?: return
    val color = styledColor(paintSpec, style.fillOpacity, style.effectiveOpacity) ?: return
    val paint = Paint().apply {
        isAntiAlias = true
        this.style = Paint.Style.FILL
        this.color = color
    }
    canvas.drawPath(path, paint)
}

internal fun drawStroke(canvas: Canvas, path: Path, style: EffectiveStyle) {
    val paintSpec = style.stroke ?: return
    if (style.strokeWidth <= MIN_VISIBLE_STROKE_WIDTH) return
    val color = styledColor(paintSpec, style.strokeOpacity, style.effectiveOpacity) ?: return
    val paint = Paint().apply {
        isAntiAlias = true
        this.style = Paint.Style.STROKE
        this.color = color
        strokeWidth = style.strokeWidth
        strokeCap = resolveLineCap(style.strokeLineCap)
        strokeJoin = resolveLineJoin(style.strokeLineJoin)
        strokeMiter = style.strokeMiterLimit
    }
    canvas.drawPath(path, paint)
}

internal const val MIN_VISIBLE_STROKE_WIDTH = 0.001f

internal fun resolveLineCap(lineCap: String): Paint.Cap =
    when (lineCap.lowercase()) {
        LINE_CAP_ROUND -> Paint.Cap.ROUND
        LINE_CAP_SQUARE -> Paint.Cap.SQUARE
        else -> Paint.Cap.BUTT
    }

internal const val LINE_CAP_ROUND = "round"
internal const val LINE_CAP_SQUARE = "square"

internal fun resolveLineJoin(lineJoin: String): Paint.Join =
    when (lineJoin.lowercase()) {
        LINE_JOIN_ROUND -> Paint.Join.ROUND
        LINE_JOIN_BEVEL -> Paint.Join.BEVEL
        else -> Paint.Join.MITER
    }

internal const val LINE_JOIN_ROUND = "round"
internal const val LINE_JOIN_BEVEL = "bevel"

internal fun styledColor(paintSpec: String, opacity: Float, effectiveOpacity: Float): Int? {
    val resolved = parseColorString(paintSpec) ?: Color.BLACK
    val baseAlpha = Color.alpha(resolved) / MAX_CHANNEL_VALUE
    val finalAlpha = (baseAlpha * opacity * effectiveOpacity * MAX_CHANNEL_VALUE)
        .toInt()
        .coerceIn(MIN_CHANNEL_VALUE, MAX_CHANNEL_VALUE)
    if (finalAlpha <= NO_ALPHA) return null
    return (finalAlpha shl ALPHA_SHIFT) or (resolved and RGB_MASK)
}

internal const val NO_ALPHA = 0
internal const val ALPHA_SHIFT = 24
internal const val RGB_MASK = 0x00FFFFFF
internal fun parseSvgRoot(svgContent: String): RootInfo =
    runCatching { findSvgRoot(svgContent) }.getOrNull() ?: RootInfo(EMPTY_DIMENSION, EMPTY_DIMENSION, null)

internal const val EMPTY_DIMENSION = 0f

internal fun findSvgRoot(svgContent: String): RootInfo? {
    val parser = newXmlParser()
    parser.setInput(StringReader(svgContent))
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (isSvgStartTag(parser, event)) {
            return readRootInfo(parser)
        }
        event = parser.next()
    }
    return null
}

internal fun newXmlParser(): XmlPullParser {
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = false
    val parser = factory.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    return parser
}

internal fun isSvgStartTag(parser: XmlPullParser, event: Int): Boolean =
    event == XmlPullParser.START_TAG && parser.name.equals(TAG_SVG, ignoreCase = true)

internal const val TAG_SVG = "svg"

internal fun readRootInfo(parser: XmlPullParser): RootInfo {
    var docWidth = EMPTY_DIMENSION
    var docHeight = EMPTY_DIMENSION
    var viewBox: ViewBox? = null
    for (index in 0 until parser.attributeCount) {
        val name = parser.getAttributeName(index)?.lowercase()
        val value = parser.getAttributeValue(index) ?: continue
        when (name) {
            ATTR_WIDTH -> parseDocLength(value)?.let { docWidth = it }
            ATTR_HEIGHT -> parseDocLength(value)?.let { docHeight = it }
            ATTR_VIEW_BOX -> viewBox = parseViewBox(value)
        }
    }
    return fillMissingDimensions(docWidth, docHeight, viewBox)
}

internal const val ATTR_WIDTH = "width"
internal const val ATTR_HEIGHT = "height"
internal const val ATTR_VIEW_BOX = "viewbox"

internal fun fillMissingDimensions(docWidth: Float, docHeight: Float, viewBox: ViewBox?): RootInfo {
    var width = docWidth
    var height = docHeight
    if ((width <= 0 || height <= 0) && viewBox != null) {
        if (width <= 0) width = viewBox.width
        if (height <= 0) height = viewBox.height
    }
    return RootInfo(width, height, viewBox)
}
