package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.*
import org.xmlpull.v1.XmlPullParser

// --- Spreadsheet ---

internal fun OdfParser.parseSpreadsheet(
    xml: String, styles: Map<String, StyleInfo>, numberStyles: Map<String, OdfNumberFormat>,
    title: String, metadata: OdfMetadata, images: Map<String, ByteArray>, objectContents: Map<String, String> = emptyMap(),
    freezeMap: Map<String, Pair<Int, Int>> = emptyMap()
): OdfDocument.Spreadsheet {
    val sheets = mutableListOf<OdfSheet>()
    val namedRanges = mutableListOf<OdfNamedRange>()
    val validations = mutableListOf<OdfDataValidation>()
    val parser = newParser(xml)
    var eventType = parser.eventType

    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG && parser.name == "table" && parser.namespace?.contains("table") == true) {
            val name = getAttr(parser, "name") ?: "Sheet ${sheets.size + 1}"
            val printRanges = getAttr(parser, "print-ranges")
            val r = parseSheetContent(parser, styles, numberStyles, images, objectContents)
            val freeze = freezeMap[name]
            sheets.add(OdfSheet(name, r.rows, r.columnWidths, r.floating, freeze?.first ?: 0, freeze?.second ?: 0,
                rowHeights = r.rowHeights, hiddenRows = r.hiddenRows, hiddenCols = r.hiddenCols, printRanges = printRanges))
        } else if (eventType == XmlPullParser.START_TAG && parser.name == "named-range") {
            val nm = getAttr(parser, "name")
            val addr = getAttr(parser, "cell-range-address")
            if (nm != null && addr != null) namedRanges.add(OdfNamedRange(nm, addr, getAttr(parser, "base-cell-address")))
        } else if (eventType == XmlPullParser.START_TAG && parser.name == "content-validation") {
            val nm = getAttr(parser, "name")
            val cond = getAttr(parser, "condition")
            if (nm != null && cond != null) validations.add(OdfDataValidation(nm, cond, getAttr(parser, "allow-empty-cell") != "false"))
        }
        eventType = parser.next()
    }
    return OdfDocument.Spreadsheet(title, sheets, metadata, images, namedRanges, validations)
}

internal data class SheetParse(
    val rows: List<OdfRow>, val columnWidths: List<Float?>, val floating: List<OdfSlideElement>,
    val rowHeights: List<Float?>, val hiddenRows: Set<Int>, val hiddenCols: Set<Int>
)

internal fun OdfParser.parseSheetContent(parser: XmlPullParser, styles: Map<String, StyleInfo>, numberStyles: Map<String, OdfNumberFormat>, images: Map<String, ByteArray>, objectContents: Map<String, String> = emptyMap()): SheetParse {
    val rows = mutableListOf<OdfRow>()
    val columnWidths = mutableListOf<Float?>()
    val floating = mutableListOf<OdfSlideElement>()
    val rowHeights = mutableListOf<Float?>()
    val hiddenRows = mutableSetOf<Int>()
    val hiddenCols = mutableSetOf<Int>()
    var colIndex = 0
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) {
            val isDraw = parser.namespace?.contains("draw") == true
            when (parser.name) {
                "table-column" -> {
                    val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                    val styleName = getAttr(parser, "style-name")
                    val width = resolveStyle(styleName, styles).columnWidth
                    val hidden = getAttr(parser, "visibility").let { it == "collapse" || it == "filter" }
                    repeat(repeated.coerceAtMost(200)) {
                        if (hidden) hiddenCols.add(colIndex)
                        columnWidths.add(width); colIndex++
                    }
                }
                "table-row" -> {
                    val repeated = getAttr(parser, "number-rows-repeated")?.toIntOrNull() ?: 1
                    val styleName = getAttr(parser, "style-name")
                    val rh = resolveStyle(styleName, styles).rowHeight
                    val hidden = getAttr(parser, "visibility").let { it == "collapse" || it == "filter" }
                    val cells = parseSpreadsheetCells(parser, styles, numberStyles)
                    repeat(repeated.coerceAtMost(1000)) {
                        if (hidden) hiddenRows.add(rows.size)
                        rows.add(OdfRow(cells)); rowHeights.add(rh)
                    }
                }
                "frame" -> if (isDraw) floating.add(OdfSlideElement.Frame(parseSingleFrame(parser, styles, images, objectContents)))
                "rect" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "rect")))
                "ellipse" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "ellipse")))
                "line" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "line")))
                "custom-shape" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "custom-shape")))
                "polyline" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "polyline")))
                "polygon" -> if (isDraw) floating.add(OdfSlideElement.Shape(parseShape(parser, styles, "polygon")))
                "path" -> if (isDraw) floating.add(OdfSlideElement.Shape(parsePathShape(parser, styles)))
            }
        }
        eventType = parser.next()
    }
    // Trim trailing all-empty rows.
    while (rows.isNotEmpty() && rows.last().cells.all { it.text.isEmpty() && it.formula == null }) {
        rows.removeAt(rows.size - 1)
        if (rowHeights.isNotEmpty()) rowHeights.removeAt(rowHeights.size - 1)
    }
    return SheetParse(rows, columnWidths, floating, rowHeights, hiddenRows.filter { it < rows.size }.toSet(), hiddenCols)
}

internal fun OdfParser.parseSpreadsheetCells(parser: XmlPullParser, styles: Map<String, StyleInfo>, numberStyles: Map<String, OdfNumberFormat>): List<OdfCell> {
    val cells = mutableListOf<OdfCell>()
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) when (parser.name) {
            "table-cell" -> {
                val spanned = getAttr(parser, "number-columns-spanned")?.toIntOrNull() ?: 1
                val rowSpan = getAttr(parser, "number-rows-spanned")?.toIntOrNull() ?: 1
                val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                val styleName = getAttr(parser, "style-name")
                val resolved = resolveStyle(styleName, styles)
                val formula = getAttr(parser, "formula")
                val valueType = getAttr(parser, "value-type")
                val numberValue = getAttr(parser, "value")?.toDoubleOrNull()
                val numberFormat = resolved.dataStyleName?.let { numberStyles[it] }
                val validationName = getAttr(parser, "content-validation-name")
                val condFormats = resolved.conditionalMaps.map { (cond, sn) ->
                    val t = resolveStyle(sn, styles); OdfCondFormat(cond, t.cellBackgroundColor, t.color)
                }
                val cellContent = parseCellContent(parser, styles)
                repeat(repeated.coerceAtMost(100)) {
                    cells.add(OdfCell(
                        text = cellContent.first, spannedColumns = spanned, rowSpan = rowSpan,
                        backgroundColor = resolved.cellBackgroundColor, textColor = resolved.color,
                        bold = resolved.bold, italic = resolved.italic, alignment = resolved.textAlign,
                        borderColor = resolved.cellBorderColor,
                        borders = resolved.cellBorders,
                        formula = formula, valueType = valueType, numberValue = numberValue,
                        numberFormat = numberFormat, wrap = resolved.cellWrap,
                        annotation = cellContent.second, validationName = validationName,
                        condFormats = condFormats
                    ))
                }
            }
            "covered-table-cell" -> {
                val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                repeat(repeated.coerceAtMost(100)) { cells.add(OdfCell(text = "", isCovered = true)) }
                skipElement(parser)
            }
        }
        eventType = parser.next()
    }
    // Drop trailing "filler" cells (spreadsheets pad each row out to 1024 columns via
    // number-columns-repeated on an empty, unstyled cell). Keeping them bloats the grid width.
    while (cells.isNotEmpty()) {
        val last = cells.last()
        val isFiller = last.text.isEmpty() && last.formula == null && !last.isCovered &&
            last.backgroundColor == null && last.borderColor == null &&
            last.borders?.isEmpty() != false && last.annotation == null &&
            last.numberValue == null && last.spannedColumns == 1 && last.rowSpan == 1 &&
            last.condFormats.isEmpty() && last.validationName == null && last.hyperlink == null
        if (isFiller) cells.removeAt(cells.size - 1) else break
    }
    return cells
}

/** Parses a spreadsheet cell's text plus an optional office:annotation (cell comment). (Round 3) */
internal fun OdfParser.parseCellContent(parser: XmlPullParser, styles: Map<String, StyleInfo>): Pair<String, OdfAnnotation?> {
    val sb = StringBuilder()
    var annotation: OdfAnnotation? = null
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) when (parser.name) {
            "annotation" -> annotation = parseAnnotation(parser, styles)
            // Separate successive paragraphs within a cell with a line break.
            "p" -> if (sb.isNotEmpty()) sb.append("\n")
            // Whitespace-preserving inline elements (multi-space runs, tabs, line breaks).
            "s" -> sb.append(" ".repeat((getAttr(parser, "c")?.toIntOrNull() ?: 1).coerceIn(0, 4096)))
            "tab" -> sb.append("\t")
            "line-break" -> sb.append("\n")
        } else if (eventType == XmlPullParser.TEXT) {
            sb.append(parser.text)
        }
        eventType = parser.next()
    }
    return sb.toString().trim() to annotation
}
