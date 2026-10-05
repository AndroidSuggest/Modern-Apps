package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfBorders
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfCondFormat
import com.vayunmathur.library.ui.odf.OdfDataValidation
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfNamedRange
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.util.OfficeNative
import org.xmlpull.v1.XmlPullParser

/**
 * XLSX importer (Groups 6-8, phases X1-X9). Resolves cell styles (fonts/fills/borders/number
 * formats/alignment), formulas & value types, sheet layout (column widths, row heights, merges,
 * frozen panes, hidden/visibility, tab color), print & page setup, defined names, data validation,
 * conditional formatting, comments, hyperlinks, and embedded images/charts. Best-effort onto ODF.
 */
internal object OoxmlXlsx {

    fun import(pkg: OoxmlPackage, fileName: String): OdfDocument.Spreadsheet {
        val theme = OoxmlTheme.parse(pkg.entries["xl/theme/theme1.xml"])
        val shared = pkg.entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val styles = pkg.entries["xl/styles.xml"]?.let { parseStyles(it, theme) } ?: StyleTable()
        val wbRels = pkg.relsFor("xl/workbook.xml")
        val wb = pkg.entries["xl/workbook.xml"]?.let { parseWorkbook(it) } ?: Workbook(emptyList(), emptyList())
        val ctx = XlsxImportCtx(theme, shared, styles, wbRels, wb)
        val namedRanges = mutableListOf<OdfNamedRange>()
        val validations = mutableListOf<OdfDataValidation>()
        collectWorkbookNames(ctx, namedRanges)
        val sheets = buildWorkbookSheets(pkg, ctx, namedRanges, validations)
        return OdfDocument.Spreadsheet(
            title = fileName,
            sheets = sheets,
            metadata = OoxmlMetadata.parse(pkg),
            images = collectImages(sheets),
            namedRanges = namedRanges,
            validations = validations
        )
    }

    private class XlsxImportCtx(
        val theme: OoxmlTheme,
        val shared: List<String>,
        val styles: StyleTable,
        val wbRels: Map<String, OoxmlPackage.Rel>,
        val wb: Workbook,
        val printRanges: HashMap<String, String> = HashMap(),
    )

    private fun collectWorkbookNames(ctx: XlsxImportCtx, namedRanges: MutableList<OdfNamedRange>) {
        for (dn in ctx.wb.definedNames) {
            when {
                dn.name == "_xlnm.Print_Area" -> dn.localSheet?.let { idx ->
                    ctx.wb.sheets.getOrNull(idx)?.let { ctx.printRanges[it.name] = a1RefToOdf(dn.value) }
                }
                dn.name.startsWith("_xlnm") -> {}
                else -> namedRanges.add(OdfNamedRange(dn.name, a1RefToOdf(dn.value)))
            }
        }
    }

    private fun buildWorkbookSheets(
        pkg: OoxmlPackage, ctx: XlsxImportCtx, namedRanges: MutableList<OdfNamedRange>,
        validations: MutableList<OdfDataValidation>,
    ): List<OdfSheet> {
        val sheets = mutableListOf<OdfSheet>()
        for (wsheet in ctx.wb.sheets) {
            val target = ctx.wbRels[wsheet.rId]?.target
            val xml = target?.let { pkg.entries[it] }
            if (target != null && xml != null) {
                sheets.add(parseWorksheet(
                    pkg,
                    target,
                    xml,
                    wsheet,
                    ctx.shared,
                    ctx.styles,
                    ctx.theme,
                    validations,
                    ctx.printRanges[wsheet.name],
                    namedRanges))
            }
        }
        if (sheets.isEmpty()) collectFallbackSheets(pkg, ctx, namedRanges, validations, sheets)
        if (sheets.isEmpty()) sheets.add(OdfSheet("Sheet 1", emptyList()))
        return sheets
    }

    private fun collectFallbackSheets(
        pkg: OoxmlPackage, ctx: XlsxImportCtx, namedRanges: MutableList<OdfNamedRange>,
        validations: MutableList<OdfDataValidation>, sheets: MutableList<OdfSheet>,
    ) {
        pkg.entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.substringAfterLast("sheet").substringBefore(".xml").toIntOrNull() ?: 0 }
            .forEachIndexed { i, path ->
                sheets.add(parseWorksheet(
                    pkg,
                    path,
                    pkg.entries[path]!!,
                    WbSheet("Sheet ${i + 1}", "", false),
                    ctx.shared,
                    ctx.styles,
                    ctx.theme,
                    validations,
                    null,
                    namedRanges))
            }
    }

    private fun collectImages(sheets: List<OdfSheet>): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        for (s in sheets) {
            for (el in s.floating) {
                if (el is OdfSlideElement.Frame) el.frame.image?.let { out[it.path] = it.imageData }
            }
        }
        return out
    }

    // ---- Workbook ----

    private class WbSheet(val name: String, val rId: String, val hidden: Boolean)
    private class DefinedName(val name: String, val value: String, val localSheet: Int?)
    private class Workbook(val sheets: List<WbSheet>, val definedNames: List<DefinedName>)

    private fun parseWorkbook(xml: String): Workbook {
        val parser = OoxmlXml.newParser(xml)
        val sheets = mutableListOf<WbSheet>()
        val names = mutableListOf<DefinedName>()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "sheet" -> {
                    val name = OoxmlXml.attr(parser, "name") ?: "Sheet ${sheets.size + 1}"
                    val rId = OoxmlXml.attrNs(
                        parser,
                        "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                        "id")
                        ?: OoxmlXml.attr(parser, "id") ?: ""
                    val hidden = OoxmlXml.attr(parser, "state").let { it == "hidden" || it == "veryHidden" }
                    sheets.add(WbSheet(name, rId, hidden))
                }
                "definedName" -> {
                    val name = OoxmlXml.attr(parser, "name") ?: ""
                    val local = OoxmlXml.attr(parser, "localSheetId")?.toIntOrNull()
                    val value = OoxmlXml.readElementText(parser, "definedName")
                    if (name.isNotBlank()) names.add(DefinedName(name, value, local))
                }
            }
            e = parser.next()
        }
        return Workbook(sheets, names)
    }

    private fun parseSharedStrings(xml: String): List<String> {
        val list = mutableListOf<String>()
        val parser = OoxmlXml.newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "si") {
                list.add(parseSharedItem(parser))
            }
            e = parser.next()
        }
        return list
    }

    /** One shared-string item (skipping phonetic-guide runs). */
    private fun parseSharedItem(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var phoneticDepth = -1
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "si")) {
            when {
                // Skip <t> inside <rPh> phonetic-guide runs (furigana), which aren't visible text.
                ev == XmlPullParser.START_TAG && parser.name == "rPh" -> phoneticDepth = parser.depth
                ev == XmlPullParser.END_TAG && parser.name == "rPh" -> phoneticDepth = -1
                ev == XmlPullParser.START_TAG && parser.name == "t" && phoneticDepth < 0 ->
                    sb.append(OoxmlXml.readElementText(parser, "t"))
            }
            if (ev == XmlPullParser.END_DOCUMENT) break
            ev = parser.next()
        }
        return sb.toString()
    }

    // ---- Styles ----

    private class Font(
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strike: Boolean,
        val color: Long?,
        val size: Float?)
    private class Xf(
        val numFmtId: Int,
        val fontId: Int,
        val fillId: Int,
        val borderId: Int,
        val applyFont: Boolean,
        val applyFill: Boolean,
        val applyBorder: Boolean,
        val applyNumberFormat: Boolean,
        val applyAlignment: Boolean,
        val halign: String?,
        val valign: String?,
        val wrap: Boolean,
        val rotation: Int,
        val indent: Int,
    )
    private class Dxf(val fill: Long?, val fontColor: Long?)
    private class StyleTable(
        val fonts: List<Font> = emptyList(),
        val fills: List<Long?> = emptyList(),
        val borders: List<OdfBorders> = emptyList(),
        val numFmts: Map<Int, String> = emptyMap(),
        val cellXfs: List<Xf> = emptyList(),
        val dxfs: List<Dxf> = emptyList()
    ) {
        fun numberFormat(numFmtId: Int): OdfNumberFormat? =
            numFmts[numFmtId]?.let { ExcelNumFmt.parse(it) } ?: ExcelNumFmt.forBuiltin(numFmtId)
        fun isDateFmt(numFmtId: Int): Boolean =
            numFmts[numFmtId]?.let { ExcelNumFmt.parse(it)?.let { f -> f.isDate || f.isTime } == true }
                ?: ExcelNumFmt.isDateTimeBuiltin(numFmtId)
    }

    private fun parseStyles(xml: String, theme: OoxmlTheme): StyleTable {
        val parser = OoxmlXml.newParser(xml)
        val table = StyleAcc()
        var section = ""  // "cellXfs" | "dxfs" | "cellStyleXfs"
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                section = applyStyleStart(parser, theme, table, section)
            } else if (e == XmlPullParser.END_TAG) when (parser.name) {
                "fonts", "fills", "borders", "cellXfs", "cellStyleXfs", "dxfs" -> section = ""
            }
            e = parser.next()
        }
        return StyleTable(table.fonts, table.fills, table.borders, table.numFmts, table.cellXfs, table.dxfs)
    }

    /** Style-table accumulation state. */
    private class StyleAcc(
        val fonts: MutableList<Font> = mutableListOf(),
        val fills: MutableList<Long?> = mutableListOf(),
        val borders: MutableList<OdfBorders> = mutableListOf(),
        val numFmts: HashMap<Int, String> = HashMap(),
        val cellXfs: MutableList<Xf> = mutableListOf(),
        val dxfs: MutableList<Dxf> = mutableListOf(),
    )

    /** Apply one styles start tag; returns the (possibly updated) section. */
    private fun applyStyleStart(
        parser: XmlPullParser,
        theme: OoxmlTheme,
        table: StyleAcc,
        section: String,
    ): String {
        var s = applyStyleSectionTag(parser, section)
        applyStyleEntryTag(parser, theme, table, s)
        return s
    }

    private fun applyStyleSectionTag(parser: XmlPullParser, section: String): String {
        return when (parser.name) {
            "fonts" -> "fonts"
            "fills" -> "fills"
            "borders" -> "borders"
            "cellStyleXfs" -> "cellStyleXfs"
            "cellXfs" -> "cellXfs"
            "dxfs" -> "dxfs"
            else -> section
        }
    }

    private fun applyStyleEntryTag(parser: XmlPullParser, theme: OoxmlTheme, table: StyleAcc, section: String) {
        when (parser.name) {
            "numFmt" -> applyNumFmtTag(parser, table)
            "font" -> if (section == "fonts") table.fonts.add(parseFont(parser, theme))
            "fill" -> if (section == "fills") table.fills.add(parseFill(parser, theme))
            "border" -> if (section == "borders") table.borders.add(parseXlsxBorder(parser))
            "xf" -> if (section == "cellXfs") table.cellXfs.add(parseXf(parser))
            "dxf" -> if (section == "dxfs") table.dxfs.add(parseDxf(parser, theme))
        }
    }

    private fun applyNumFmtTag(parser: XmlPullParser, table: StyleAcc) {
        val id = OoxmlXml.attr(parser, "numFmtId")?.toIntOrNull()
        val code = OoxmlXml.attr(parser, "formatCode")
        if (id != null && code != null) table.numFmts[id] = code
    }

    private fun parseFont(parser: XmlPullParser, theme: OoxmlTheme): Font {
        val depth = parser.depth
        var bold = false; var italic = false; var underline = false; var strike = false
        var color: Long? = null; var size: Float? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "font")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "b" -> bold = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "i" -> italic = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "u" -> underline = OoxmlXml.attr(parser, "val") != "none"
                "strike" -> strike = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "val"))
                "sz" -> size = OoxmlXml.attr(parser, "val")?.toFloatOrNull()
                "color" -> color = parseColorAttr(parser, theme)
            }
            e = parser.next()
        }
        return Font(bold, italic, underline, strike, color, size)
    }

    private fun parseFill(parser: XmlPullParser, theme: OoxmlTheme): Long? {
        val depth = parser.depth
        var fg: Long? = null; var patternType: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "fill")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "patternFill" -> patternType = OoxmlXml.attr(parser, "patternType")
                "fgColor" -> fg = parseColorAttr(parser, theme)
            }
            e = parser.next()
        }
        return if (patternType == null || patternType == "none") null else fg
    }

    private fun parseXlsxBorder(parser: XmlPullParser): OdfBorders {
        val depth = parser.depth
        var top: String? = null; var bottom: String? = null; var left: String? = null; var right: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "border")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) {
                val edge = parser.name
                if (edge in setOf("top", "bottom", "left", "right")) {
                    val style = OoxmlXml.attr(parser, "style")
                    if (style != null && style != "none") {
                        val kind = borderStyleKind(style)
                        val v = "%.2fpt %s #000000".format(borderWeight(style), kind)
                        when (edge) {
                            "top" -> top = v
                            "bottom" -> bottom = v
                            "left" -> left = v
                            "right" -> right = v
                        }
                    }
                }
            }
            e = parser.next()
        }
        return OdfBorders(top, right, bottom, left)
    }

    private const val BORDER_THIN = 0.5f
    private const val BORDER_MEDIUM = 1.5f
    private const val BORDER_THICK = 2.5f
    private const val BORDER_DEFAULT = 1f
    private const val PALETTE_SYSTEM_FG = 64
    private const val PALETTE_SYSTEM_BG = 65
    private const val SYSTEM_FG_RGB = 0x000000L
    private const val SYSTEM_BG_RGB = 0xFFFFFFL
    private const val FULL_ALPHA = 0xFF000000L
    private const val OOXML_THOUSANDTHS = 100000

    private fun borderWeight(style: String): Float = when (style) {
        "thin", "hair" -> BORDER_THIN
        "medium", "mediumDashed" -> BORDER_MEDIUM
        "thick" -> BORDER_THICK
        else -> BORDER_DEFAULT
    }

    private fun borderStyleKind(style: String): String = when {
        style.contains("dash", true) -> "dashed"
        style.contains("dot", true) -> "dotted"
        style == "double" -> "double"
        else -> "solid"
    }

    private fun parseXf(parser: XmlPullParser): Xf {
        val depth = parser.depth
        val numFmtId = OoxmlXml.attr(parser, "numFmtId")?.toIntOrNull() ?: 0
        val fontId = OoxmlXml.attr(parser, "fontId")?.toIntOrNull() ?: 0
        val fillId = OoxmlXml.attr(parser, "fillId")?.toIntOrNull() ?: 0
        val borderId = OoxmlXml.attr(parser, "borderId")?.toIntOrNull() ?: 0
        val applyFont = OoxmlXml.boolAttr(OoxmlXml.attr(parser, "applyFont")) && OoxmlXml.attr(
            parser,
            "applyFont") != null
        val applyFill = OoxmlXml.attr(parser, "applyFill") == "1"
        val applyBorder = OoxmlXml.attr(parser, "applyBorder") == "1"
        val applyNum = OoxmlXml.attr(parser, "applyNumberFormat") == "1"
        val applyAlign = OoxmlXml.attr(parser, "applyAlignment") == "1"
        val align = parseXfAlignment(parser, depth)
        return Xf(
            numFmtId,
            fontId,
            fillId,
            borderId,
            applyFont,
            applyFill,
            applyBorder,
            applyNum,
            applyAlign,
            align.first,
            align.second,
            align.third,
            align.fourth,
            align.fifth)
    }

    /** Alignment child of an xf (halign, valign, wrap, rotation, indent). */
    private fun parseXfAlignment(
        parser: XmlPullParser,
        depth: Int,
    ): Quintuple<String?, String?, Boolean, Int, Int> {
        var halign: String? = null; var valign: String? = null; var wrap = false; var rotation = 0; var indent = 0
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "xf")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "alignment") {
                halign = OoxmlXml.attr(parser, "horizontal")
                valign = OoxmlXml.attr(parser, "vertical")
                wrap = OoxmlXml.attr(parser, "wrapText") == "1"
                rotation = OoxmlXml.attr(parser, "textRotation")?.toIntOrNull() ?: 0
                indent = OoxmlXml.attr(parser, "indent")?.toIntOrNull() ?: 0
            }
            e = parser.next()
        }
        return Quintuple(halign, valign, wrap, rotation, indent)
    }

    /** Five-element tuple. */
    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E,
    )

    private fun parseDxf(parser: XmlPullParser, theme: OoxmlTheme): Dxf {
        val depth = parser.depth
        var fill: Long? = null; var fontColor: Long? = null; var inFont = false
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "dxf")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "font" -> inFont = true
                "color" -> if (inFont) fontColor = parseColorAttr(parser, theme)
                "fgColor", "bgColor" -> if (fill == null) fill = parseColorAttr(parser, theme)
            } else if (e == XmlPullParser.END_TAG && parser.name == "font") inFont = false
            e = parser.next()
        }
        return Dxf(fill, fontColor)
    }

    /** Reads an xlsx color attribute set (rgb / theme+tint / indexed) on the current element. */
    private fun parseColorAttr(parser: XmlPullParser, theme: OoxmlTheme): Long? {
        OoxmlUnits.hexColor(OoxmlXml.attr(parser, "rgb"))?.let { return it }
        val themeIdx = OoxmlXml.attr(parser, "theme")?.toIntOrNull()
        if (themeIdx != null) {
            val base = theme.colors[themeSlot(themeIdx)] ?: return null
            val tint = OoxmlXml.attr(parser, "tint")?.toDoubleOrNull() ?: 0.0
            return applyExcelTint(base, tint)
        }
        OoxmlXml.attr(parser, "indexed")?.toIntOrNull()?.let { return indexedColor(it) }
        return null
    }

    /** Resolves a legacy indexed color (BIFF8 default palette) to 0xFFRRGGBB. */
    private fun indexedColor(idx: Int): Long? {
        val rgb = INDEXED_PALETTE.getOrNull(idx) ?: when (idx) {
            PALETTE_SYSTEM_FG -> SYSTEM_FG_RGB   // system foreground
            PALETTE_SYSTEM_BG -> SYSTEM_BG_RGB   // system background
            else -> return null
        }
        return FULL_ALPHA or rgb
    }

    private val INDEXED_PALETTE = longArrayOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333
    )

    private fun themeSlot(idx: Int): String = THEME_SLOTS[idx] ?: "dk1"

    private val THEME_SLOTS = mapOf(
        0 to "lt1", 1 to "dk1", 2 to "lt2", 3 to "dk2",
        4 to "accent1", 5 to "accent2", 6 to "accent3",
        7 to "accent4", 8 to "accent5", 9 to "accent6",
        10 to "hlink", 11 to "folhlink",
    )

    private fun applyExcelTint(base: Long, tint: Double): Long {
        if (tint == 0.0) return base
        val scaled = ((1 - kotlin.math.abs(tint)) * OOXML_THOUSANDTHS).toInt()
        return if (tint < 0) {
            OoxmlUnits.applyTransforms(base, shade = scaled)
        } else {
            OoxmlUnits.applyTransforms(base, tint = scaled)
        }
    }

    // ---- Worksheet ----

    private fun parseWorksheet(
        pkg: OoxmlPackage, part: String, xml: String, wsheet: WbSheet,
        shared: List<String>, styles: StyleTable, theme: OoxmlTheme,
        validations: MutableList<OdfDataValidation>, printRange: String?, namedRanges: MutableList<OdfNamedRange>
    ): OdfSheet {
        val rels = pkg.relsFor(part)
        val parser = OoxmlXml.newParser(xml)
        val acc = SheetAcc()
        val sharedFormulas = HashMap<Int, SharedFormulaDef>()

        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> applyWorksheetStart(
                    parser, theme, acc, shared, styles, sharedFormulas, validations)
                XmlPullParser.END_TAG -> applyWorksheetEnd(parser, acc)
            }
            e = parser.next()
        }

        var sheet = OdfSheet(
            name = wsheet.name,
            rows = acc.rows,
            columnWidths = buildColWidths(acc.colWidths, acc.rows),
            freezeRows = acc.freezeRows,
            freezeCols = acc.freezeCols,
            rowHeights = acc.rowHeights.toList(),
            hiddenRows = acc.hiddenRows,
            hiddenCols = acc.hiddenCols,
            printRanges = printRange,
            hidden = wsheet.hidden,
            tabColor = acc.tabColor
        )

        sheet = applyMerges(sheet, acc.merges)
        sheet = applyHyperlinks(sheet, acc.hyperlinkRefs, rels)
        sheet = applyCondFormats(sheet, acc.condFormats, styles)
        sheet = applyComments(pkg, rels, sheet)
        val floating = OoxmlXlsxHelper.parseDrawings(
            pkg, rels, acc.drawingRid, theme, acc.colWidths, acc.rowHeights)
        if (floating.isNotEmpty()) sheet = sheet.copy(floating = floating)
        if (acc.valRefs.isNotEmpty()) sheet = applyValidationNames(sheet, acc.valRefs)
        // Structured tables (xl/tables/tableN.xml) -> named ranges.
        for (rel in rels.values) if (rel.type?.endsWith("table") == true) {
            pkg.entries[rel.target]?.let { tx -> parseTablePart(tx, wsheet.name)?.let { namedRanges.add(it) } }
        }
        return sheet
    }

    /** Worksheet accumulation state. */
    private class SheetAcc(
        val rows: MutableList<OdfRow> = mutableListOf(),
        val rowHeights: MutableList<Float?> = mutableListOf(),
        val hiddenRows: HashSet<Int> = HashSet(),
        val hiddenCols: HashSet<Int> = HashSet(),
        val colWidths: HashMap<Int, Float> = HashMap(),
        val merges: MutableList<String> = mutableListOf(),
        var freezeRows: Int = 0,
        var freezeCols: Int = 0,
        var tabColor: Long? = null,
        val hyperlinkRefs: MutableList<Triple<String, String?, String?>> = mutableListOf(),
        val condFormats: MutableList<Pair<String, List<CfRule>>> = mutableListOf(),
        val valRefs: MutableList<Pair<String, List<String>>> = mutableListOf(),
        var curCells: MutableList<Pair<Int, OdfCell>>? = null,
        var curRowHidden: Boolean = false,
        var rowIndex: Int = 0,
        var drawingRid: String? = null,
    )

    /** Apply one worksheet start tag. */
    private fun applyWorksheetStart(
        parser: XmlPullParser,
        theme: OoxmlTheme,
        acc: SheetAcc,
        shared: List<String>,
        styles: StyleTable,
        sharedFormulas: HashMap<Int, SharedFormulaDef>,
        validations: MutableList<OdfDataValidation>,
    ) {
        if (applyWorksheetStyleTag(parser, theme, acc)) return
        applyWorksheetDataTag(parser, acc, shared, styles, sharedFormulas, validations)
    }

    private fun applyWorksheetStyleTag(parser: XmlPullParser, theme: OoxmlTheme, acc: SheetAcc): Boolean {
        when (parser.name) {
            "sheetPr" -> {}
            "tabColor" -> acc.tabColor = parseColorAttr(parser, theme)
            "pane" -> applyPane(parser, acc)
            "col" -> applyCol(parser, acc)
            else -> return false
        }
        return true
    }

    private fun applyWorksheetDataTag(
        parser: XmlPullParser,
        acc: SheetAcc,
        shared: List<String>,
        styles: StyleTable,
        sharedFormulas: HashMap<Int, SharedFormulaDef>,
        validations: MutableList<OdfDataValidation>,
    ) {
        when (parser.name) {
            "row" -> applyRowStart(parser, acc)
            "c" -> applyCellStart(parser, acc, shared, styles, sharedFormulas)
            "mergeCell" -> OoxmlXml.attr(parser, "ref")?.let { acc.merges.add(it) }
            "dataValidation" -> applyDataValidationTag(parser, acc, validations)
            "conditionalFormatting" -> applyCondFmtTag(parser, acc)
            "hyperlink" -> applyHyperlink(parser, acc)
            "drawing" -> applyDrawingTag(parser, acc)
        }
    }

    private fun applyDataValidationTag(
        parser: XmlPullParser, acc: SheetAcc, validations: MutableList<OdfDataValidation>,
    ) {
        parseDataValidation(parser)?.let { (v, refs) ->
            validations.add(v)
            acc.valRefs.add(v.name to refs)
        }
    }

    private fun applyCondFmtTag(parser: XmlPullParser, acc: SheetAcc) {
        val sqref = OoxmlXml.attr(parser, "sqref") ?: ""
        acc.condFormats.add(sqref to parseCfRules(parser))
    }

    private fun applyDrawingTag(parser: XmlPullParser, acc: SheetAcc) {
        acc.drawingRid = OoxmlXml.attrNs(
            parser,
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "id")
    }

    /** Pane (freeze) attributes. */
    private fun applyPane(parser: XmlPullParser, acc: SheetAcc) {
        if (OoxmlXml.attr(parser, "state").let { it == "frozen" || it == "frozenSplit" }) {
            acc.freezeCols = OoxmlXml.attr(parser, "xSplit")?.toDoubleOrNull()?.toInt() ?: 0
            acc.freezeRows = OoxmlXml.attr(parser, "ySplit")?.toDoubleOrNull()?.toInt() ?: 0
        }
    }

    /** Column width/hidden attributes. */
    private fun applyCol(parser: XmlPullParser, acc: SheetAcc) {
        val min = OoxmlXml.attr(parser, "min")?.toIntOrNull() ?: 1
        val max = OoxmlXml.attr(parser, "max")?.toIntOrNull() ?: min
        val width = OoxmlXml.attr(parser, "width")?.toFloatOrNull()
        val hidden = OoxmlXml.attr(parser, "hidden") == "1"
        for (c in (min - 1) until max) {
            width?.let { acc.colWidths[c] = OoxmlUnits.excelColWidthToPx(it) }
            if (hidden) acc.hiddenCols.add(c)
        }
    }

    /** Row start: new cell list + height/hidden. */
    private fun applyRowStart(parser: XmlPullParser, acc: SheetAcc) {
        acc.curCells = mutableListOf()
        acc.rowIndex = (OoxmlXml.attr(parser, "r")?.toIntOrNull() ?: (acc.rows.size + 1)) - 1
        acc.curRowHidden = OoxmlXml.attr(parser, "hidden") == "1"
        val ht = OoxmlXml.attr(parser, "ht")?.toFloatOrNull()
        while (acc.rowHeights.size <= acc.rowIndex) acc.rowHeights.add(null)
        acc.rowHeights[acc.rowIndex] = ht?.let { OoxmlUnits.ptToPx(it) }
        if (acc.curRowHidden) acc.hiddenRows.add(acc.rowIndex)
    }

    /** Cell start: parse and append. */
    private fun applyCellStart(
        parser: XmlPullParser,
        acc: SheetAcc,
        shared: List<String>,
        styles: StyleTable,
        sharedFormulas: HashMap<Int, SharedFormulaDef>,
    ) {
        val curCells = acc.curCells ?: return
        val ref = OoxmlXml.attr(parser, "r") ?: ""
        val ci = if (ref.isNotEmpty()) OoxmlXml.colIndex(ref) else curCells.size
        curCells.add(ci to parseCell(parser, shared, styles, sharedFormulas, acc.rowIndex, ci))
    }

    /** Hyperlink attributes. */
    private fun applyHyperlink(parser: XmlPullParser, acc: SheetAcc) {
        val ref = OoxmlXml.attr(parser, "ref")
        val rId = OoxmlXml.attrNs(
            parser,
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "id")
        val loc = OoxmlXml.attr(parser, "location")
        if (ref != null) acc.hyperlinkRefs.add(Triple(ref, rId, loc))
    }

    /** Apply one worksheet end tag (row finalization). */
    private fun applyWorksheetEnd(parser: XmlPullParser, acc: SheetAcc) {
        if (parser.name != "row") return
        val curCells = acc.curCells ?: return
        val maxCol = curCells.maxOfOrNull { it.first } ?: -1
        val arr = MutableList(maxCol + 1) { OdfCell(text = "") }
        for ((ci, cell) in curCells) if (ci in arr.indices) arr[ci] = cell
        while (acc.rows.size < acc.rowIndex) { acc.rows.add(OdfRow(emptyList())); }
        if (acc.rows.size == acc.rowIndex) acc.rows.add(OdfRow(arr))
        else if (acc.rowIndex in acc.rows.indices) acc.rows[acc.rowIndex] =
            OdfRow(arr) else acc.rows.add(OdfRow(arr))
        acc.curCells = null
    }

    private fun parseTablePart(xml: String, sheetName: String): OdfNamedRange? {
        val parser = OoxmlXml.newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "table") {
                val name = OoxmlXml.attr(parser, "displayName") ?: OoxmlXml.attr(parser, "name") ?: return null
                val ref = OoxmlXml.attr(parser, "ref") ?: return null
                val addr = ref.split(":").joinToString(":") { "$sheetName.$it" }
                return OdfNamedRange(name, addr)
            }
            e = parser.next()
        }
        return null
    }

    private fun buildColWidths(map: Map<Int, Float>, rows: List<OdfRow>): List<Float?> {
        if (map.isEmpty()) return emptyList()
        val maxCol = maxOf(map.keys.maxOrNull() ?: 0, rows.maxOfOrNull { it.cells.size - 1 } ?: 0)
        return (0..maxCol).map { map[it] }
    }

    /** Master definition of a shared formula: the master's A1 body and its 0-based row/col. */
    private class SharedFormulaDef(val a1: String, val row: Int, val col: Int)

    private fun parseCell(
        parser: XmlPullParser, shared: List<String>, styles: StyleTable,
        sharedFormulas: MutableMap<Int, SharedFormulaDef>, cellRow: Int, cellCol: Int
    ): OdfCell {
        val type = OoxmlXml.attr(parser, "t")
        val styleIdx = OoxmlXml.attr(parser, "s")?.toIntOrNull()
        val cell = readCellContent(parser, sharedFormulas, cellRow, cellCol)
        val xf = styleIdx?.let { styles.cellXfs.getOrNull(it) }
        val cellStyle = xf?.let { resolveCellStyle(it, styles) } ?: CellStyle()
        val isDate = xf?.let { styles.isDateFmt(it.numFmtId) } == true
        val base = baseCell(type, cell.value, cell.inlineText, shared, isDate)
        return styleCell(base, xf, styles, cellStyle, cell.formula)
    }

    private fun styleCell(
        base: OdfCell, xf: Xf?, styles: StyleTable, cellStyle: CellStyle, formula: String?,
    ): OdfCell {
        val resolvedFmt = xf?.let {
            if (it.applyNumberFormat || it.numFmtId != 0) styles.numberFormat(it.numFmtId) else null
        } ?: base.numberFormat
        // Excel stores the raw number (e.g. a date serial "45292"); the display string must be
        // formatted per the number format so dates/percent/currency don't show as bare numbers.
        val fmtForDisplay = resolvedFmt ?: if (base.valueType == "date") OdfNumberFormat(isDate = true) else null
        val displayText = if (base.numberValue != null && base.valueType != "boolean" && fmtForDisplay != null)
            OfficeNative.formatValue(base.numberValue!!, fmtForDisplay) else base.text
        return base.copy(
            text = displayText,
            formula = formula ?: base.formula,
            backgroundColor = cellStyle.fill,
            textColor = cellStyle.fontColor,
            bold = cellStyle.bold,
            italic = cellStyle.italic,
            alignment = cellStyle.align,
            borders = cellStyle.borders?.takeIf { !it.isEmpty() },
            borderColor = cellStyle.borders?.let { OdfBorders.renderColor(it.top ?: it.left) },
            numberFormat = resolvedFmt,
            wrap = cellStyle.wrap,
            textRotation = cellStyle.rotation,
            verticalAlign = cellStyle.valign
        )
    }

    /** Raw cell content (value/inline/formula). */
    private class CellContent(
        var value: String? = null,
        var inlineText: String? = null,
        var formula: String? = null,
    )

    /** Read the value/inline/formula children of a cell. */
    private fun readCellContent(
        parser: XmlPullParser,
        sharedFormulas: MutableMap<Int, SharedFormulaDef>,
        cellRow: Int,
        cellCol: Int,
    ): CellContent {
        val depth = parser.depth
        val cell = CellContent()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "c")) {
            if (e == XmlPullParser.START_TAG) applyCellChild(parser, cell, sharedFormulas, cellRow, cellCol)
            if (e == XmlPullParser.END_DOCUMENT) break
            e = parser.next()
        }
        return cell
    }

    /** Apply one cell child tag. */
    private fun applyCellChild(
        parser: XmlPullParser,
        cell: CellContent,
        sharedFormulas: MutableMap<Int, SharedFormulaDef>,
        cellRow: Int,
        cellCol: Int,
    ) {
        when (parser.name) {
            "v" -> cell.value = OoxmlXml.readElementText(parser, "v")
            "t" -> cell.inlineText = (cell.inlineText ?: "") + OoxmlXml.readElementText(parser, "t")
            "f" -> cell.formula = readCellFormula(parser, sharedFormulas, cellRow, cellCol)
        }
    }

    /** Cell formula with shared-formula resolution. */
    private fun readCellFormula(
        parser: XmlPullParser,
        sharedFormulas: MutableMap<Int, SharedFormulaDef>,
        cellRow: Int,
        cellCol: Int,
    ): String? {
        val ft = OoxmlXml.attr(parser, "t")
        val si = OoxmlXml.attr(parser, "si")?.toIntOrNull()
        val f = OoxmlXml.readElementText(parser, "f")
        return when {
            // Master of a shared formula: remember its A1 body + position for dependents.
            ft == "shared" && f.isNotBlank() -> {
                if (si != null) sharedFormulas[si] = SharedFormulaDef(f, cellRow, cellCol)
                ExcelFormula.toOdf(f)
            }
            // Dependent: re-base the master's relative refs to this cell before translating.
            ft == "shared" && f.isBlank() -> si?.let { sharedFormulas[it] }?.let { def ->
                ExcelFormula.toOdf(ExcelFormula.shift(def.a1, cellRow - def.row, cellCol - def.col))
            }
            f.isNotBlank() -> ExcelFormula.toOdf(f)
            else -> null
        }
    }

    /** Base cell from the type + raw content. */
    private fun baseCell(
        type: String?,
        value: String?,
        inlineText: String?,
        shared: List<String>,
        isDate: Boolean,
    ): OdfCell {
        if (isStringType(type)) return stringCell(type, value, inlineText, shared)
        if (type == "b") return boolCell(value)
        return numericCell(value, isDate)
    }

    private fun isStringType(type: String?): Boolean =
        type == "s" || type == "inlineStr" || type == "str" || type == "e"

    private fun stringCell(type: String?, value: String?, inlineText: String?, shared: List<String>): OdfCell {
        return when (type) {
            "s" -> OdfCell(text = shared.getOrNull(value?.toIntOrNull() ?: -1) ?: "", valueType = "string")
            "inlineStr" -> OdfCell(text = inlineText ?: "", valueType = "string")
            "str" -> OdfCell(text = value ?: "", valueType = "string")
            else -> OdfCell(text = value ?: "#ERR", valueType = "string")
        }
    }

    private fun boolCell(value: String?): OdfCell {
        return OdfCell(
            text = if (value == "1") "TRUE" else "FALSE",
            numberValue = if (value == "1") 1.0 else 0.0,
            valueType = "boolean")
    }

    private fun numericCell(value: String?, isDate: Boolean): OdfCell {
        val num = value?.toDoubleOrNull()
        if (num != null) return OdfCell(text = value, numberValue = num, valueType = if (isDate) "date" else "float")
        return OdfCell(text = value ?: "")
    }

    private class CellStyle(
        val fill: Long? = null, val fontColor: Long? = null, val bold: Boolean = false, val italic: Boolean = false,
        val align: TextAlign? = null, val valign: String? = null, val wrap: Boolean = false, val rotation: Int = 0,
        val borders: OdfBorders? = null
    )

    private fun resolveCellStyle(xf: Xf, styles: StyleTable): CellStyle {
        val font = styles.fonts.getOrNull(xf.fontId)
        val fill = if (xf.fillId > 0) styles.fills.getOrNull(xf.fillId) else null
        val borders = if (xf.borderId > 0) styles.borders.getOrNull(xf.borderId) else null
        return CellStyle(
            fill = fill,
            fontColor = font?.color,
            bold = font?.bold == true,
            italic = font?.italic == true,
            align = when (xf.halign) {
                "center" -> TextAlign.Center
                "right" -> TextAlign.End
                "left" -> TextAlign.Start
                "justify" -> TextAlign.Justify
                else -> null
            },
            valign = when (xf.valign) {
                "center" -> "middle"
                "top" -> "top"
                "bottom" -> "bottom"
                else -> null
            },
            wrap = xf.wrap,
            rotation = xf.rotation,
            borders = borders
        )
    }

    // ---- Post-processing (merges, hyperlinks, condformat, comments, validation) ----

    private fun applyMerges(sheet: OdfSheet, merges: List<String>): OdfSheet {
        if (merges.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for (m in merges) {
            applyMerge(grid, m)
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    /** Ensure the grid covers cell (r, c). */
    private fun ensureMergeCell(grid: MutableList<MutableList<OdfCell>>, r: Int, c: Int) {
        while (grid.size <= r) grid.add(mutableListOf())
        while (grid[r].size <= c) grid[r].add(OdfCell(text = ""))
    }

    /** Apply one merge range. */
    private fun applyMerge(grid: MutableList<MutableList<OdfCell>>, m: String) {
        val (a, b) = m.split(":").let { if (it.size == 2) it[0] to it[1] else it[0] to it[0] }
        val r1 = OoxmlXml.rowIndex(a); val c1 = OoxmlXml.colIndex(a)
        val r2 = OoxmlXml.rowIndex(b); val c2 = OoxmlXml.colIndex(b)
        if (r1 < 0 || c1 < 0) return
        ensureMergeCell(grid, r1, c1)
        grid[r1][c1] = grid[r1][c1].copy(
            spannedColumns = (c2 - c1 + 1).coerceAtLeast(1),
            rowSpan = (r2 - r1 + 1).coerceAtLeast(1))
        for (r in r1..r2) for (c in c1..c2) {
            if (r == r1 && c == c1) continue
            ensureMergeCell(grid, r, c)
            grid[r][c] = grid[r][c].copy(isCovered = true)
        }
    }

    private fun applyHyperlinks(
        sheet: OdfSheet,
        links: List<Triple<String,
        String?,
        String?>>,
        rels: Map<String,
        OoxmlPackage.Rel>): OdfSheet {
        if (links.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((ref, rId, loc) in links) {
            val first = ref.split(":").first()
            val r = OoxmlXml.rowIndex(first); val c = OoxmlXml.colIndex(first)
            val target = rId?.let { rels[it]?.target } ?: loc?.let { "#$it" }
            if (r in grid.indices && c in grid[r].indices && target != null) {
                grid[r][c] = grid[r][c].copy(hyperlink = target)
            }
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    private class CfRule(val condition: String, val bg: Long?, val fontColor: Long?)

    private fun parseCfRules(parser: XmlPullParser): List<CfRule> {
        val depth = parser.depth
        val rules = mutableListOf<CfRule>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "conditionalFormatting")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "cfRule") {
                parseCfRule(parser)?.let { rules.add(it) }
            }
            e = parser.next()
        }
        return rules
    }

    /** One cfRule (formulas + dxf reference). */
    private fun parseCfRule(parser: XmlPullParser): CfRule? {
        val type = OoxmlXml.attr(parser, "type")
        val op = OoxmlXml.attr(parser, "operator")
        val dxfId = OoxmlXml.attr(parser, "dxfId")?.toIntOrNull()
        val formulas = readCfFormulas(parser)
        if (formulas.isEmpty()) return null
        return when (type) {
            "cellIs" -> {
                val cond = cellIsCondition(op, formulas)
                CfRule(cond, null, null).let { it.copyWithDxf(dxfId) }
            }
            "expression" -> CfRule(formulas[0], null, null).let { it.copyWithDxf(dxfId) }
            else -> null
        }
    }

    /** Formula children of a cfRule. */
    private fun readCfFormulas(parser: XmlPullParser): List<String> {
        val d = parser.depth
        val formulas = mutableListOf<String>()
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == d && parser.name == "cfRule")) {
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG && parser.name == "formula") {
                formulas.add(OoxmlXml.readElementText(parser, "formula"))
            }
            ev = parser.next()
        }
        return formulas
    }

    // dxf colors resolved later against StyleTable; store id via a sentinel condition suffix.
    private fun CfRule.copyWithDxf(dxfId: Int?): CfRule = CfRule(
        if (dxfId != null) "$condition\u0000$dxfId" else condition,
        bg,
        fontColor)

    private fun cellIsCondition(op: String?, formulas: List<String>): String = when (op) {
        "greaterThan" -> "value()>${formulas[0]}"
        "lessThan" -> "value()<${formulas[0]}"
        "greaterThanOrEqual" -> "value()>=${formulas[0]}"
        "lessThanOrEqual" -> "value()<=${formulas[0]}"
        "equal" -> "value()=${formulas[0]}"
        "notEqual" -> "value()!=${formulas[0]}"
        "between" ->
            if (formulas.size >= 2) "value()>=${formulas[0]} and value()<=${formulas[1]}" else "value()>=${formulas[0]}"
        else -> "value()=${formulas.getOrElse(0) { "0" }}"
    }

    private fun applyCondFormats(sheet: OdfSheet, cfs: List<Pair<String, List<CfRule>>>, styles: StyleTable): OdfSheet {
        if (cfs.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((sqref, rules) in cfs) {
            val odfRules = rules.map { r ->
                val parts = r.condition.split('\u0000')
                val cond = parts[0]
                val dxf = parts.getOrNull(1)?.toIntOrNull()?.let { styles.dxfs.getOrNull(it) }
                OdfCondFormat(condition = cond, backgroundColor = dxf?.fill, textColor = dxf?.fontColor)
            }
            for (range in sqref.split(" ")) {
                val (a, b) = range.split(":").let { if (it.size == 2) it[0] to it[1] else it[0] to it[0] }
                val r1 = OoxmlXml.rowIndex(a); val c1 = OoxmlXml.colIndex(a)
                val r2 = OoxmlXml.rowIndex(b); val c2 = OoxmlXml.colIndex(b)
                for (r in r1..r2) for (c in c1..c2) {
                    if (r in grid.indices && c in grid[r].indices) grid[r][c] =
                        grid[r][c].copy(condFormats = grid[r][c].condFormats + odfRules)
                }
            }
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    private fun parseDataValidation(parser: XmlPullParser): Pair<OdfDataValidation, List<String>>? {
        val type = OoxmlXml.attr(parser, "type") ?: return null
        val sqref = OoxmlXml.attr(parser, "sqref") ?: return null
        val formulas = readValidationFormulas(parser)
        val name = "val_${sqref.replace(Regex("[^A-Za-z0-9]"), "_")}"
        return OdfDataValidation(name, validationCondition(type, formulas)) to sqref.split(" ")
    }

    /** Formula children of a dataValidation. */
    private fun readValidationFormulas(parser: XmlPullParser): List<String> {
        val depth = parser.depth
        val formulas = mutableListOf<String>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "dataValidation")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && (parser.name == "formula1" || parser.name == "formula2")) {
                formulas.add(OoxmlXml.readElementText(parser, parser.name))
            }
            e = parser.next()
        }
        return formulas
    }

    /** ODF validation condition for a type + formulas. */
    private fun validationCondition(type: String, formulas: List<String>): String = when (type) {
        "list" -> {
            val f = formulas.getOrElse(0) { "" }.trim('"')
            val values = f.split(",").joinToString(";") { "\"${it.trim()}\"" }
            "of:cell-content-is-in-list($values)"
        }
        "whole", "decimal" -> "of:cell-content()>=${formulas.getOrElse(0) { "0" }}"
        "textLength" -> "of:cell-content-text-length()>=${formulas.getOrElse(0) { "0" }}"
        else -> "of:cell-content()"
    }

    private fun applyValidationNames(sheet: OdfSheet, list: List<Pair<String, List<String>>>): OdfSheet {
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((name, refs) in list) for (range in refs) {
            val (a, b) = range.split(":").let { if (it.size == 2) it[0] to it[1] else it[0] to it[0] }
            val r1 = OoxmlXml.rowIndex(a); val c1 = OoxmlXml.colIndex(a)
            val r2 = OoxmlXml.rowIndex(b); val c2 = OoxmlXml.colIndex(b)
            for (r in r1..r2) for (c in c1..c2) if (r in grid.indices && c in grid[r].indices) grid[r][c] =
                grid[r][c].copy(validationName = name)
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    private fun applyComments(
        pkg: OoxmlPackage,
        rels: Map<String,
        OoxmlPackage.Rel>,
        sheet: OdfSheet): OdfSheet {
        val commentsPart = rels.values.firstOrNull { it.type?.endsWith("comments") == true }?.target ?: return sheet
        val xml = pkg.entries[commentsPart] ?: return sheet
        val comments = parseSheetComments(xml)
        if (comments.isEmpty()) return sheet
        val grid = sheet.rows.map { it.cells.toMutableList() }.toMutableList()
        for ((ref, ann) in comments) {
            val r = OoxmlXml.rowIndex(ref); val c = OoxmlXml.colIndex(ref)
            if (r in grid.indices && c in grid[r].indices) grid[r][c] = grid[r][c].copy(annotation = ann)
        }
        return sheet.copy(rows = grid.map { OdfRow(it) })
    }

    private fun parseSheetComments(xml: String): Map<String, OdfAnnotation> {
        val parser = OoxmlXml.newParser(xml)
        val authors = mutableListOf<String>()
        val out = LinkedHashMap<String, OdfAnnotation>()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "author" -> authors.add(OoxmlXml.readElementText(parser, "author"))
                    "comment" -> parseSheetComment(parser, authors)?.let { (ref, ann) -> out[ref] = ann }
                }
            }
            e = parser.next()
        }
        return out
    }

    /** One sheet comment (ref + annotation). */
    private fun parseSheetComment(
        parser: XmlPullParser,
        authors: List<String>,
    ): Pair<String, OdfAnnotation>? {
        val ref = OoxmlXml.attr(parser, "ref") ?: ""
        val aIdx = OoxmlXml.attr(parser, "authorId")?.toIntOrNull()
        val text = readCommentText(parser)
        if (ref.isBlank()) return null
        return ref to OdfAnnotation(
            author = aIdx?.let { authors.getOrNull(it) },
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text))))
        )
    }

    /** Text content of a comment element. */
    private fun readCommentText(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "comment")) {
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG && parser.name == "t") sb.append(OoxmlXml.readElementText(
                parser,
                "t"))
            ev = parser.next()
        }
        return sb.toString()
    }

    // ---- Helpers ----

    /** Converts an Excel A1 range like "Sheet1!$A$1:$D$20" to an ODF address "Sheet1.A1:Sheet1.D20". */
    private fun a1RefToOdf(ref: String): String {
        return ref.split(",").joinToString(" ") { part ->
            val p = part.trim()
            val sheet = if (p.contains("!")) p.substringBefore("!").trim('\'', '$') else null
            val range = p.substringAfter("!").replace("$", "")
            if (sheet != null) {
                if (range.contains(":")) {
                    val (a, b) = range.split(":", limit = 2)
                    "$sheet.$a:$sheet.$b"
                } else "$sheet.$range"
            } else range
        }
    }
}
