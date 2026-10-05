package com.vayunmathur.library.image.decoders

import android.graphics.Canvas
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import kotlin.math.abs

internal fun renderSvgToCanvas(
    canvas: Canvas,
    svgContent: String,
    outW: Int,
    outH: Int,
    rootInfo: RootInfo,
) {
    val parser = newXmlParser()
    parser.setInput(StringReader(svgContent))
    val state = RenderState()
    applyRootViewport(canvas, state, outW, outH, rootInfo)
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        eventType = dispatchEvent(parser, eventType, canvas, state)
    }
    canvas.restoreToCount(state.baseSave)
}

internal class RenderState(
    val styleStack: ArrayDeque<EffectiveStyle> = ArrayDeque(),
    val canvasSaveStack: ArrayDeque<Int> = ArrayDeque(),
    var currentStyle: EffectiveStyle = EffectiveStyle.default(),
    var skipDepth: Int? = null,
    var baseSave: Int = 0,
)

internal fun applyRootViewport(
    canvas: Canvas,
    state: RenderState,
    outW: Int,
    outH: Int,
    rootInfo: RootInfo,
) {
    state.baseSave = canvas.save()
    val viewBox = rootInfo.viewBox
    if (viewBox != null) {
        fitViewBox(canvas, outW, outH, viewBox)
    } else if (rootInfo.docWidth > 0 && rootInfo.docHeight > 0) {
        fitDocumentSize(canvas, outW, outH, rootInfo)
    }
}

internal fun fitViewBox(canvas: Canvas, outW: Int, outH: Int, viewBox: ViewBox) {
    val scaleX = outW / viewBox.width
    val scaleY = outH / viewBox.height
    val scale = minOf(scaleX, scaleY)
    val scaledW = viewBox.width * scale
    val scaledH = viewBox.height * scale
    val translateX = (outW - scaledW) / CENTER_DIVISOR
    val translateY = (outH - scaledH) / CENTER_DIVISOR
    canvas.translate(translateX, translateY)
    canvas.scale(scale, scale)
    canvas.translate(-viewBox.minX, -viewBox.minY)
}

internal const val CENTER_DIVISOR = 2f

internal fun fitDocumentSize(canvas: Canvas, outW: Int, outH: Int, rootInfo: RootInfo) {
    val scaleX = outW / rootInfo.docWidth
    val scaleY = outH / rootInfo.docHeight
    val needsScale = abs(scaleX - IDENTITY_SCALE) > SCALE_EPSILON ||
        abs(scaleY - IDENTITY_SCALE) > SCALE_EPSILON
    if (needsScale) {
        canvas.scale(scaleX, scaleY)
    }
}

internal const val IDENTITY_SCALE = 1f
internal const val SCALE_EPSILON = 0.001f

internal fun dispatchEvent(
    parser: XmlPullParser,
    eventType: Int,
    canvas: Canvas,
    state: RenderState,
): Int =
    when (eventType) {
        XmlPullParser.START_TAG -> handleStartTag(parser, canvas, state)
        XmlPullParser.END_TAG -> handleEndTag(parser, canvas, state)
        else -> advance(parser)
    }

internal fun advance(parser: XmlPullParser): Int = parser.next()

internal fun handleStartTag(parser: XmlPullParser, canvas: Canvas, state: RenderState): Int {
    val depth = parser.depth
    val skipAt = state.skipDepth
    if (skipAt != null && depth > skipAt) return advance(parser)
    val tagName = parser.name?.lowercase().orEmpty()
    if (tagName in NON_RENDERING_TAGS) {
        state.skipDepth = depth
        return advance(parser)
    }
    val attrs = readAttributes(parser)
    val visibility = startTagVisibility(attrs, depth, state) ?: return advance(parser)
    pushStyle(canvas, state, attrs)
    applyNestedSvgViewport(canvas, tagName, depth, attrs)
    runCatching { drawElement(canvas, tagName, attrs, state.currentStyle) }
    return advance(parser)
}

internal val NON_RENDERING_TAGS = setOf(
    "defs",
    "clippath",
    "mask",
    "pattern",
    "filter",
    "style",
    "script",
    "title",
    "desc",
    "metadata",
    "lineargradient",
    "radialgradient",
    "stop",
    "image",
)

internal fun readAttributes(parser: XmlPullParser): MutableMap<String, String> {
    val attrs = mutableMapOf<String, String>()
    for (index in 0 until parser.attributeCount) {
        readAttribute(parser, index)?.let { attrs[it.first] = it.second }
    }
    return attrs
}

internal fun readAttribute(parser: XmlPullParser, index: Int): Pair<String, String>? {
    val name = parser.getAttributeName(index)?.lowercase() ?: return null
    val value = parser.getAttributeValue(index) ?: return null
    return name to value
}

internal fun startTagVisibility(
    attrs: MutableMap<String, String>,
    depth: Int,
    state: RenderState,
): EffectiveStyle? {
    mergeInlineStyle(attrs)
    if (isDisplayNone(attrs) || isCollapsed(attrs)) {
        state.skipDepth = depth
        return null
    }
    val effective = computeEffective(parent = state.currentStyle, attrs = attrs)
    if (effective.effectiveOpacity <= MIN_VISIBLE_OPACITY) {
        state.skipDepth = depth
        return null
    }
    return effective
}

internal fun mergeInlineStyle(attrs: MutableMap<String, String>) {
    val styleAttr = attrs[ATTR_STYLE] ?: return
    if (styleAttr.isBlank()) return
    val declarations = parseStyleAttribute(styleAttr)
    for ((key, value) in declarations) {
        attrs[key] = value
    }
    attrs.remove(ATTR_STYLE)
}

internal const val ATTR_STYLE = "style"

internal fun isDisplayNone(attrs: Map<String, String>): Boolean =
    attrs[ATTR_DISPLAY]?.trim()?.lowercase() == DISPLAY_NONE

internal const val DISPLAY_NONE = "none"

internal fun isCollapsed(attrs: Map<String, String>): Boolean {
    val visibility = attrs[ATTR_VISIBILITY]?.trim()?.lowercase() ?: return false
    return visibility == VISIBILITY_HIDDEN || visibility == VISIBILITY_COLLAPSE
}

internal const val VISIBILITY_HIDDEN = "hidden"
internal const val VISIBILITY_COLLAPSE = "collapse"

internal const val MIN_VISIBLE_OPACITY = 0.01f

internal fun pushStyle(canvas: Canvas, state: RenderState, attrs: Map<String, String>) {
    val effective = computeEffective(parent = state.currentStyle, attrs = attrs)
    state.canvasSaveStack.addLast(canvas.save())
    state.styleStack.addLast(state.currentStyle)
    state.currentStyle = effective
    parseTransform(attrs[ATTR_TRANSFORM])?.let { canvas.concat(it) }
}

internal const val ATTR_TRANSFORM = "transform"

internal fun applyNestedSvgViewport(canvas: Canvas, tagName: String, depth: Int, attrs: Map<String, String>) {
    if (tagName != TAG_SVG || depth <= ROOT_SVG_DEPTH) return
    val offsetX = parseLength(attrs[ATTR_X]) ?: NO_OFFSET
    val offsetY = parseLength(attrs[ATTR_Y]) ?: NO_OFFSET
    if (offsetX != NO_OFFSET || offsetY != NO_OFFSET) {
        canvas.translate(offsetX, offsetY)
    }
    applyNestedViewBox(canvas, attrs)
}

internal fun applyNestedViewBox(canvas: Canvas, attrs: Map<String, String>) {
    val nestedBox = parseViewBox(attrs[ATTR_VIEW_BOX]) ?: return
    val nestedSize = nestedExplicitSize(attrs)
    if (nestedSize != null) {
        fitNestedViewBox(canvas, nestedBox, nestedSize.first, nestedSize.second)
    } else {
        canvas.translate(-nestedBox.minX, -nestedBox.minY)
    }
}

internal fun nestedExplicitSize(attrs: Map<String, String>): Pair<Float, Float>? {
    val nestedWidth = parseLength(attrs[ATTR_WIDTH]) ?: return null
    val nestedHeight = parseLength(attrs[ATTR_HEIGHT]) ?: return null
    if (nestedWidth <= 0 || nestedHeight <= 0) return null
    return nestedWidth to nestedHeight
}

internal const val ROOT_SVG_DEPTH = 1
internal const val ATTR_X = "x"
internal const val ATTR_Y = "y"

internal fun fitNestedViewBox(canvas: Canvas, box: ViewBox, width: Float, height: Float) {
    val scaleX = width / box.width
    val scaleY = height / box.height
    val scale = minOf(scaleX, scaleY)
    val scaledW = box.width * scale
    val scaledH = box.height * scale
    val translateX = (width - scaledW) / CENTER_DIVISOR
    val translateY = (height - scaledH) / CENTER_DIVISOR
    canvas.translate(translateX, translateY)
    canvas.scale(scale, scale)
    canvas.translate(-box.minX, -box.minY)
}

internal fun handleEndTag(parser: XmlPullParser, canvas: Canvas, state: RenderState): Int {
    val depth = parser.depth
    if (state.skipDepth != null) {
        if (depth == state.skipDepth) state.skipDepth = null
        return advance(parser)
    }
    if (state.canvasSaveStack.isNotEmpty()) {
        val saveCount = state.canvasSaveStack.removeLast()
        canvas.restoreToCount(saveCount)
    }
    if (state.styleStack.isNotEmpty()) {
        state.currentStyle = state.styleStack.removeLast()
    }
    return advance(parser)
}

