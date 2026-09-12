package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.*
import androidx.compose.ui.text.style.TextAlign
import org.xmlpull.v1.XmlPullParser

// --- Style parsing ---

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
        if (eventType == XmlPullParser.START_TAG && parser.name == "style" && parser.namespace?.contains("style") == true) {
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
    var bold = false; var italic = false; var fontSize: Float? = null; var fontFamily: String? = null
    var underline = false; var strikethrough = false
    var color: Long? = null; var bgColor: Long? = null
    var superscript = false; var subscript = false
    var textAlign: TextAlign? = null
    var marginLeft = 0f; var marginTop = 0f; var marginBottom = 0f; var textIndent: Float? = null
    var marginRight = 0f; var keepWithNext = false; var keepTogether = false
    var widows: Int? = null; var orphans: Int? = null
    var paraBgColor: Long? = null
    var breakBefore: String? = null; var breakAfter: String? = null
    var drawFillColor: Long? = null; var drawStrokeColor: Long? = null; var drawStrokeWidth: Float? = null
    var cellBgColor: Long? = null; var cellBorderColor: Long? = null
    var cellWrap = false
    var cellVerticalAlign: String? = null
    var writingMode: String? = null
    var columnWidth: Float? = null
    var rowHeight: Float? = null
    var lineHeightPercent: Float? = null
    var paraBorderColor: Long? = null
    val tabStops = mutableListOf<Float>()
    val tabStopDetails = mutableListOf<OdfTabStop>()
    var dropCapLines = 0; var dropCapLength = 1
    var padding = 0f
    var clipVal: String? = null
    var imageOpacity: Float? = null; var imageColorMode: String? = null
    var fillGradientName: String? = null
    var columnCount = 1
    var strokeDashed = false; var markerStart = false; var markerEnd = false
    var transitionType: String? = null; var transitionSpeed: String? = null
    val conditionalMaps = mutableListOf<Pair<String, String>>()
    // Raw per-edge border strings (Priority 4): uniform "border" plus optional edge overrides.
    var paraBorderAll: String? = null; var paraBorderT: String? = null; var paraBorderR: String? = null; var paraBorderB: String? = null; var paraBorderL: String? = null
    var cellBorderAll: String? = null; var cellBorderT: String? = null; var cellBorderR: String? = null; var cellBorderB: String? = null; var cellBorderL: String? = null
    // Extended character properties (Round 2 R2).
    var underlineStyle: String? = null; var underlineColor: Long? = null
    var letterSpacing: Float? = null; var textTransform: String? = null
    var language: String? = null; var country: String? = null

    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) {
            when (parser.name) {
                "text-properties" -> {
                    if (getAttr(parser, "font-weight") == "bold") bold = true
                    if (getAttr(parser, "font-style") == "italic") italic = true
                    getAttr(parser, "font-size")?.let { s ->
                        fontSize = when {
                            s.endsWith("pt") || s.endsWith("px") -> s.dropLast(2).toFloatOrNull()
                            // Percentage font sizes are relative to a 12pt default.
                            s.endsWith("%") -> s.dropLast(1).toFloatOrNull()?.let { it / 100f * 12f }
                            s.endsWith("cm") || s.endsWith("mm") || s.endsWith("in") || s.endsWith("pc") ->
                                parseDimension(s).takeIf { it > 0f }?.let { it / (96f / 72f) } // px@96 -> pt
                            else -> s.toFloatOrNull()
                        }
                    }
                    getAttr(parser, "font-name")?.let { fontFamily = it }
                    if (fontFamily == null) getAttr(parser, "font-family")?.let { fontFamily = it }
                    getAttr(parser, "text-underline-style")?.let { if (it != "none") { underline = true; underlineStyle = it } }
                    getAttr(parser, "text-underline-color")?.let { if (it != "font-color") underlineColor = parseColor(it) }
                    getAttr(parser, "text-line-through-style")?.let { if (it != "none") strikethrough = true }
                    getAttr(parser, "letter-spacing")?.let { ls -> if (ls != "normal") letterSpacing = ls.replace("pt", "").replace("cm", "").toFloatOrNull() }
                    getAttr(parser, "text-transform")?.let { if (it != "none") textTransform = it }
                    if (textTransform == null && getAttr(parser, "font-variant") == "small-caps") textTransform = "uppercase"
                    getAttr(parser, "language")?.let { language = it }
                    getAttr(parser, "country")?.let { country = it }
                    getAttr(parser, "color")?.let { color = parseColor(it) }
                    getAttr(parser, "background-color")?.let { if (it != "transparent") bgColor = parseColor(it) }
                    getAttr(parser, "text-position")?.let { tp ->
                        // Format is "<vertical-shift>% <height>%"; a 0% shift is baseline, not super/sub.
                        val shift = tp.substringBefore(' ').removeSuffix("%").trim().toFloatOrNull()
                        when {
                            tp.startsWith("super") -> superscript = true
                            tp.startsWith("sub") -> subscript = true
                            shift != null && shift > 0f -> superscript = true
                            shift != null && shift < 0f -> subscript = true
                        }
                    }
                }
                "paragraph-properties" -> {
                    textAlign = when (getAttr(parser, "text-align")) {
                        "start", "left" -> TextAlign.Start
                        "center" -> TextAlign.Center
                        "end", "right" -> TextAlign.End
                        "justify" -> TextAlign.Justify
                        else -> null
                    }
                    getAttr(parser, "margin-left")?.let { marginLeft = parseDimension(it) }
                    getAttr(parser, "margin-right")?.let { marginRight = parseDimension(it) }
                    getAttr(parser, "margin-top")?.let { marginTop = parseDimension(it) }
                    getAttr(parser, "margin-bottom")?.let { marginBottom = parseDimension(it) }
                    if (getAttr(parser, "keep-with-next").let { it != null && it != "auto" }) keepWithNext = true
                    if (getAttr(parser, "keep-together").let { it != null && it != "auto" }) keepTogether = true
                    getAttr(parser, "widows")?.toIntOrNull()?.let { widows = it }
                    getAttr(parser, "orphans")?.toIntOrNull()?.let { orphans = it }
                    getAttr(parser, "text-indent")?.let { textIndent = parseDimension(it) }
                    getAttr(parser, "padding")?.let { padding = parseDimension(it) }
                    if (padding == 0f) {
                        (getAttr(parser, "padding-left") ?: getAttr(parser, "padding-top")
                            ?: getAttr(parser, "padding-right") ?: getAttr(parser, "padding-bottom"))?.let { padding = parseDimension(it) }
                    }
                    getAttr(parser, "background-color")?.let { if (it != "transparent") paraBgColor = parseColor(it) }
                    breakBefore = getAttr(parser, "break-before")
                    breakAfter = getAttr(parser, "break-after")
                    getAttr(parser, "writing-mode")?.let { writingMode = it }
                    getAttr(parser, "line-height")?.let { lh ->
                        if (lh.endsWith("%")) lh.dropLast(1).toFloatOrNull()?.let { lineHeightPercent = it / 100f }
                    }
                    getAttr(parser, "border")?.let { b ->
                        paraBorderAll = b
                        b.split(" ").lastOrNull { it.startsWith("#") }?.let { paraBorderColor = parseColor(it) }
                    }
                    getAttr(parser, "border-top")?.let { paraBorderT = it }
                    getAttr(parser, "border-right")?.let { paraBorderR = it }
                    getAttr(parser, "border-bottom")?.let { paraBorderB = it }
                    getAttr(parser, "border-left")?.let { paraBorderL = it }
                }
                "tab-stop" -> {
                    getAttr(parser, "position")?.let { pos ->
                        val px = parseDimension(pos)
                        tabStops.add(px)
                        val type = getAttr(parser, "type")
                        val leader = getAttr(parser, "leader-char") ?: getAttr(parser, "leader-text")
                        tabStopDetails.add(OdfTabStop(px, type, leader))
                    }
                }
                "drop-cap" -> {
                    dropCapLines = getAttr(parser, "lines")?.toIntOrNull() ?: 0
                    dropCapLength = getAttr(parser, "length")?.toIntOrNull() ?: 1
                }
                "drawing-page-properties" -> {
                    if (getAttr(parser, "fill") == "solid") {
                        getAttr(parser, "fill-color")?.let { drawFillColor = parseColor(it) }
                    }
                    getAttr(parser, "fill-color")?.let { if (drawFillColor == null) drawFillColor = parseColor(it) }
                    getAttr(parser, "transition-style")?.let { transitionType = it }
                    getAttr(parser, "transition-speed")?.let { transitionSpeed = it }
                }
                "graphic-properties" -> {
                    if (getAttr(parser, "fill") == "solid" || getAttr(parser, "fill") == null) {
                        getAttr(parser, "fill-color")?.let { drawFillColor = parseColor(it) }
                    }
                    if (getAttr(parser, "fill") == "gradient") getAttr(parser, "fill-gradient-name")?.let { fillGradientName = it }
                    if (getAttr(parser, "stroke") == "dash") strokeDashed = true
                    if (getAttr(parser, "marker-start") != null) markerStart = true
                    if (getAttr(parser, "marker-end") != null) markerEnd = true
                    getAttr(parser, "stroke-color")?.let { drawStrokeColor = parseColor(it) }
                    getAttr(parser, "stroke-width")?.let { drawStrokeWidth = parseDimension(it) }
                    getAttr(parser, "clip")?.let { clipVal = it }
                    getAttr(parser, "image-opacity")?.let { imageOpacity = it.removeSuffix("%").toFloatOrNull() }
                    getAttr(parser, "color-mode")?.let { imageColorMode = it }
                }
                "map" -> {
                    val cond = getAttr(parser, "condition"); val applyStyle = getAttr(parser, "apply-style-name")
                    if (cond != null && applyStyle != null) conditionalMaps.add(cond to applyStyle)
                }
                "columns" -> getAttr(parser, "column-count")?.toIntOrNull()?.let { columnCount = it }
                "table-cell-properties" -> {
                    getAttr(parser, "background-color")?.let { if (it != "transparent") cellBgColor = parseColor(it) }
                    getAttr(parser, "border")?.let { border ->
                        cellBorderAll = border
                        border.split(" ").lastOrNull { it.startsWith("#") }?.let { cellBorderColor = parseColor(it) }
                    }
                    getAttr(parser, "border-top")?.let { cellBorderT = it }
                    getAttr(parser, "border-right")?.let { cellBorderR = it }
                    getAttr(parser, "border-bottom")?.let { cellBorderB = it }
                    getAttr(parser, "border-left")?.let { cellBorderL = it }
                    if (cellBorderColor == null) {
                        (cellBorderT ?: cellBorderR ?: cellBorderB ?: cellBorderL)
                            ?.split(" ")?.lastOrNull { it.startsWith("#") }?.let { cellBorderColor = parseColor(it) }
                    }
                    if (getAttr(parser, "wrap-option") == "wrap") cellWrap = true
                    getAttr(parser, "vertical-align")?.let { cellVerticalAlign = it }
                }
                "table-column-properties" -> {
                    getAttr(parser, "column-width")?.let { columnWidth = parseDimension(it) }
                }
                "table-row-properties" -> {
                    getAttr(parser, "row-height")?.let { rowHeight = parseDimension(it) }
                }
            }
        }
        eventType = parser.next()
    }
    return StyleInfo(
        bold, italic, fontSize, fontFamily, parentStyle,
        underline, strikethrough, color, bgColor, superscript, subscript,
        textAlign, marginLeft, marginTop, marginBottom, textIndent, paraBgColor,
        breakBefore, breakAfter, drawFillColor, drawStrokeColor, drawStrokeWidth,
        cellBgColor, cellBorderColor, writingMode, columnWidth,
        lineHeightPercent, paraBorderColor, tabStops, null, cellWrap, clipVal,
        buildBorders(cellBorderAll, cellBorderT, cellBorderR, cellBorderB, cellBorderL),
        buildBorders(paraBorderAll, paraBorderT, paraBorderR, paraBorderB, paraBorderL),
        underlineStyle, underlineColor, letterSpacing, textTransform, language, country,
        marginRight, keepWithNext, keepTogether, widows, orphans, rowHeight, imageOpacity, imageColorMode, transitionType, transitionSpeed, conditionalMaps, cellVerticalAlign, fillGradientName, columnCount, strokeDashed, markerStart, markerEnd,
        tabStopDetails, dropCapLines, dropCapLength, padding
    )
}

/** Combines a uniform fo:border with per-edge overrides into [OdfBorders], or null if none. */
private fun OdfParser.buildBorders(all: String?, top: String?, right: String?, bottom: String?, left: String?): OdfBorders? {
    if (all == null && top == null && right == null && bottom == null && left == null) return null
    return OdfBorders(top ?: all, right ?: all, bottom ?: all, left ?: all)
}

internal fun OdfParser.resolveStyle(name: String?, styles: Map<String, StyleInfo>): StyleInfo {
    if (name == null) return StyleInfo()
    val info = styles[name] ?: return StyleInfo()
    if (info.parentStyle != null) {
        val parent = resolveStyle(info.parentStyle, styles)
        return StyleInfo(
            bold = info.bold || parent.bold,
            italic = info.italic || parent.italic,
            fontSize = info.fontSize ?: parent.fontSize,
            fontFamily = info.fontFamily ?: parent.fontFamily,
            parentStyle = null,
            underline = info.underline || parent.underline,
            strikethrough = info.strikethrough || parent.strikethrough,
            color = info.color ?: parent.color,
            backgroundColor = info.backgroundColor ?: parent.backgroundColor,
            superscript = info.superscript || parent.superscript,
            subscript = info.subscript || parent.subscript,
            textAlign = info.textAlign ?: parent.textAlign,
            marginLeft = if (info.marginLeft != 0f) info.marginLeft else parent.marginLeft,
            marginTop = if (info.marginTop != 0f) info.marginTop else parent.marginTop,
            marginBottom = if (info.marginBottom != 0f) info.marginBottom else parent.marginBottom,
            textIndent = info.textIndent ?: parent.textIndent,
            paragraphBackgroundColor = info.paragraphBackgroundColor ?: parent.paragraphBackgroundColor,
            breakBefore = info.breakBefore ?: parent.breakBefore,
            breakAfter = info.breakAfter ?: parent.breakAfter,
            drawFillColor = info.drawFillColor ?: parent.drawFillColor,
            drawStrokeColor = info.drawStrokeColor ?: parent.drawStrokeColor,
            drawStrokeWidth = info.drawStrokeWidth ?: parent.drawStrokeWidth,
            cellBackgroundColor = info.cellBackgroundColor ?: parent.cellBackgroundColor,
            cellBorderColor = info.cellBorderColor ?: parent.cellBorderColor,
            writingMode = info.writingMode ?: parent.writingMode,
            columnWidth = info.columnWidth ?: parent.columnWidth,
            lineHeightPercent = info.lineHeightPercent ?: parent.lineHeightPercent,
            paragraphBorderColor = info.paragraphBorderColor ?: parent.paragraphBorderColor,
            tabStops = if (info.tabStops.isNotEmpty()) info.tabStops else parent.tabStops,
            dataStyleName = info.dataStyleName ?: parent.dataStyleName,
            cellWrap = info.cellWrap || parent.cellWrap,
            clip = info.clip ?: parent.clip,
            cellBorders = info.cellBorders ?: parent.cellBorders,
            paraBorders = info.paraBorders ?: parent.paraBorders,
            underlineStyle = info.underlineStyle ?: parent.underlineStyle,
            underlineColor = info.underlineColor ?: parent.underlineColor,
            letterSpacing = info.letterSpacing ?: parent.letterSpacing,
            textTransform = info.textTransform ?: parent.textTransform,
            language = info.language ?: parent.language,
            country = info.country ?: parent.country,
            marginRight = if (info.marginRight != 0f) info.marginRight else parent.marginRight,
            keepWithNext = info.keepWithNext || parent.keepWithNext,
            keepTogether = info.keepTogether || parent.keepTogether,
            widows = info.widows ?: parent.widows,
            orphans = info.orphans ?: parent.orphans,
            rowHeight = info.rowHeight ?: parent.rowHeight,
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
            tabStopDetails = if (info.tabStopDetails.isNotEmpty()) info.tabStopDetails else parent.tabStopDetails,
            dropCapLines = if (info.dropCapLines != 0) info.dropCapLines else parent.dropCapLines,
            dropCapLength = if (info.dropCapLines != 0) info.dropCapLength else parent.dropCapLength,
            padding = if (info.padding != 0f) info.padding else parent.padding
        )
    }
    return info
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
        return levels[level] ?: levels[levels.keys.minByOrNull { kotlin.math.abs(it - level) }] ?: ListLevelStyle(numbered = false)
    }
    val anyNumbered: Boolean get() = levels.values.any { it.numbered }
}

internal fun OdfParser.parseListStyles(xml: String): Map<String, ListStyleInfo> {
    val map = mutableMapOf<String, ListStyleInfo>()
    val parser = newParser(xml)
    var eventType = parser.eventType
    var currentName: String? = null
    var levels = mutableMapOf<Int, ListLevelStyle>()

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "list-style" -> { currentName = getAttr(parser, "name"); levels = mutableMapOf() }
                "outline-style" -> { currentName = "%outline%"; levels = mutableMapOf() }
                "outline-level-style" -> {
                    val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
                    val fmt = getAttr(parser, "num-format")
                    levels[lvl] = ListLevelStyle(
                        numbered = !fmt.isNullOrEmpty(),
                        numberFormat = fmt?.ifEmpty { "1" } ?: "1",
                        prefix = getAttr(parser, "num-prefix") ?: "",
                        suffix = getAttr(parser, "num-suffix") ?: "",
                        startValue = getAttr(parser, "start-value")?.toIntOrNull() ?: 1,
                        displayLevels = getAttr(parser, "display-levels")?.toIntOrNull() ?: 1
                    )
                }
                "list-level-style-number" -> {
                    val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
                    levels[lvl] = ListLevelStyle(
                        numbered = true,
                        numberFormat = getAttr(parser, "num-format")?.ifEmpty { "1" } ?: "1",
                        prefix = getAttr(parser, "num-prefix") ?: "",
                        // ODF default for text:num-suffix is empty; only add "." when present.
                        suffix = getAttr(parser, "num-suffix") ?: "",
                        startValue = getAttr(parser, "start-value")?.toIntOrNull() ?: 1
                    )
                }
                "list-level-style-bullet" -> {
                    val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
                    val bullet = getAttr(parser, "bullet-char")?.ifEmpty { "•" } ?: "•"
                    val isCheckbox = getAttr(parser, "checkbox") == "true" || bullet == "☐" || bullet == "☑" || bullet == "☒"
                    levels[lvl] = ListLevelStyle(
                        numbered = false,
                        bulletChar = bullet,
                        checkbox = isCheckbox
                    )
                }
                "list-level-style-image" -> {
                    val lvl = getAttr(parser, "level")?.toIntOrNull() ?: 1
                    levels[lvl] = ListLevelStyle(numbered = false, bulletChar = "▪", bulletImagePath = getAttr(parser, "href"))
                }
            }
            XmlPullParser.END_TAG -> if ((parser.name == "list-style" || parser.name == "outline-style") && currentName != null) {
                map[currentName] = ListStyleInfo(levels.toMap())
                currentName = null
            }
        }
        eventType = parser.next()
    }
    return map
}

// --- Number format (data style) parsing (H50) ---

internal fun OdfParser.parseNumberStyles(xml: String): Map<String, OdfNumberFormat> {
    val map = mutableMapOf<String, OdfNumberFormat>()
    val parser = newParser(xml)
    var e = parser.eventType
    var curName: String? = null
    var type = ""
    var decimals: Int? = null
    var currency: String? = null
    var grouping = false
    var isTime = false
    var isScientific = false
    var isFraction = false
    var fracDenomDigits = 1
    val dateTokens = mutableListOf<OdfNumberToken>()
    fun flush() {
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
    // Local names of date/time component tokens preserved in order. (Phase 4)
    val dateTokenNames = setOf(
        "year", "month", "day", "day-of-week", "hours", "minutes", "seconds",
        "am-pm", "era", "quarter", "week-of-year"
    )
    while (e != XmlPullParser.END_DOCUMENT) {
        when (e) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "number-style", "percentage-style", "currency-style", "date-style", "time-style" -> {
                    curName = getAttr(parser, "name"); type = parser.name.removeSuffix("-style")
                    decimals = null; currency = null; grouping = false
                    isTime = false; isScientific = false; isFraction = false; fracDenomDigits = 1
                    dateTokens.clear()
                }
                in dateTokenNames -> if (type == "date" || type == "time") {
                    dateTokens.add(OdfNumberToken(
                        kind = parser.name,
                        style = getAttr(parser, "style"),
                        textual = getAttr(parser, "textual") == "true"
                    ))
                }
                "text" -> if (type == "date" || type == "time") {
                    val d = parser.depth; var ev = parser.next(); val sb = StringBuilder()
                    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                        if (ev == XmlPullParser.TEXT) sb.append(parser.text)
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        ev = parser.next()
                    }
                    dateTokens.add(OdfNumberToken(kind = "text", text = sb.toString()))
                }
                "number" -> {
                    getAttr(parser, "decimal-places")?.toIntOrNull()?.let { decimals = it }
                    if (getAttr(parser, "grouping") == "true") grouping = true
                }
                "scientific-number" -> {
                    isScientific = true
                    getAttr(parser, "decimal-places")?.toIntOrNull()?.let { decimals = it }
                }
                "fraction" -> {
                    isFraction = true
                    getAttr(parser, "min-denominator-digits")?.toIntOrNull()?.let { fracDenomDigits = it }
                }
                "currency-symbol" -> {
                    val d = parser.depth; var ev = parser.next(); val sb = StringBuilder()
                    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                        if (ev == XmlPullParser.TEXT) sb.append(parser.text)
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        ev = parser.next()
                    }
                    currency = sb.toString().trim()
                }
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "number-style", "percentage-style", "currency-style", "date-style", "time-style" -> { flush(); curName = null }
            }
        }
        e = parser.next()
    }
    return map
}

// --- Metadata parsing ---

internal fun OdfParser.parseMetadata(xml: String): OdfMetadata {
    val parser = newParser(xml)
    var eventType = parser.eventType
    var title: String? = null; var creator: String? = null; var initialCreator: String? = null
    var creationDate: String? = null; var modifiedDate: String? = null
    var description: String? = null; var subject: String? = null
    val keywords = mutableListOf<String>()
    var pageCount: Int? = null; var wordCount: Int? = null
    var charCount: Int? = null; var paragraphCount: Int? = null
    var generator: String? = null; var editingCycles: Int? = null
    val userDefined = LinkedHashMap<String, String>()
    val userDefinedTypes = LinkedHashMap<String, String>()
    var udName: String? = null
    var udType: String? = null
    var currentTag = ""

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> {
                currentTag = parser.name
                if (parser.name == "document-statistic") {
                    pageCount = getAttr(parser, "page-count")?.toIntOrNull()
                    wordCount = getAttr(parser, "word-count")?.toIntOrNull()
                    charCount = getAttr(parser, "character-count")?.toIntOrNull()
                    paragraphCount = getAttr(parser, "paragraph-count")?.toIntOrNull()
                }
                if (parser.name == "user-defined") { udName = getAttr(parser, "name"); udType = getAttr(parser, "value-type") }
            }
            XmlPullParser.TEXT -> {
                val text = parser.text.trim()
                if (text.isNotEmpty()) when (currentTag) {
                    "title" -> title = text
                    "creator" -> creator = text
                    "initial-creator" -> initialCreator = text
                    "creation-date" -> creationDate = text
                    "date" -> modifiedDate = text
                    "description" -> description = text
                    "subject" -> subject = text
                    "keyword" -> keywords.add(text)
                    "generator" -> generator = text
                    "editing-cycles" -> editingCycles = text.toIntOrNull()
                    "user-defined" -> udName?.let { userDefined[it] = text; if (udType != null) userDefinedTypes[it] = udType }
                }
            }
            XmlPullParser.END_TAG -> currentTag = ""
        }
        eventType = parser.next()
    }
    return OdfMetadata(title, creator ?: initialCreator, initialCreator, creationDate, modifiedDate, description, subject, keywords, pageCount, wordCount,
        generator = generator, editingCycles = editingCycles, charCount = charCount, paragraphCount = paragraphCount, userDefined = userDefined, userDefinedTypes = userDefinedTypes)
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
            val def = OdfPageSetup()
            val mAll = getAttr(parser, "margin")?.let { parseDimension(it) }
            return OdfPageSetup(
                widthPx = getAttr(parser, "page-width")?.let { parseDimension(it) } ?: def.widthPx,
                heightPx = getAttr(parser, "page-height")?.let { parseDimension(it) } ?: def.heightPx,
                marginLeftPx = getAttr(parser, "margin-left")?.let { parseDimension(it) } ?: mAll ?: def.marginLeftPx,
                marginRightPx = getAttr(parser, "margin-right")?.let { parseDimension(it) } ?: mAll ?: def.marginRightPx,
                marginTopPx = getAttr(parser, "margin-top")?.let { parseDimension(it) } ?: mAll ?: def.marginTopPx,
                marginBottomPx = getAttr(parser, "margin-bottom")?.let { parseDimension(it) } ?: mAll ?: def.marginBottomPx
            )
        }
        e = parser.next()
    }
    return null
}

internal fun OdfParser.parseHeaderFooter(stylesXml: String, styles: Map<String, StyleInfo>): HeaderFooterResult {
    val headerParas = mutableListOf<OdfParagraph>()
    val footerParas = mutableListOf<OdfParagraph>()
    val parser = newParser(stylesXml)
    var eventType = parser.eventType
    var inHeader = false; var inFooter = false

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "header" -> if (parser.namespace?.contains("style") == true) inHeader = true
                "footer" -> if (parser.namespace?.contains("style") == true) inFooter = true
                "p" -> if (inHeader || inFooter) {
                    val spans = parseInlineContent(parser, "p", styles)
                    if (spans.isNotEmpty()) {
                        val para = OdfParagraph(spans)
                        if (inHeader) headerParas.add(para)
                        else footerParas.add(para)
                    }
                }
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "header" -> if (parser.namespace?.contains("style") == true) inHeader = false
                "footer" -> if (parser.namespace?.contains("style") == true) inFooter = false
            }
        }
        eventType = parser.next()
    }
    return HeaderFooterResult(headerParas, footerParas)
}
