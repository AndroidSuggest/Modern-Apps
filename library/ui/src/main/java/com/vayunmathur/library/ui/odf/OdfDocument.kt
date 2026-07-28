package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection

sealed class OdfDocument {
    abstract val title: String
    abstract val metadata: OdfMetadata

    data class TextDocument(
        override val title: String,
        val content: List<OdfContentBlock>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap(),
        val footnotes: List<OdfFootnote> = emptyList(),
        val headerParagraphs: List<OdfParagraph> = emptyList(),
        val footerParagraphs: List<OdfParagraph> = emptyList(),
        val bookmarks: List<OdfBookmark> = emptyList(),
        val changes: List<OdfChange> = emptyList(),
        val pageSetup: OdfPageSetup? = null
    ) : OdfDocument()

    data class Spreadsheet(
        override val title: String,
        val sheets: List<OdfSheet>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap(),
        val namedRanges: List<OdfNamedRange> = emptyList(),
        val validations: List<OdfDataValidation> = emptyList()
    ) : OdfDocument()

    data class Presentation(
        override val title: String,
        val slides: List<OdfSlide>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap()
    ) : OdfDocument()

    data class Drawing(
        override val title: String,
        val pages: List<OdfSlide>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap()
    ) : OdfDocument()
}

/**
 * A tracked change region (text:changed-region, Priority 6). [type] is one of
 * "insertion", "deletion", "format-change". Body spans reference it via OdfSpan.changeId.
 */
data class OdfChange(
    val id: String,
    val type: String,
    val author: String? = null,
    val date: String? = null,
    val comment: String? = null
)

/**
 * Page geometry (style:page-layout-properties, Priority 7). All lengths in px@96
 * (cm = px / 37.795). Defaults are A4 portrait with 2cm margins.
 */
data class OdfPageSetup(
    val widthPx: Float = 793.7f,
    val heightPx: Float = 1122.5f,
    val marginLeftPx: Float = 75.6f,
    val marginRightPx: Float = 75.6f,
    val marginTopPx: Float = 75.6f,
    val marginBottomPx: Float = 75.6f
) {
    val isLandscape: Boolean get() = widthPx > heightPx
}

sealed class OdfContentBlock {
    data class Paragraph(val paragraph: OdfParagraph) : OdfContentBlock()
    data class Table(val table: OdfTable) : OdfContentBlock()
    data class Image(val image: OdfImage) : OdfContentBlock()
    data class Chart(val chart: OdfChart) : OdfContentBlock()
    data class Formula(val mathml: String) : OdfContentBlock()
    /** Live table of contents (text:table-of-content). Entries are the generated heading lines. */
    data class TableOfContents(val title: String, val entries: List<OdfParagraph>) : OdfContentBlock()
    /** Start of a text:section (round-trip). [columnCount] > 1 means a multi-column section. (Round 3) */
    data class SectionStart(val name: String, val columnCount: Int = 1) : OdfContentBlock()
    data object SectionEnd : OdfContentBlock()
    data object PageBreak : OdfContentBlock()
}

enum class ChartType { BAR, LINE, PIE, AREA, DONUT, SCATTER, STACKED_BAR, RADAR, BUBBLE }

data class OdfChartSeries(
    val name: String,
    val values: List<Float>,
    // Per-series fill color (chart:style-name -> fo:fill-color), preserved for round-trip. (Phase 5)
    val color: Long? = null,
    // Whether data labels are shown for this series (chart:data-label-*). (Phase 5)
    val dataLabels: Boolean = false
)

data class OdfChart(
    val type: ChartType,
    val categories: List<String>,
    val series: List<OdfChartSeries>,
    val title: String? = null,
    val subtitle: String? = null,
    val legend: Boolean = true,
    val xAxisTitle: String? = null,
    val yAxisTitle: String? = null,
    // Stacked/percent-stacked plotting (chart:stacked / chart:percentage). (Phase 5)
    val stacked: Boolean = false
)

data class OdfParagraph(
    val spans: List<OdfSpan>,
    val style: ParagraphStyle = ParagraphStyle.BODY,
    val alignment: TextAlign? = null,
    val marginLeft: Float = 0f,
    val marginTop: Float = 0f,
    val marginBottom: Float = 0f,
    val textIndent: Float = 0f,
    val backgroundColor: Long? = null,
    val listLevel: Int = 0,
    val listType: ListType = ListType.BULLET,
    val listItemIndex: Int = 0,
    // Checked state for CHECKBOX list items (loext:checkbox-status). Ignored for other list types.
    val listChecked: Boolean = false,
    val direction: LayoutDirection? = null,
    // Line spacing as a multiple of normal (1.0 = single, 1.5 = 150%); null = unspecified. (A6)
    val lineHeightPercent: Float? = null,
    // Paragraph border color (single uniform border) if any. (A7)
    val borderColor: Long? = null,
    // Per-edge styled borders (raw fo:border values), if any (Priority 4). Preserved on round-trip.
    val borders: OdfBorders? = null,
    // Number format for numbered lists: "1", "a", "A", "i", "I". (F42)
    val listNumberFormat: String = "1",
    // Bullet glyph for bullet lists. (F42)
    val listBulletChar: String = "\u2022",
    // Number prefix/suffix (e.g. "(" and ")" for "(1)"). (F42)
    val listNumberPrefix: String = "",
    val listNumberSuffix: String = ".",
    // Tab stop positions in px (96dpi). (A4)
    val tabStops: List<Float> = emptyList(),
    // Rich tab stops with type (left/right/center/char) and leader char, if any. (Phase 2)
    // When non-empty, takes precedence over [tabStops] on serialize.
    val tabStopDetails: List<OdfTabStop> = emptyList(),
    // Drop cap (style:drop-cap): number of lines the initial spans; 0 = none. (Phase 2)
    val dropCapLines: Int = 0,
    val dropCapLength: Int = 1,
    // Uniform paragraph padding in px@96 (fo:padding), preserved for round-trip. (Phase 6)
    val padding: Float = 0f,
    // Emit fo:break-before="page" on this paragraph's style. (B4)
    val breakBeforePage: Boolean = false,
    // Extended paragraph properties (Round 2 R3).
    val marginRight: Float = 0f,
    val keepWithNext: Boolean = false,
    val keepTogether: Boolean = false,
    val widows: Int? = null,
    val orphans: Int? = null,
    // Computed automatic heading number from text:outline-style, e.g. "1.2." (Round 3).
    val outlineNumber: String? = null
)

enum class ParagraphStyle { HEADING1, HEADING2, HEADING3, HEADING4, BODY, LIST_ITEM, TABLE_HEADER }

/**
 * A paragraph tab stop (style:tab-stop). [position] is px@96. [type] is one of
 * "left"/"right"/"center"/"char" (default "left"); [leaderChar] is the fill char
 * (e.g. "." for a dotted leader), preserved for round-trip. (Phase 2)
 */
data class OdfTabStop(
    val position: Float,
    val type: String? = null,
    val leaderChar: String? = null
)

enum class ListType { BULLET, NUMBERED, CHECKBOX }

/** Formats a 1-based list item index using an ODF number-format token ("1","a","A","i","I"). (F42) */
fun formatListNumber(index: Int, format: String): String {
    val n = index.coerceAtLeast(1)
    return when (format) {
        "a" -> toAlpha(n).lowercase()
        "A" -> toAlpha(n)
        "i" -> toRoman(n).lowercase()
        "I" -> toRoman(n)
        else -> n.toString()
    }
}

private fun toAlpha(n: Int): String {
    val sb = StringBuilder()
    var v = n
    while (v > 0) {
        val rem = (v - 1) % 26
        sb.insert(0, ('A' + rem))
        v = (v - 1) / 26
    }
    return sb.toString()
}

private fun toRoman(n: Int): String {
    if (n !in 1..3999) return n.toString()
    val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    val sb = StringBuilder()
    var v = n
    for (i in values.indices) {
        while (v >= values[i]) { sb.append(symbols[i]); v -= values[i] }
    }
    return sb.toString()
}

data class OdfSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val fontSize: Float? = null,
    val fontFamily: String? = null,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val color: Long? = null,
    val backgroundColor: Long? = null,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    val href: String? = null,
    val annotation: OdfAnnotation? = null,
    // ODF text field kind ("date","time","page-number",...). null = plain text. Display value in [text].
    val field: String? = null,
    // Cross-reference (Priority 5): refKind is one of "reference-ref","bookmark-ref",
    // "reference-mark","reference-mark-start","reference-mark-end". refName = target/mark name;
    // refFormat = text:reference-format (e.g. "page","chapter","text"). Display value in [text].
    val refName: String? = null,
    val refKind: String? = null,
    val refFormat: String? = null,
    // Track changes (Priority 6): changeKind = "insertion" or "deletion"; changeId references an OdfChange.
    val changeId: String? = null,
    val changeKind: String? = null,
    // Extended character formatting (Round 2 R2).
    val underlineStyle: String? = null,   // solid/double/dotted/dash/wave (null = none unless [underline])
    val underlineColor: Long? = null,
    val letterSpacing: Float? = null,     // pt
    val textTransform: String? = null,    // uppercase/lowercase/capitalize
    val language: String? = null,
    val country: String? = null
)

@Suppress("ArrayInDataClass")
data class OdfImage(
    val path: String,
    val imageData: ByteArray,
    val width: Float = 0f,
    val height: Float = 0f,
    val anchorType: String = "",
    // Rotation in degrees clockwise (E38).
    val rotationDegrees: Float = 0f,
    // Natural (intrinsic) pixel size of the source image, used to convert crop
    // fractions to absolute ODF fo:clip lengths. 0 = unknown. (A7)
    val naturalWidthPx: Float = 0f,
    val naturalHeightPx: Float = 0f,
    // Non-destructive crop insets as fractions of the source image [0,1). (Phase 5)
    val cropLeftPct: Float = 0f,
    val cropTopPct: Float = 0f,
    val cropRightPct: Float = 0f,
    val cropBottomPct: Float = 0f,
    // Image effects (Round 2 R7): opacity 0..100 (% from draw:image-opacity), color mode
    // (draw:color-mode: "standard"/"greyscale"/"mono"/"watermark").
    val opacityPercent: Float = 100f,
    val colorMode: String? = null,
    // Accessibility alt text from svg:title / svg:desc inside the frame. (Phase 2)
    val altTitle: String? = null,
    val altDesc: String? = null
)

data class OdfTable(
    val name: String = "",
    val columns: List<OdfTableColumn> = emptyList(),
    val rows: List<OdfTableRow> = emptyList(),
    // Number of leading header rows (table:table-header-rows). (Round 2 R5)
    val headerRowCount: Int = 0
)

data class OdfTableColumn(val width: Float? = null)

data class OdfTableRow(val cells: List<OdfTableCell>)

data class OdfTableCell(
    val paragraphs: List<OdfParagraph> = emptyList(),
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val backgroundColor: Long? = null,
    val borderColor: Long? = null,
    // Per-edge styled borders (raw fo:border values), if any. Preserved on round-trip. (OOXML import)
    val borders: OdfBorders? = null,
    val isCovered: Boolean = false,
    // OpenFormula source for a text-document table cell, preserved for round-trip. (Round 2 R5)
    val formula: String? = null,
    // Vertical alignment within the cell: "top"/"middle"/"bottom" (style:vertical-align). (Round 3)
    val verticalAlign: String? = null
)

data class OdfSheet(
    val name: String,
    val rows: List<OdfRow>,
    val columnWidths: List<Float?> = emptyList(),
    val floating: List<OdfSlideElement> = emptyList(),
    // Freeze panes: number of frozen header rows/columns (0 = none). Persisted in settings.xml. (C2)
    val freezeRows: Int = 0,
    val freezeCols: Int = 0,
    // Row heights in px@96 (parallel to rows; null = default). (Round 2 R6)
    val rowHeights: List<Float?> = emptyList(),
    // Indices of hidden rows / columns (table:visibility="collapse"|"filter"). (Round 2 R6)
    val hiddenRows: Set<Int> = emptySet(),
    val hiddenCols: Set<Int> = emptySet(),
    // Print range address(es), e.g. "Sheet1.A1:Sheet1.D20" (table:print-ranges). (Round 3)
    val printRanges: String? = null,
    // Sheet hidden from the tab bar (xlsx sheet state="hidden"/"veryHidden"). (OOXML import)
    val hidden: Boolean = false,
    // Sheet tab color as 0xAARRGGBB, if any (xlsx sheetPr/tabColor). (OOXML import)
    val tabColor: Long? = null
)

/** A workbook-level named range / expression (table:named-range). (Round 2 R6) */
data class OdfNamedRange(
    val name: String,
    val cellRangeAddress: String,
    val baseCellAddress: String? = null
)

/** A content validation rule (table:content-validation). (Round 3) */
data class OdfDataValidation(
    val name: String,
    val condition: String,
    val allowEmpty: Boolean = true
) {
    /** Extracts the allowed values when this is a "cell-content-is-in-list" validation, else null. */
    fun listValues(): List<String>? {
        val m = Regex("cell-content-is-in-list\\((.*)\\)").find(condition) ?: return null
        return m.groupValues[1].split(";").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }
}

data class OdfRow(val cells: List<OdfCell>)

data class OdfCell(
    val text: String,
    val spannedColumns: Int = 1,
    val rowSpan: Int = 1,
    val backgroundColor: Long? = null,
    val textColor: Long? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val alignment: TextAlign? = null,
    val borderColor: Long? = null,
    // Per-edge styled borders (raw fo:border values), if any (Priority 4). Preserved on round-trip.
    val borders: OdfBorders? = null,
    val isCovered: Boolean = false,
    // OpenFormula source (e.g. "of:=SUM([.A1:.A3])"), if this is a formula cell. (H49)
    val formula: String? = null,
    // ODF value type: "float", "string", "date", "percentage", "currency", "boolean". (H50/H54)
    val valueType: String? = null,
    // Cached numeric value from office:value, if numeric. (H54)
    val numberValue: Double? = null,
    // Resolved number/date/currency display format. (H50)
    val numberFormat: OdfNumberFormat? = null,
    // Wrap text in cell. (H53)
    val wrap: Boolean = false,
    // Cell comment/note (office:annotation), if any (Round 3).
    val annotation: OdfAnnotation? = null,
    // Name of a content-validation rule applied to this cell (Round 3).
    val validationName: String? = null,
    // Conditional formatting rules (from style:map), evaluated at render time (Round 3).
    val condFormats: List<OdfCondFormat> = emptyList(),
    // Hyperlink target (xlsx cell hyperlink), if any. (OOXML import)
    val hyperlink: String? = null,
    // Text rotation in degrees counter-clockwise (xlsx alignment textRotation). (OOXML import)
    val textRotation: Int = 0,
    // Vertical alignment within the cell: "top"/"middle"/"bottom" (xlsx alignment vertical). (OOXML import)
    val verticalAlign: String? = null
)

/** A conditional-format rule: when [condition] is true, apply the given colors. (Round 3) */
data class OdfCondFormat(
    val condition: String,
    val backgroundColor: Long? = null,
    val textColor: Long? = null
)

/** Resolved spreadsheet number-format descriptor (H50). */
data class OdfNumberFormat(
    val decimals: Int? = null,
    val percent: Boolean = false,
    val currencySymbol: String? = null,
    val grouping: Boolean = false,
    val isDate: Boolean = false,
    // Additional number-style kinds (Priority 4).
    val isTime: Boolean = false,
    val isScientific: Boolean = false,
    val isFraction: Boolean = false,
    // Fraction denominator digit count (e.g. 2 -> ?/??). Defaults to 1.
    val fractionDenominatorDigits: Int = 1,
    // Ordered source tokens for date/time styles, preserved to re-emit the exact pattern
    // instead of a hard-coded YYYY-MM-DD / HH:MM:SS on write. (Phase 4)
    val dateTimeTokens: List<OdfNumberToken> = emptyList()
)

/**
 * One ordered child token of a number:date-style / number:time-style (Phase 4).
 * [kind] is the number:* element local name (e.g. "year", "month", "text"); [style]
 * is number:style ("long"/"short"); [text] is the literal for number:text; [textual]
 * mirrors number:textual (month names).
 */
data class OdfNumberToken(
    val kind: String,
    val style: String? = null,
    val text: String? = null,
    val textual: Boolean = false
)

/**
 * Per-edge styled borders for a cell or paragraph (Priority 4). Each value is the
 * raw fo:border string (e.g. "0.5pt solid #000000"), preserved verbatim for ODF
 * round-trip fidelity. A representative color for rendering can be derived via
 * [renderColor].
 */
data class OdfBorders(
    val top: String? = null,
    val right: String? = null,
    val bottom: String? = null,
    val left: String? = null
) {
    fun isEmpty(): Boolean = top == null && right == null && bottom == null && left == null

    companion object {
        /** Extracts the first #RRGGBB color from a raw fo:border value as 0xFFRRGGBB. */
        fun renderColor(border: String?): Long? {
            if (border == null) return null
            val hex = Regex("#([0-9a-fA-F]{6})").find(border)?.groupValues?.get(1) ?: return null
            return 0xFF000000L or hex.toLong(16)
        }
    }
}

data class OdfSlide(
    val name: String,
    val elements: List<OdfSlideElement> = emptyList(),
    val backgroundColor: Long? = null,
    val backgroundImagePath: String? = null,
    val notes: List<OdfParagraph> = emptyList(),
    // Slide transition (presentation:transition-* on the drawing-page style). (Round 2 R9)
    val transitionType: String? = null,   // e.g. "fade", "wipe", "dissolve"
    val transitionSpeed: String? = null,  // "slow" | "medium" | "fast"
    // Linked master page name (draw:master-page-name), preserved for round-trip. (Round 3)
    val masterName: String? = null
)

sealed class OdfSlideElement {
    data class Frame(val frame: OdfFrame) : OdfSlideElement()
    data class Shape(val shape: OdfShape) : OdfSlideElement()
}

/** Geometry (x, y, width, height) of a floating element, in px@96. (Phase 1) */
fun OdfSlideElement.bounds(): FloatArray = when (this) {
    is OdfSlideElement.Frame -> floatArrayOf(frame.x, frame.y, frame.width, frame.height)
    is OdfSlideElement.Shape -> when (val s = shape) {
        // A line's (x,y) is its first endpoint, which may not be the top-left; position the
        // bounding box at the min corner so it isn't drawn off to one side.
        is OdfShape.Line -> floatArrayOf(
            minOf(s.x, s.x2), minOf(s.y, s.y2),
            kotlin.math.abs(s.x2 - s.x), kotlin.math.abs(s.y2 - s.y)
        )
        else -> floatArrayOf(s.x, s.y, s.width, s.height)
    }
}

/** Returns a copy of the element repositioned/resized to the given bounds (px@96). (Phase 1) */
fun setElementBounds(el: OdfSlideElement, x: Float, y: Float, w: Float, h: Float): OdfSlideElement = when (el) {
    is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(x = x, y = y, width = w, height = h))
    is OdfSlideElement.Shape -> OdfSlideElement.Shape(when (val s = el.shape) {
        is OdfShape.Rect -> s.copy(x = x, y = y, width = w, height = h)
        is OdfShape.Ellipse -> s.copy(x = x, y = y, width = w, height = h)
        is OdfShape.Line -> s.copy(x = x, y = y, width = w, height = h)
        is OdfShape.CustomShape -> s.copy(x = x, y = y, width = w, height = h)
        is OdfShape.Polyline -> s.copy(x = x, y = y, width = w, height = h)
    })
}

data class OdfFrame(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val paragraphs: List<OdfParagraph>,
    val image: OdfImage? = null,
    val chart: OdfChart? = null,
    val fillColor: Long? = null,
    val strokeColor: Long? = null,
    val strokeWidth: Float? = null,
    val fillGradient: OdfGradient? = null,
    /** Rotation in degrees clockwise (from the shape/frame transform). */
    val rotationDegrees: Float = 0f
)

sealed class OdfShape {
    abstract val x: Float
    abstract val y: Float
    abstract val width: Float
    abstract val height: Float
    abstract val fillColor: Long?
    abstract val strokeColor: Long?
    abstract val strokeWidth: Float?
    abstract val text: List<OdfParagraph>
    /** Rotation in degrees clockwise (from draw:transform rotate). (Round 3) */
    open val rotationDegrees: Float get() = 0f
    /** Linear/axial gradient fill, if any (overrides solid fillColor for rendering). (Round 3) */
    open val fillGradient: OdfGradient? get() = null
    /** Dashed stroke (draw:stroke="dash"). (Round 3) */
    open val strokeDashed: Boolean get() = false
    /** Arrowhead markers at line/connector ends (draw:marker-start/-end). (Round 3) */
    open val markerStart: Boolean get() = false
    open val markerEnd: Boolean get() = false

    data class Rect(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        val cornerRadius: Float = 0f,
        override val rotationDegrees: Float = 0f,
        override val fillGradient: OdfGradient? = null,
        override val strokeDashed: Boolean = false
    ) : OdfShape()

    data class Ellipse(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        override val rotationDegrees: Float = 0f,
        override val fillGradient: OdfGradient? = null,
        override val strokeDashed: Boolean = false
    ) : OdfShape()

    data class Line(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        val x2: Float = 0f, val y2: Float = 0f,
        override val rotationDegrees: Float = 0f,
        override val strokeDashed: Boolean = false,
        override val markerStart: Boolean = false,
        override val markerEnd: Boolean = false
    ) : OdfShape()

    data class CustomShape(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        override val rotationDegrees: Float = 0f,
        override val fillGradient: OdfGradient? = null,
        override val strokeDashed: Boolean = false
    ) : OdfShape()

    /** Polyline (open) or polygon (closed) with absolute px@96 vertices. (Priority 8) */
    data class Polyline(
        override val x: Float, override val y: Float,
        override val width: Float, override val height: Float,
        override val fillColor: Long? = null,
        override val strokeColor: Long? = null,
        override val strokeWidth: Float? = null,
        override val text: List<OdfParagraph> = emptyList(),
        val points: List<Pair<Float, Float>> = emptyList(),
        val closed: Boolean = false,
        override val rotationDegrees: Float = 0f,
        override val fillGradient: OdfGradient? = null,
        override val strokeDashed: Boolean = false
    ) : OdfShape()
}

/** Linear/axial gradient fill (draw:gradient). [angle] is in degrees. (Round 3) */
data class OdfGradient(
    val startColor: Long,
    val endColor: Long,
    val angle: Float = 0f,
    val style: String = "linear"
)

data class OdfFootnote(
    val citation: String,
    val body: List<OdfParagraph>,
    val isEndnote: Boolean = false
)

data class OdfAnnotation(
    val author: String? = null,
    val date: String? = null,
    val paragraphs: List<OdfParagraph> = emptyList()
)

data class OdfBookmark(
    val name: String,
    val contentIndex: Int
)
