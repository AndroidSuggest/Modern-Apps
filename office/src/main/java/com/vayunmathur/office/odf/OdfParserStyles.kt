package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.OdfBorders
import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfNumberToken
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfTabStop
import org.xmlpull.v1.XmlPullParser

// --- Style parsing ---

private const val DEFAULT_FONT_PT = 12f
private const val PERCENT_DIVISOR = 100f
private const val PX_PER_INCH = 96f
private const val PT_PER_INCH = 72f

internal data class StyleInfo(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontSize: Float? = null,
    val fontFamily: String? = null,
    val parentStyle: String? = null,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val color: Long? = null,
    val backgroundColor: Long? = null,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val textAlign: TextAlign? = null,
    val marginLeft: Float = 0f,
    val marginTop: Float = 0f,
    val marginBottom: Float = 0f,
    // Nullable so an explicit fo:text-indent="0" (override the parent to no indent) is distinct
    // from "unspecified" (inherit the parent). Matches LibreOffice; avoids phantom first-line indents.
    val textIndent: Float? = null,
    val paragraphBackgroundColor: Long? = null,
    val breakBefore: String? = null,
    val breakAfter: String? = null,
    val drawFillColor: Long? = null,
    val drawStrokeColor: Long? = null,
    val drawStrokeWidth: Float? = null,
    val cellBackgroundColor: Long? = null,
    val cellBorderColor: Long? = null,
    val writingMode: String? = null,
    val columnWidth: Float? = null,
    val lineHeightPercent: Float? = null,
    val paragraphBorderColor: Long? = null,
    val tabStops: List<Float> = emptyList(),
    val dataStyleName: String? = null,
    val cellWrap: Boolean = false,
    val clip: String? = null,
    val cellBorders: OdfBorders? = null,
    val paraBorders: OdfBorders? = null,
    val underlineStyle: String? = null,
    val underlineColor: Long? = null,
    val letterSpacing: Float? = null,
    val textTransform: String? = null,
    val language: String? = null,
    val country: String? = null,
    val marginRight: Float = 0f,
    val keepWithNext: Boolean = false,
    val keepTogether: Boolean = false,
    val widows: Int? = null,
    val orphans: Int? = null,
    val rowHeight: Float? = null,
    val imageOpacity: Float? = null,
    val imageColorMode: String? = null,
    val transitionType: String? = null,
    val transitionSpeed: String? = null,
    val conditionalMaps: List<Pair<String, String>> = emptyList(),
    val cellVerticalAlign: String? = null,
    val fillGradientName: String? = null,
    val columnCount: Int = 1,
    val strokeDashed: Boolean = false,
    val markerStart: Boolean = false,
    val markerEnd: Boolean = false,
    val tabStopDetails: List<OdfTabStop> = emptyList(),
    val dropCapLines: Int = 0,
    val dropCapLength: Int = 1,
    val padding: Float = 0f
)

internal fun OdfParser.parseStyles(xml: String): Map<String, StyleInfo> {
    val styles = mutableMapOf<String, StyleInfo>()
    val parser = newParser(xml)
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (isTag(eventType, parser, "style", "style")) {
            val styleName = getAttr(parser, "name")
            val parentStyle = getAttr(parser, "parent-style-name")
            val dataStyle = getAttr(parser, "data-style-name")
            if (styleName != null) {
                styles[styleName] = parseStyleProperties(parser, parentStyle).copy(dataStyleName = dataStyle)
            }
        }
        eventType = parser.next()
    }
    return styles
}

internal fun OdfParser.parseStyleProperties(parser: XmlPullParser, parentStyle: String?): StyleInfo {
    val acc = StyleAcc()
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) {
            applyStyleTag(parser, acc)
        }
        eventType = parser.next()
    }
    return StyleInfo(
        acc.bold, acc.italic, acc.fontSize, acc.fontFamily, parentStyle,
        acc.underline, acc.strikethrough, acc.color, acc.bgColor, acc.superscript, acc.subscript,
        acc.textAlign, acc.marginLeft, acc.marginTop, acc.marginBottom, acc.textIndent, acc.paraBgColor,
        acc.breakBefore, acc.breakAfter, acc.drawFillColor, acc.drawStrokeColor, acc.drawStrokeWidth,
        acc.cellBgColor, acc.cellBorderColor, acc.writingMode, acc.columnWidth,
        acc.lineHeightPercent, acc.paraBorderColor, acc.tabStops, null, acc.cellWrap, acc.clipVal,
        buildBorders(acc.cellBorderAll, acc.cellBorderT, acc.cellBorderR, acc.cellBorderB, acc.cellBorderL),
        buildBorders(acc.paraBorderAll, acc.paraBorderT, acc.paraBorderR, acc.paraBorderB, acc.paraBorderL),
        acc.underlineStyle, acc.underlineColor, acc.letterSpacing, acc.textTransform, acc.language, acc.country,
        acc.marginRight, acc.keepWithNext, acc.keepTogether, acc.widows, acc.orphans, acc.rowHeight,
        acc.imageOpacity,
        acc.imageColorMode, acc.transitionType, acc.transitionSpeed, acc.conditionalMaps, acc.cellVerticalAlign,
        acc.fillGradientName, acc.columnCount, acc.strokeDashed, acc.markerStart, acc.markerEnd,
        acc.tabStopDetails, acc.dropCapLines, acc.dropCapLength, acc.padding
    )
}

/** Style-property accumulation state. */
internal class StyleAcc(
    var bold: Boolean = false,
    var italic: Boolean = false,
    var fontSize: Float? = null,
    var fontFamily: String? = null,
    var underline: Boolean = false,
    var strikethrough: Boolean = false,
    var color: Long? = null,
    var bgColor: Long? = null,
    var superscript: Boolean = false,
    var subscript: Boolean = false,
    var textAlign: TextAlign? = null,
    var marginLeft: Float = 0f,
    var marginTop: Float = 0f,
    var marginBottom: Float = 0f,
    var textIndent: Float? = null,
    var marginRight: Float = 0f,
    var keepWithNext: Boolean = false,
    var keepTogether: Boolean = false,
    var widows: Int? = null,
    var orphans: Int? = null,
    var paraBgColor: Long? = null,
    var breakBefore: String? = null,
    var breakAfter: String? = null,
    var drawFillColor: Long? = null,
    var drawStrokeColor: Long? = null,
    var drawStrokeWidth: Float? = null,
    var cellBgColor: Long? = null,
    var cellBorderColor: Long? = null,
    var cellWrap: Boolean = false,
    var cellVerticalAlign: String? = null,
    var writingMode: String? = null,
    var columnWidth: Float? = null,
    var rowHeight: Float? = null,
    var lineHeightPercent: Float? = null,
    var paraBorderColor: Long? = null,
    val tabStops: MutableList<Float> = mutableListOf(),
    val tabStopDetails: MutableList<OdfTabStop> = mutableListOf(),
    var dropCapLines: Int = 0,
    var dropCapLength: Int = 1,
    var padding: Float = 0f,
    var clipVal: String? = null,
    var imageOpacity: Float? = null,
    var imageColorMode: String? = null,
    var fillGradientName: String? = null,
    var columnCount: Int = 1,
    var strokeDashed: Boolean = false,
    var markerStart: Boolean = false,
    var markerEnd: Boolean = false,
    var transitionType: String? = null,
    var transitionSpeed: String? = null,
    val conditionalMaps: MutableList<Pair<String, String>> = mutableListOf(),
    var paraBorderAll: String? = null,
    var paraBorderT: String? = null,
    var paraBorderR: String? = null,
    var paraBorderB: String? = null,
    var paraBorderL: String? = null,
    var cellBorderAll: String? = null,
    var cellBorderT: String? = null,
    var cellBorderR: String? = null,
    var cellBorderB: String? = null,
    var cellBorderL: String? = null,
    var underlineStyle: String? = null,
    var underlineColor: Long? = null,
    var letterSpacing: Float? = null,
    var textTransform: String? = null,
    var language: String? = null,
    var country: String? = null,
)

/** Apply one style child tag. */
private fun OdfParser.applyStyleTag(parser: XmlPullParser, acc: StyleAcc) {
    applyStyleTagCore(parser, acc)
    applyStyleTagTable(parser, acc)
}

/** Core style tags (text/paragraph/drawing). */
private fun OdfParser.applyStyleTagCore(parser: XmlPullParser, acc: StyleAcc) {
    when (parser.name) {
        "text-properties" -> applyTextProperties(parser, acc)
        "paragraph-properties" -> applyParagraphProperties(parser, acc)
        "tab-stop" -> applyTabStop(parser, acc)
        "drop-cap" -> {
            acc.dropCapLines = getAttr(parser, "lines")?.toIntOrNull() ?: 0
            acc.dropCapLength = getAttr(parser, "length")?.toIntOrNull() ?: 1
        }
        "drawing-page-properties" -> applyDrawingPageProperties(parser, acc)
        "graphic-properties" -> applyGraphicProperties(parser, acc)
    }
}

/** Table/map/columns style tags. */
private fun OdfParser.applyStyleTagTable(parser: XmlPullParser, acc: StyleAcc) {
    when (parser.name) {
        "map" -> {
            val cond = getAttr(parser, "condition")
            val applyStyle = getAttr(parser, "apply-style-name")
            if (cond != null && applyStyle != null) acc.conditionalMaps.add(cond to applyStyle)
        }
        "columns" -> getAttr(parser, "column-count")?.toIntOrNull()?.let { acc.columnCount = it }
        "table-cell-properties" -> applyTableCellProperties(parser, acc)
        "table-column-properties" -> {
            getAttr(parser, "column-width")?.let { acc.columnWidth = parseDimension(it) }
        }
        "table-row-properties" -> {
            getAttr(parser, "row-height")?.let { acc.rowHeight = parseDimension(it) }
        }
    }
}

/** text-properties properties. */
private fun OdfParser.applyTextProperties(parser: XmlPullParser, acc: StyleAcc) {
    applyTextFontAttrs(parser, acc)
    applyTextDecorAttrs(parser, acc)
    applyTextPositionAttrs(parser, acc)
}

/** Font weight/style/size/family. */
private fun OdfParser.applyTextFontAttrs(parser: XmlPullParser, acc: StyleAcc) {
    if (getAttr(parser, "font-weight") == "bold") acc.bold = true
    if (getAttr(parser, "font-style") == "italic") acc.italic = true
    getAttr(parser, "font-size")?.let { acc.fontSize = parseFontSize(it) }
    getAttr(parser, "font-name")?.let { acc.fontFamily = it }
    if (acc.fontFamily == null) getAttr(parser, "font-family")?.let { acc.fontFamily = it }
}

private fun OdfParser.parseFontSize(s: String): Float? {
    return when {
        s.endsWith("pt") || s.endsWith("px") -> s.dropLast(2).toFloatOrNull()
        // Percentage font sizes are relative to a 12pt default.
        s.endsWith("%") -> s.dropLast(1).toFloatOrNull()?.let {
            it / PERCENT_DIVISOR * DEFAULT_FONT_PT
        }
        s.endsWith("cm") || s.endsWith("mm") || s.endsWith("in") || s.endsWith("pc") ->
            parseDimension(s).takeIf { it > 0f }?.let {
                it / (PX_PER_INCH / PT_PER_INCH)
            } // px@96 -> pt
        else -> s.toFloatOrNull()
    }
}

/** Underline/strike/color/background/letter-spacing/transform/language. */
private fun OdfParser.applyTextDecorAttrs(parser: XmlPullParser, acc: StyleAcc) {
    applyTextUnderlineAttrs(parser, acc)
    applyTextColorLangAttrs(parser, acc)
}

private fun OdfParser.applyTextUnderlineAttrs(parser: XmlPullParser, acc: StyleAcc) {
    getAttr(
        parser,
        "text-underline-style")?.let { if (it != "none") { acc.underline = true; acc.underlineStyle = it } }
    getAttr(
        parser,
        "text-underline-color")?.let { if (it != "font-color") acc.underlineColor = parseColor(it) }
    getAttr(parser, "text-line-through-style")?.let { if (it != "none") acc.strikethrough = true }
    getAttr(parser, "letter-spacing")?.let { ls ->
        if (ls != "normal") {
            acc.letterSpacing = ls.replace("pt", "").replace("cm", "").toFloatOrNull()
        }
    }
    getAttr(parser, "text-transform")?.let { if (it != "none") acc.textTransform = it }
    if (acc.textTransform == null && getAttr(
        parser,
        "font-variant") == "small-caps") acc.textTransform = "uppercase"
}

private fun OdfParser.applyTextColorLangAttrs(parser: XmlPullParser, acc: StyleAcc) {
    getAttr(parser, "language")?.let { acc.language = it }
    getAttr(parser, "country")?.let { acc.country = it }
    getAttr(parser, "color")?.let { acc.color = parseColor(it) }
    getAttr(parser, "background-color")?.let { if (it != "transparent") acc.bgColor = parseColor(it) }
}

/** Superscript/subscript from text-position. */
private fun OdfParser.applyTextPositionAttrs(parser: XmlPullParser, acc: StyleAcc) {
    getAttr(parser, "text-position")?.let { tp ->
        // Format is "<vertical-shift>% <height>%"; a 0% shift is
        val shift = tp.substringBefore(' ').removeSuffix("%").trim().toFloatOrNull()
        when {
            tp.startsWith("super") -> acc.superscript = true
            tp.startsWith("sub") -> acc.subscript = true
            shift != null && shift > 0f -> acc.superscript = true
            shift != null && shift < 0f -> acc.subscript = true
        }
    }
}

/** paragraph-properties properties. */
private fun OdfParser.applyParagraphProperties(parser: XmlPullParser, acc: StyleAcc) {
    applyParaAlignMargins(parser, acc)
    applyParaKeepWidows(parser, acc)
    applyParaBordersBreaks(parser, acc)
    applyParaLineHeightWriting(parser, acc)
}

/** Alignment + margins + indent + padding. */
private fun OdfParser.applyParaAlignMargins(parser: XmlPullParser, acc: StyleAcc) {
    acc.textAlign = readParaAlign(parser)
    applyParaMargins(parser, acc)
    applyParaPadding(parser, acc)
}

private fun OdfParser.readParaAlign(parser: XmlPullParser): TextAlign? {
    return when (getAttr(parser, "text-align")) {
        "start", "left" -> TextAlign.Start
        "center" -> TextAlign.Center
        "end", "right" -> TextAlign.End
        "justify" -> TextAlign.Justify
        else -> null
    }
}

private fun OdfParser.applyParaMargins(parser: XmlPullParser, acc: StyleAcc) {
    getAttr(parser, "margin-left")?.let { acc.marginLeft = parseDimension(it) }
    getAttr(parser, "margin-right")?.let { acc.marginRight = parseDimension(it) }
    getAttr(parser, "margin-top")?.let { acc.marginTop = parseDimension(it) }
    getAttr(parser, "margin-bottom")?.let { acc.marginBottom = parseDimension(it) }
    getAttr(parser, "text-indent")?.let { acc.textIndent = parseDimension(it) }
}

private fun OdfParser.applyParaPadding(parser: XmlPullParser, acc: StyleAcc) {
    getAttr(parser, "padding")?.let { acc.padding = parseDimension(it) }
    if (acc.padding == 0f) {
        (getAttr(parser, "padding-left") ?: getAttr(parser, "padding-top")
            ?: getAttr(parser, "padding-right") ?: getAttr(
                parser,
                "padding-bottom"))?.let { acc.padding = parseDimension(it) }
    }
}

/** Keep-with-next/keep-together/widows/orphans. */
private fun OdfParser.applyParaKeepWidows(parser: XmlPullParser, acc: StyleAcc) {
    if (getAttr(parser, "keep-with-next").let { it != null && it != "auto" }) acc.keepWithNext = true
    if (getAttr(parser, "keep-together").let { it != null && it != "auto" }) acc.keepTogether = true
    getAttr(parser, "widows")?.toIntOrNull()?.let { acc.widows = it }
    getAttr(parser, "orphans")?.toIntOrNull()?.let { acc.orphans = it }
}

/** Borders + background + breaks. */
private fun OdfParser.applyParaBordersBreaks(parser: XmlPullParser, acc: StyleAcc) {
    getAttr(parser, "background-color")?.let { if (it != "transparent") acc.paraBgColor = parseColor(it) }
    acc.breakBefore = getAttr(parser, "break-before")
    acc.breakAfter = getAttr(parser, "break-after")
    getAttr(parser, "border")?.let { b ->
        acc.paraBorderAll = b
        b.split(" ").lastOrNull { it.startsWith("#") }?.let { acc.paraBorderColor = parseColor(it) }
    }
    getAttr(parser, "border-top")?.let { acc.paraBorderT = it }
    getAttr(parser, "border-right")?.let { acc.paraBorderR = it }
    getAttr(parser, "border-bottom")?.let { acc.paraBorderB = it }
    getAttr(parser, "border-left")?.let { acc.paraBorderL = it }
}

/** Line-height + writing-mode. */
private fun OdfParser.applyParaLineHeightWriting(parser: XmlPullParser, acc: StyleAcc) {
    getAttr(parser, "writing-mode")?.let { acc.writingMode = it }
    getAttr(parser, "line-height")?.let { lh ->
        if (lh.endsWith("%")) {
            lh.dropLast(1).toFloatOrNull()?.let { acc.lineHeightPercent = it / PERCENT_DIVISOR }
        }
    }
}

/** tab-stop properties. */
private fun OdfParser.applyTabStop(parser: XmlPullParser, acc: StyleAcc) {
    getAttr(parser, "position")?.let { pos ->
        val px = parseDimension(pos)
        acc.tabStops.add(px)
        val type = getAttr(parser, "type")
        val leader = getAttr(parser, "leader-char") ?: getAttr(parser, "leader-text")
        acc.tabStopDetails.add(OdfTabStop(px, type, leader))
    }
}

/** drawing-page-properties properties. */
private fun OdfParser.applyDrawingPageProperties(parser: XmlPullParser, acc: StyleAcc) {
    if (getAttr(parser, "fill") == "solid") {
        getAttr(parser, "fill-color")?.let { acc.drawFillColor = parseColor(it) }
    }
    getAttr(parser, "fill-color")?.let { if (acc.drawFillColor == null) acc.drawFillColor = parseColor(it) }
    getAttr(parser, "transition-style")?.let { acc.transitionType = it }
    getAttr(parser, "transition-speed")?.let { acc.transitionSpeed = it }
}

/** graphic-properties properties. */
private fun OdfParser.applyGraphicProperties(parser: XmlPullParser, acc: StyleAcc) {
    if (getAttr(parser, "fill") == "solid" || getAttr(parser, "fill") == null) {
        getAttr(parser, "fill-color")?.let { acc.drawFillColor = parseColor(it) }
    }
    if (getAttr(parser, "fill") == "gradient") getAttr(
        parser,
        "fill-gradient-name")?.let { acc.fillGradientName = it }
    if (getAttr(parser, "stroke") == "dash") acc.strokeDashed = true
    if (getAttr(parser, "marker-start") != null) acc.markerStart = true
    if (getAttr(parser, "marker-end") != null) acc.markerEnd = true
    getAttr(parser, "stroke-color")?.let { acc.drawStrokeColor = parseColor(it) }
    getAttr(parser, "stroke-width")?.let { acc.drawStrokeWidth = parseDimension(it) }
    getAttr(parser, "clip")?.let { acc.clipVal = it }
    getAttr(parser, "image-opacity")?.let { acc.imageOpacity = it.removeSuffix("%").toFloatOrNull() }
    getAttr(parser, "color-mode")?.let { acc.imageColorMode = it }
}

/** table-cell-properties properties. */
private fun OdfParser.applyTableCellProperties(parser: XmlPullParser, acc: StyleAcc) {
    applyCellBgBorder(parser, acc)
    applyCellEdgesWrap(parser, acc)
}

private fun OdfParser.applyCellBgBorder(parser: XmlPullParser, acc: StyleAcc) {
    getAttr(parser, "background-color")?.let { if (it != "transparent") acc.cellBgColor = parseColor(it) }
    getAttr(parser, "border")?.let { border ->
        acc.cellBorderAll = border
        border.split(" ").lastOrNull { it.startsWith("#") }?.let { acc.cellBorderColor = parseColor(it) }
    }
}

private fun OdfParser.applyCellEdgesWrap(parser: XmlPullParser, acc: StyleAcc) {
    getAttr(parser, "border-top")?.let { acc.cellBorderT = it }
    getAttr(parser, "border-right")?.let { acc.cellBorderR = it }
    getAttr(parser, "border-bottom")?.let { acc.cellBorderB = it }
    getAttr(parser, "border-left")?.let { acc.cellBorderL = it }
    if (acc.cellBorderColor == null) {
        (acc.cellBorderT ?: acc.cellBorderR ?: acc.cellBorderB ?: acc.cellBorderL)
            ?.split(" ")?.lastOrNull { it.startsWith("#") }?.let { acc.cellBorderColor = parseColor(it) }
    }
    if (getAttr(parser, "wrap-option") == "wrap") acc.cellWrap = true
    getAttr(parser, "vertical-align")?.let { acc.cellVerticalAlign = it }
}


/** Combines a uniform fo:border with per-edge overrides into [OdfBorders], or null if none. */
private fun OdfParser.buildBorders(
    all: String?,
    top: String?,
    right: String?,
    bottom: String?,
    left: String?): OdfBorders? {
    if (listOf(all, top, right, bottom, left).all { it == null }) return null
    return OdfBorders(top ?: all, right ?: all, bottom ?: all, left ?: all)
}

internal fun OdfParser.resolveStyle(name: String?, styles: Map<String, StyleInfo>): StyleInfo {
    if (name == null) return StyleInfo()
    val info = styles[name] ?: return StyleInfo()
    if (info.parentStyle != null) {
        val parent = resolveStyle(info.parentStyle, styles)
        return mergeTextStyle(info, parent).mergeParaStyle(info, parent).mergeDecorStyle(info, parent)
    }
    return info
}

/** Merge text properties with parent. */
private fun mergeTextStyle(info: StyleInfo, parent: StyleInfo): StyleInfo {
    return mergeTextStyleB(mergeTextStyleA(info, parent), info, parent)
}

private fun mergeTextStyleA(info: StyleInfo, parent: StyleInfo): StyleInfo {
    return info.copy(
        bold = info.bold || parent.bold,
        italic = info.italic || parent.italic,
        fontSize = info.fontSize ?: parent.fontSize,
        fontFamily = info.fontFamily ?: parent.fontFamily,
        underline = info.underline || parent.underline,
        strikethrough = info.strikethrough || parent.strikethrough,
        color = info.color ?: parent.color,
        backgroundColor = info.backgroundColor ?: parent.backgroundColor,
        superscript = info.superscript || parent.superscript,
        subscript = info.subscript || parent.subscript,
    )
}

private fun mergeTextStyleB(base: StyleInfo, info: StyleInfo, parent: StyleInfo): StyleInfo {
    return base.copy(
        underlineStyle = info.underlineStyle ?: parent.underlineStyle,
        underlineColor = info.underlineColor ?: parent.underlineColor,
        letterSpacing = info.letterSpacing ?: parent.letterSpacing,
        textTransform = info.textTransform ?: parent.textTransform,
        language = info.language ?: parent.language,
        country = info.country ?: parent.country,
    )
}

/** Merge paragraph properties with parent. */
private fun StyleInfo.mergeParaStyle(info: StyleInfo, parent: StyleInfo): StyleInfo {
    return mergeParaStyleB(mergeParaStyleA(info, parent), info, parent)
}

private fun StyleInfo.mergeParaStyleA(info: StyleInfo, parent: StyleInfo): StyleInfo {
    return copy(
        textAlign = info.textAlign ?: parent.textAlign,
        marginLeft = if (info.marginLeft != 0f) info.marginLeft else parent.marginLeft,
        marginTop = if (info.marginTop != 0f) info.marginTop else parent.marginTop,
        marginBottom = if (info.marginBottom != 0f) info.marginBottom else parent.marginBottom,
        textIndent = info.textIndent ?: parent.textIndent,
        paragraphBackgroundColor = info.paragraphBackgroundColor ?: parent.paragraphBackgroundColor,
        breakBefore = info.breakBefore ?: parent.breakBefore,
        breakAfter = info.breakAfter ?: parent.breakAfter,
        writingMode = info.writingMode ?: parent.writingMode,
        lineHeightPercent = info.lineHeightPercent ?: parent.lineHeightPercent,
    )
}

private fun StyleInfo.mergeParaStyleB(base: StyleInfo, info: StyleInfo, parent: StyleInfo): StyleInfo {
    return base.copy(
        paragraphBorderColor = info.paragraphBorderColor ?: parent.paragraphBorderColor,
        tabStops = if (info.tabStops.isNotEmpty()) info.tabStops else parent.tabStops,
        marginRight = if (info.marginRight != 0f) info.marginRight else parent.marginRight,
        keepWithNext = info.keepWithNext || parent.keepWithNext,
        keepTogether = info.keepTogether || parent.keepTogether,
        widows = info.widows ?: parent.widows,
        orphans = info.orphans ?: parent.orphans,
        tabStopDetails = if (info.tabStopDetails.isNotEmpty()) info.tabStopDetails else parent.tabStopDetails,
        padding = if (info.padding != 0f) info.padding else parent.padding,
    )
}

/** Merge decoration/table/media properties with parent. */
private fun StyleInfo.mergeDecorStyle(info: StyleInfo, parent: StyleInfo): StyleInfo {
    return mergeDecorStyleB(mergeDecorStyleA(info, parent), info, parent)
}

private fun StyleInfo.mergeDecorStyleA(info: StyleInfo, parent: StyleInfo): StyleInfo {
    return copy(
        drawFillColor = info.drawFillColor ?: parent.drawFillColor,
        drawStrokeColor = info.drawStrokeColor ?: parent.drawStrokeColor,
        drawStrokeWidth = info.drawStrokeWidth ?: parent.drawStrokeWidth,
        cellBackgroundColor = info.cellBackgroundColor ?: parent.cellBackgroundColor,
        cellBorderColor = info.cellBorderColor ?: parent.cellBorderColor,
        columnWidth = info.columnWidth ?: parent.columnWidth,
        dataStyleName = info.dataStyleName ?: parent.dataStyleName,
        cellWrap = info.cellWrap || parent.cellWrap,
        clip = info.clip ?: parent.clip,
        cellBorders = info.cellBorders ?: parent.cellBorders,
        paraBorders = info.paraBorders ?: parent.paraBorders,
        rowHeight = info.rowHeight ?: parent.rowHeight,
    )
}

private fun StyleInfo.mergeDecorStyleB(base: StyleInfo, info: StyleInfo, parent: StyleInfo): StyleInfo {
    return base.copy(
        imageOpacity = info.imageOpacity ?: parent.imageOpacity,
        imageColorMode = info.imageColorMode ?: parent.imageColorMode,
        transitionType = info.transitionType ?: parent.transitionType,
        transitionSpeed = info.transitionSpeed ?: parent.transitionSpeed,
        conditionalMaps = info.conditionalMaps.ifEmpty { parent.conditionalMaps },
        cellVerticalAlign = info.cellVerticalAlign ?: parent.cellVerticalAlign,
        fillGradientName = info.fillGradientName ?: parent.fillGradientName,
        columnCount = if (info.columnCount != 1) info.columnCount else parent.columnCount,
        strokeDashed = info.strokeDashed || parent.strokeDashed,
        markerStart = info.markerStart || parent.markerStart,
        markerEnd = info.markerEnd || parent.markerEnd,
        dropCapLines = if (info.dropCapLines != 0) info.dropCapLines else parent.dropCapLines,
        dropCapLength = if (info.dropCapLines != 0) info.dropCapLength else parent.dropCapLength,
    )
}

// --- List style parsing ---

internal data class ListLevelStyle(
    val numbered: Boolean,
    val numberFormat: String = "1",
    val bulletChar: String = "•",
    val prefix: String = "",
    val suffix: String = ".",
    val startValue: Int = 1,
    val displayLevels: Int = 1,
    // Checkbox list level (loext:checkbox on the bullet level style). (Phase 2)
    val checkbox: Boolean = false,
    // Bullet image href (list-level-style-image xlink:href), preserved for round-trip. (Phase 2)
    val bulletImagePath: String? = null
)

internal data class ListStyleInfo(val levels: Map<Int, ListLevelStyle>) {
    fun levelStyle(level: Int): ListLevelStyle {
        if (levels.isEmpty()) return ListLevelStyle(numbered = false)
        val nearest = levels.keys.minByOrNull { kotlin.math.abs(it - level) }
        return levels[level] ?: levels[nearest] ?: ListLevelStyle(numbered = false)
    }
    val anyNumbered: Boolean get() = levels.values.any { it.numbered }
}

internal fun OdfParser.parseListStyles(xml: String): Map<String, ListStyleInfo> {
    val map = mutableMapOf<String, ListStyleInfo>()
    val parser = newParser(xml)
    val acc = ListStyleAcc()
    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> applyListStyleStart(parser, acc)
            XmlPullParser.END_TAG ->
                if ((parser.name == "list-style" || parser.name == "outline-style") && acc.currentName != null) {
                map[acc.currentName!!] = ListStyleInfo(acc.levels.toMap())
                acc.currentName = null
            }
        }
        eventType = parser.next()
    }
    return map
}

/** List-style accumulation state. */
private class ListStyleAcc(
    var currentName: String? = null,
    var levels: MutableMap<Int, ListLevelStyle> = mutableMapOf(),
)

/** Apply one list-style start tag. */
private fun OdfParser.applyListStyleStart(parser: XmlPullParser, acc: ListStyleAcc) {
    when (parser.name) {
        "list-style" -> { acc.currentName = getAttr(parser, "name"); acc.levels = mutableMapOf() }
        "outline-style" -> { acc.currentName = "%outline%"; acc.levels = mutableMapOf() }
        "outline-level-style" -> applyOutlineLevel(parser, acc)
        "list-level-style-number" -> applyNumberLevel(parser, acc)
        "list-level-style-bullet" -> applyBulletLevel(parser, acc)
        "list-level-style-image" -> applyImageLevel(parser, acc)
    }
}

/** Outline level style. */
private fun OdfParser.applyOutlineLevel(parser: XmlPullParser, acc: ListStyleAcc) {
    val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
    val fmt = getAttr(parser, "num-format")
    acc.levels[lvl] = ListLevelStyle(
        numbered = !fmt.isNullOrEmpty(),
        numberFormat = fmt?.ifEmpty { "1" } ?: "1",
        prefix = getAttr(parser, "num-prefix") ?: "",
        suffix = getAttr(parser, "num-suffix") ?: "",
        startValue = getAttr(parser, "start-value")?.toIntOrNull() ?: 1,
        displayLevels = getAttr(parser, "display-levels")?.toIntOrNull() ?: 1
    )
}

/** Numbered level style. */
private fun OdfParser.applyNumberLevel(parser: XmlPullParser, acc: ListStyleAcc) {
    val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
    acc.levels[lvl] = ListLevelStyle(
        numbered = true,
        numberFormat = getAttr(parser, "num-format")?.ifEmpty { "1" } ?: "1",
        prefix = getAttr(parser, "num-prefix") ?: "",
        // ODF default for text:num-suffix is empty; only add "." when present.
        suffix = getAttr(parser, "num-suffix") ?: "",
        startValue = getAttr(parser, "start-value")?.toIntOrNull() ?: 1
    )
}

/** Bullet level style. */
private fun OdfParser.applyBulletLevel(parser: XmlPullParser, acc: ListStyleAcc) {
    val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
    val bullet = getAttr(parser, "bullet-char")?.ifEmpty { "•" } ?: "•"
    val isCheckbox = getAttr(
        parser,
        "checkbox") == "true" || bullet == "☐" || bullet == "☑" || bullet == "☒"
    acc.levels[lvl] = ListLevelStyle(
        numbered = false,
        bulletChar = bullet,
        checkbox = isCheckbox
    )
}

/** Image level style. */
private fun OdfParser.applyImageLevel(parser: XmlPullParser, acc: ListStyleAcc) {
    val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
    acc.levels[lvl] = ListLevelStyle(
        numbered = false,
        bulletChar = "▪",
        bulletImagePath = getAttr(parser, "href"))
}

// --- Number format (data style) parsing (H50) ---

/** Number-style accumulation state. */
private class NumberStyleAcc(
    var curName: String? = null,
    var type: String = "",
    var decimals: Int? = null,
    var currency: String? = null,
    var grouping: Boolean = false,
    var isTime: Boolean = false,
    var isScientific: Boolean = false,
    var isFraction: Boolean = false,
    var fracDenomDigits: Int = 1,
    val dateTokens: MutableList<OdfNumberToken> = mutableListOf(),
) {
    fun reset(name: String?, t: String) {
        curName = name; type = t
        decimals = null; currency = null; grouping = false
        isTime = false; isScientific = false; isFraction = false; fracDenomDigits = 1
        dateTokens.clear()
    }

    fun flushInto(map: MutableMap<String, OdfNumberFormat>) {
        val n = curName ?: return
        map[n] = OdfNumberFormat(
            decimals = decimals,
            percent = type == "percentage",
            currencySymbol = currency,
            grouping = grouping,
            isDate = type == "date",
            isTime = isTime || type == "time",
            isScientific = isScientific,
            isFraction = isFraction,
            fractionDenominatorDigits = fracDenomDigits,
            dateTimeTokens = dateTokens.toList()
        )
    }
}

internal fun OdfParser.parseNumberStyles(xml: String): Map<String, OdfNumberFormat> {
    val map = mutableMapOf<String, OdfNumberFormat>()
    val parser = newParser(xml)
    val acc = NumberStyleAcc()
    var e = parser.eventType
    while (e != XmlPullParser.END_DOCUMENT) {
        when (e) {
            XmlPullParser.START_TAG -> applyNumberStyleStart(parser, acc)
            XmlPullParser.END_TAG -> applyNumberStyleEnd(parser, acc, map)
        }
        e = parser.next()
    }
    return map
}

/** Number style date/time token names. */
private val dateTokenNames = setOf(
    "year", "month", "day", "day-of-week", "hours", "minutes", "seconds",
    "am-pm", "era", "quarter", "week-of-year"
)

/** Apply one number-style start tag. */
private fun OdfParser.applyNumberStyleStart(parser: XmlPullParser, acc: NumberStyleAcc) {
    when (parser.name) {
        "number-style", "percentage-style", "currency-style", "date-style", "time-style" -> {
            acc.reset(getAttr(parser, "name"), parser.name.removeSuffix("-style"))
        }
        "text" -> readNumberStyleText(parser, acc)
        "number" -> {
            getAttr(parser, "decimal-places")?.toIntOrNull()?.let { acc.decimals = it }
            if (getAttr(parser, "grouping") == "true") acc.grouping = true
        }
        "scientific-number" -> {
            acc.isScientific = true
            getAttr(parser, "decimal-places")?.toIntOrNull()?.let { acc.decimals = it }
        }
        "fraction" -> {
            acc.isFraction = true
            getAttr(parser, "min-denominator-digits")?.toIntOrNull()?.let { acc.fracDenomDigits = it }
        }
        "currency-symbol" -> acc.currency = readElementText(parser).trim()
        else -> readNumberStyleDateToken(parser, acc)
    }
}

/** Date/time component token reader. */
private fun OdfParser.readNumberStyleDateToken(parser: XmlPullParser, acc: NumberStyleAcc) {
    if (parser.name !in dateTokenNames) return
    if (acc.type != "date" && acc.type != "time") return
    acc.dateTokens.add(
        OdfNumberToken(
            kind = parser.name,
            style = getAttr(parser, "style"),
            textual = getAttr(parser, "textual") == "true"
        )
    )
}

/** Literal text token reader for date/time styles. */
private fun OdfParser.readNumberStyleText(parser: XmlPullParser, acc: NumberStyleAcc) {
    if (acc.type != "date" && acc.type != "time") return
    acc.dateTokens.add(OdfNumberToken(kind = "text", text = readElementText(parser)))
}

/** Reads all text inside the current element. */
private fun OdfParser.readElementText(parser: XmlPullParser): String {
    val d = parser.depth
    var ev = parser.next()
    val sb = StringBuilder()
    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
        if (ev == XmlPullParser.TEXT) sb.append(parser.text)
        if (ev == XmlPullParser.END_DOCUMENT) break
        ev = parser.next()
    }
    return sb.toString()
}

/** Apply one number-style end tag. */
private fun OdfParser.applyNumberStyleEnd(
    parser: XmlPullParser,
    acc: NumberStyleAcc,
    map: MutableMap<String, OdfNumberFormat>,
) {
    when (parser.name) {
        "number-style", "percentage-style", "currency-style", "date-style", "time-style" -> {
            acc.flushInto(map); acc.curName = null
        }
    }
}

// --- Metadata parsing ---

internal fun OdfParser.parseMetadata(xml: String): OdfMetadata {
    val parser = newParser(xml)
    val acc = MetadataAcc()
    var eventType = parser.eventType

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> applyMetadataStart(parser, acc)
            XmlPullParser.TEXT -> applyMetadataText(parser, acc)
            XmlPullParser.END_TAG -> acc.currentTag = ""
        }
        eventType = parser.next()
    }
    return OdfMetadata(
        acc.title,
        acc.creator ?: acc.initialCreator,
        acc.initialCreator,
        acc.creationDate,
        acc.modifiedDate,
        acc.description,
        acc.subject,
        acc.keywords,
        acc.pageCount,
        acc.wordCount,
        generator = acc.generator,
        editingCycles = acc.editingCycles,
        charCount = acc.charCount,
        paragraphCount = acc.paragraphCount,
        userDefined = acc.userDefined,
        userDefinedTypes = acc.userDefinedTypes,
    )
}

/** Metadata accumulation state. */
private class MetadataAcc(
    var title: String? = null,
    var creator: String? = null,
    var initialCreator: String? = null,
    var creationDate: String? = null,
    var modifiedDate: String? = null,
    var description: String? = null,
    var subject: String? = null,
    val keywords: MutableList<String> = mutableListOf(),
    var pageCount: Int? = null,
    var wordCount: Int? = null,
    var charCount: Int? = null,
    var paragraphCount: Int? = null,
    var generator: String? = null,
    var editingCycles: Int? = null,
    val userDefined: LinkedHashMap<String, String> = LinkedHashMap(),
    val userDefinedTypes: LinkedHashMap<String, String> = LinkedHashMap(),
    var udName: String? = null,
    var udType: String? = null,
    var currentTag: String = "",
)

/** Apply one metadata start tag. */
private fun OdfParser.applyMetadataStart(parser: XmlPullParser, acc: MetadataAcc) {
    acc.currentTag = parser.name
    if (parser.name == "document-statistic") {
        acc.pageCount = getAttr(parser, "page-count")?.toIntOrNull()
        acc.wordCount = getAttr(parser, "word-count")?.toIntOrNull()
        acc.charCount = getAttr(parser, "character-count")?.toIntOrNull()
        acc.paragraphCount = getAttr(parser, "paragraph-count")?.toIntOrNull()
    }
    if (parser.name == "user-defined") {
        acc.udName = getAttr(
            parser,
            "name")
        acc.udType = getAttr(parser, "value-type")
    }
}

/** Apply one metadata text node. */
private fun applyMetadataText(parser: XmlPullParser, acc: MetadataAcc) {
    val text = parser.text.trim()
    if (text.isEmpty()) return
    applyMetadataTextCore(acc, text)
    applyMetadataTextExtra(acc, text)
}

private fun applyMetadataTextCore(acc: MetadataAcc, text: String) {
    when (acc.currentTag) {
        "title" -> acc.title = text
        "creator" -> acc.creator = text
        "initial-creator" -> acc.initialCreator = text
        "creation-date" -> acc.creationDate = text
        "date" -> acc.modifiedDate = text
        "description" -> acc.description = text
        "subject" -> acc.subject = text
    }
}

private fun applyMetadataTextExtra(acc: MetadataAcc, text: String) {
    when (acc.currentTag) {
        "keyword" -> acc.keywords.add(text)
        "generator" -> acc.generator = text
        "editing-cycles" -> acc.editingCycles = text.toIntOrNull()
        "user-defined" ->
            acc.udName?.let {
                acc.userDefined[it] = text
                if (acc.udType != null) acc.userDefinedTypes[it] = acc.udType!!
            }
    }
}

// --- Header/Footer parsing from styles.xml ---

internal data class HeaderFooterResult(
    val headerParagraphs: List<OdfParagraph>,
    val footerParagraphs: List<OdfParagraph>
)

/** Parses page geometry from the first style:page-layout-properties in styles.xml. (Priority 7) */
internal fun OdfParser.parsePageSetup(stylesXml: String): OdfPageSetup? {
    val parser = newParser(stylesXml)
    var e = parser.eventType
    while (e != XmlPullParser.END_DOCUMENT) {
        if (e == XmlPullParser.START_TAG && parser.name == "page-layout-properties") {
            return readPageSetup(parser)
        }
        e = parser.next()
    }
    return null
}

private fun OdfParser.readPageSetup(parser: XmlPullParser): OdfPageSetup {
    val def = OdfPageSetup()
    val mAll = getAttr(parser, "margin")?.let { parseDimension(it) }
    return OdfPageSetup(
        widthPx = readPageWidth(parser, def),
        heightPx = getAttr(parser, "page-height")?.let { parseDimension(it) } ?: def.heightPx,
        marginLeftPx = readPageMargin(parser, "margin-left", mAll, def.marginLeftPx),
        marginRightPx = readPageMargin(parser, "margin-right", mAll, def.marginRightPx),
        marginTopPx = readPageMargin(parser, "margin-top", mAll, def.marginTopPx),
        marginBottomPx = readPageMargin(parser, "margin-bottom", mAll, def.marginBottomPx)
    )
}

private fun OdfParser.readPageWidth(parser: XmlPullParser, def: OdfPageSetup): Float {
    return getAttr(parser, "page-width")?.let { parseDimension(it) } ?: def.widthPx
}

private fun OdfParser.readPageMargin(
    parser: XmlPullParser,
    name: String,
    mAll: Float?,
    defVal: Float,
): Float {
    return getAttr(parser, name)?.let { parseDimension(it) } ?: mAll ?: defVal
}

internal fun OdfParser.parseHeaderFooter(stylesXml: String, styles: Map<String, StyleInfo>): HeaderFooterResult {
    val headerParas = mutableListOf<OdfParagraph>()
    val footerParas = mutableListOf<OdfParagraph>()
    val parser = newParser(stylesXml)
    val state = HfAcc()
    var eventType = parser.eventType

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> applyHfStart(parser, styles, state, headerParas, footerParas)
            XmlPullParser.END_TAG -> when (parser.name) {
                "header" -> if (parser.namespace?.contains("style") == true) state.inHeader = false
                "footer" -> if (parser.namespace?.contains("style") == true) state.inFooter = false
            }
        }
        eventType = parser.next()
    }
    return HeaderFooterResult(headerParas, footerParas)
}

/** Header/footer parse state. */
private class HfAcc(
    var inHeader: Boolean = false,
    var inFooter: Boolean = false,
)

/** Apply one header/footer start tag. */
private fun OdfParser.applyHfStart(
    parser: XmlPullParser,
    styles: Map<String, StyleInfo>,
    state: HfAcc,
    headerParas: MutableList<OdfParagraph>,
    footerParas: MutableList<OdfParagraph>,
) {
    when (parser.name) {
        "header" -> if (parser.namespace?.contains("style") == true) state.inHeader = true
        "footer" -> if (parser.namespace?.contains("style") == true) state.inFooter = true
        "p" -> if (state.inHeader || state.inFooter) {
            val spans = parseInlineContent(parser, "p", styles)
            if (spans.isNotEmpty()) {
                val para = OdfParagraph(spans)
                if (state.inHeader) headerParas.add(para)
                else footerParas.add(para)
            }
        }
    }
}
