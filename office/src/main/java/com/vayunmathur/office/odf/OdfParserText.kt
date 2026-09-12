package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.library.ui.odf.*
import org.xmlpull.v1.XmlPullParser

// --- Text Document ---

internal fun OdfParser.parseTextDocument(
    xml: String, styles: Map<String, StyleInfo>, listStyles: Map<String, ListStyleInfo>,
    title: String, metadata: OdfMetadata, images: Map<String, ByteArray>,
    headerFooter: HeaderFooterResult?, objectContents: Map<String, String> = emptyMap(),
    pageSetup: OdfPageSetup? = null
): OdfDocument.TextDocument {
    val content = mutableListOf<OdfContentBlock>()
    val footnotes = mutableListOf<OdfFootnote>()
    val bookmarks = mutableListOf<OdfBookmark>()
    val changes = mutableListOf<OdfChange>()
    trackedDeletionText = emptyMap()
    val parser = newParser(xml)
    var inBody = false
    var listDepth = 0
    val listTypeStack = mutableListOf<ListType>()
    val listItemCounter = mutableListOf<Int>()
    val listStyleStack = mutableListOf<ListStyleInfo?>()
    // Word (and LibreOffice) export one <text:list> per item and chain them with
    // text:continue-numbering / text:continue-list; without honoring those every item restarts
    // at 1. Remember where each list left off, by nesting depth and by xml:id. (bugfix)
    val listEndByDepth = HashMap<Int, Int>()
    val listEndById = HashMap<String, Int>()
    val listIdStack = mutableListOf<String?>()
    var pendingListItemChecked = false
    val outlineStyle = listStyles["%outline%"]
    val outlineCounters = IntArray(11)
    var eventType = parser.eventType

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> when {
                parser.name == "text" && parser.namespace?.contains("office") == true -> inBody = true
                inBody && parser.name == "tracked-changes" -> {
                    val (parsed, delText) = parseTrackedChanges(parser)
                    changes.addAll(parsed)
                    trackedDeletionText = delText
                }
                inBody && (parser.name == "bookmark" || parser.name == "bookmark-start") -> {
                    val bkName = getAttr(parser, "name")
                    if (bkName != null) bookmarks.add(OdfBookmark(bkName, content.size))
                }
                inBody && parser.name == "h" -> {
                    val level = getAttr(parser, "outline-level")?.toIntOrNull() ?: 1
                    val paraStyle = when (level) { 1 -> ParagraphStyle.HEADING1; 2 -> ParagraphStyle.HEADING2; 3 -> ParagraphStyle.HEADING3; else -> ParagraphStyle.HEADING4 }
                    val styleName = getAttr(parser, "style-name")
                    val resolved = resolveStyle(styleName, styles)
                    val inlineImages = mutableListOf<OdfImage>()
                    val inlineCharts = mutableListOf<OdfChart>()
                    val inlineFormulas = mutableListOf<String>()
                    val spans = parseInlineContent(parser, "h", styles, images, footnotes, inlineImages, objectContents, inlineCharts, inlineFormulas)
                    if (resolved.breakBefore == "page") content.add(OdfContentBlock.PageBreak)
                    val direction = parseDirection(resolved.writingMode)
                    val hLevelStyle = if (listDepth > 0) listStyleStack.lastOrNull()?.levelStyle(listDepth) else null
                    // Automatic outline heading numbering (text:outline-style). (Round 3)
                    val outlineNum: String? = outlineStyle?.let { os ->
                        val lvlStyle = os.levels[level]
                        if (lvlStyle?.numbered != true) null else {
                            outlineCounters[level]++
                            if (outlineCounters[level] < lvlStyle.startValue) outlineCounters[level] = lvlStyle.startValue
                            for (i in level + 1..10) outlineCounters[i] = 0
                            val d = lvlStyle.displayLevels.coerceIn(1, level)
                            ((level - d + 1)..level).joinToString(".") { lv ->
                                formatListNumber(outlineCounters[lv].coerceAtLeast(1), os.levels[lv]?.numberFormat ?: "1")
                            } + lvlStyle.suffix
                        }
                    }
                    content.add(OdfContentBlock.Paragraph(OdfParagraph(
                        spans = spans, style = paraStyle,
                        alignment = resolved.textAlign, marginLeft = resolved.marginLeft,
                        marginTop = resolved.marginTop, marginBottom = resolved.marginBottom,
                        textIndent = resolved.textIndent ?: 0f, backgroundColor = resolved.paragraphBackgroundColor,
                        direction = direction,
                        lineHeightPercent = resolved.lineHeightPercent,
                        borderColor = resolved.paragraphBorderColor,
                        borders = resolved.paraBorders,
                        marginRight = resolved.marginRight,
                        keepWithNext = resolved.keepWithNext,
                        keepTogether = resolved.keepTogether,
                        widows = resolved.widows,
                        orphans = resolved.orphans,
                        tabStops = resolved.tabStops,
                        tabStopDetails = resolved.tabStopDetails,
                        dropCapLines = resolved.dropCapLines,
                        dropCapLength = resolved.dropCapLength,
                        padding = resolved.padding,
                        listLevel = if (listDepth > 0) listDepth else 0,
                        listType = if (hLevelStyle?.numbered == true) ListType.NUMBERED else ListType.BULLET,
                        listItemIndex = if (listItemCounter.isNotEmpty()) listItemCounter.last() else 0,
                        listNumberFormat = hLevelStyle?.numberFormat ?: "1",
                        listBulletChar = hLevelStyle?.bulletChar ?: "•",
                        listNumberPrefix = hLevelStyle?.prefix ?: "",
                        listNumberSuffix = hLevelStyle?.suffix ?: ".",
                        outlineNumber = outlineNum
                    )))
                    for (img in inlineImages) content.add(OdfContentBlock.Image(img))
                    for (ch in inlineCharts) content.add(OdfContentBlock.Chart(ch))
                    for (f in inlineFormulas) content.add(if (f.contains("math")) OdfContentBlock.Formula(f) else OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = f, italic = true)), alignment = TextAlign.Center)))
                    if (resolved.breakAfter == "page") content.add(OdfContentBlock.PageBreak)
                }
                inBody && parser.name == "p" && parser.namespace?.contains("text") == true -> {
                    val styleName = getAttr(parser, "style-name")
                    val resolved = resolveStyle(styleName, styles)
                    if (resolved.breakBefore == "page") content.add(OdfContentBlock.PageBreak)
                    val style = if (listDepth > 0) ParagraphStyle.LIST_ITEM else ParagraphStyle.BODY
                    val inlineImages = mutableListOf<OdfImage>()
                    val inlineCharts = mutableListOf<OdfChart>()
                    val inlineFormulas = mutableListOf<String>()
                    val spans = parseInlineContent(parser, "p", styles, images, footnotes, inlineImages, objectContents, inlineCharts, inlineFormulas)
                    val direction = parseDirection(resolved.writingMode)
                    if (spans.isNotEmpty() || listDepth > 0) {
                        val itemIdx = if (listItemCounter.isNotEmpty()) listItemCounter.last() else 0
                        val levelStyle = listStyleStack.lastOrNull()?.levelStyle(listDepth)
                        val listTypeResolved = when {
                            levelStyle?.checkbox == true -> ListType.CHECKBOX
                            levelStyle != null -> if (levelStyle.numbered) ListType.NUMBERED else ListType.BULLET
                            listTypeStack.isNotEmpty() -> listTypeStack.last()
                            else -> ListType.BULLET
                        }
                        content.add(OdfContentBlock.Paragraph(OdfParagraph(
                            spans = spans, style = style,
                            alignment = resolved.textAlign, marginLeft = resolved.marginLeft,
                            marginTop = resolved.marginTop, marginBottom = resolved.marginBottom,
                            textIndent = resolved.textIndent ?: 0f, backgroundColor = resolved.paragraphBackgroundColor,
                            listLevel = listDepth,
                            listType = listTypeResolved,
                            listItemIndex = itemIdx,
                            listChecked = pendingListItemChecked,
                            direction = direction,
                            lineHeightPercent = resolved.lineHeightPercent,
                            borderColor = resolved.paragraphBorderColor,
                            borders = resolved.paraBorders,
                            marginRight = resolved.marginRight,
                            keepWithNext = resolved.keepWithNext,
                            keepTogether = resolved.keepTogether,
                            widows = resolved.widows,
                            orphans = resolved.orphans,
                            tabStops = resolved.tabStops,
                            tabStopDetails = resolved.tabStopDetails,
                            dropCapLines = resolved.dropCapLines,
                            dropCapLength = resolved.dropCapLength,
                            padding = resolved.padding,
                            listNumberFormat = levelStyle?.numberFormat ?: "1",
                            listBulletChar = levelStyle?.bulletChar ?: "•",
                            listNumberPrefix = levelStyle?.prefix ?: "",
                            listNumberSuffix = levelStyle?.suffix ?: "."
                        )))
                    }
                    for (img in inlineImages) content.add(OdfContentBlock.Image(img))
                    for (ch in inlineCharts) content.add(OdfContentBlock.Chart(ch))
                    for (f in inlineFormulas) content.add(if (f.contains("math")) OdfContentBlock.Formula(f) else OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = f, italic = true)), alignment = TextAlign.Center)))
                    if (resolved.breakAfter == "page") content.add(OdfContentBlock.PageBreak)
                }
                inBody && parser.name == "section" -> {
                    val nm = getAttr(parser, "name") ?: "Section"
                    val cols = resolveStyle(getAttr(parser, "style-name"), styles).columnCount
                    content.add(OdfContentBlock.SectionStart(nm, cols))
                }
                inBody && parser.name == "list" -> {
                    listDepth++
                    val styleName = getAttr(parser, "style-name")
                    val styleInfo = when {
                        styleName != null -> listStyles[styleName] ?: listStyleStack.lastOrNull()
                        else -> listStyleStack.lastOrNull()
                    }
                    val type = when {
                        styleInfo != null -> if (styleInfo.levelStyle(listDepth).numbered) ListType.NUMBERED else ListType.BULLET
                        listTypeStack.isNotEmpty() -> listTypeStack.last()
                        else -> ListType.BULLET
                    }
                    listTypeStack.add(type)
                    // Honor text:start-value so numbered lists can begin at N (not always 1),
                    // and text:continue-numbering / text:continue-list so a list that continues
                    // a previous one picks up where that one stopped instead of restarting.
                    val startValue = styleInfo?.levelStyle(listDepth)?.startValue ?: 1
                    val continueList = getAttr(parser, "continue-list")
                    val continued = when {
                        continueList != null -> listEndById[continueList] ?: listEndByDepth[listDepth]
                        getAttr(parser, "continue-numbering") == "true" -> listEndByDepth[listDepth]
                        else -> null
                    }
                    listItemCounter.add((continued ?: (startValue - 1)).coerceAtLeast(0))
                    listStyleStack.add(styleInfo)
                    listIdStack.add(getAttr(parser, "id"))
                }
                inBody && parser.name == "list-item" -> {
                    if (listItemCounter.isNotEmpty()) {
                        val startValue = getAttr(parser, "start-value")?.toIntOrNull()
                        listItemCounter[listItemCounter.size - 1] =
                            startValue ?: (listItemCounter.last() + 1)
                    }
                    pendingListItemChecked = getAttr(parser, "checkbox-status")?.let { it == "checked" || it == "true" } ?: false
                }
                inBody && parser.name == "table" && parser.namespace?.contains("table") == true -> {
                    content.add(OdfContentBlock.Table(parseTextTable(parser, styles)))
                }
                inBody && parser.name == "table-of-content" -> {
                    parseTocContent(parser, styles, content)
                }
                inBody && parser.name == "frame" && parser.namespace?.contains("draw") == true -> {
                    val frame = parseSingleFrame(parser, styles, images)
                    frame.image?.let { content.add(OdfContentBlock.Image(it)) }
                    for (para in frame.paragraphs) {
                        content.add(OdfContentBlock.Paragraph(para))
                    }
                }
            }
            XmlPullParser.END_TAG -> when {
                parser.name == "text" && parser.namespace?.contains("office") == true -> inBody = false
                parser.name == "list" && inBody -> {
                    val endedDepth = listDepth
                    listDepth--
                    if (listTypeStack.isNotEmpty()) listTypeStack.removeAt(listTypeStack.size - 1)
                    if (listItemCounter.isNotEmpty()) {
                        // Remember the final number so a following continue-numbering list resumes here.
                        val ended = listItemCounter.removeAt(listItemCounter.size - 1)
                        listEndByDepth[endedDepth] = ended
                        listIdStack.lastOrNull()?.let { listEndById[it] = ended }
                    }
                    if (listIdStack.isNotEmpty()) listIdStack.removeAt(listIdStack.size - 1)
                    if (listStyleStack.isNotEmpty()) listStyleStack.removeAt(listStyleStack.size - 1)
                }
                parser.name == "section" && inBody -> content.add(OdfContentBlock.SectionEnd)
            }
        }
        eventType = parser.next()
    }
    return OdfDocument.TextDocument(title, content, metadata, images, footnotes,
        headerParagraphs = headerFooter?.headerParagraphs ?: emptyList(),
        footerParagraphs = headerFooter?.footerParagraphs ?: emptyList(),
        bookmarks = bookmarks, changes = changes, pageSetup = pageSetup)
}

internal fun OdfParser.parseDirection(writingMode: String?): LayoutDirection? = when {
    writingMode == null -> null
    writingMode.startsWith("rl") -> LayoutDirection.Rtl
    writingMode.startsWith("lr") -> LayoutDirection.Ltr
    else -> null
}

// --- Tables in text documents ---

internal fun OdfParser.parseTextTable(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfTable {
    val tableName = getAttr(parser, "name") ?: ""
    val columns = mutableListOf<OdfTableColumn>()
    val rows = mutableListOf<OdfTableRow>()
    var headerRowCount = 0
    var inHeader = false
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) when (parser.name) {
            "table-column" -> {
                val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                val styleName = getAttr(parser, "style-name")
                val width = resolveStyle(styleName, styles).columnWidth
                repeat(repeated.coerceAtMost(100)) { columns.add(OdfTableColumn(width = width)) }
            }
            "table-row" -> { rows.add(OdfTableRow(parseTableCells(parser, styles))); if (inHeader) headerRowCount++ }
            "table-header-rows" -> inHeader = true
        } else if (eventType == XmlPullParser.END_TAG && parser.name == "table-header-rows") {
            inHeader = false
        }
        eventType = parser.next()
    }
    return OdfTable(tableName, columns, rows, headerRowCount)
}

internal fun OdfParser.parseTableCells(parser: XmlPullParser, styles: Map<String, StyleInfo>): List<OdfTableCell> {
    val cells = mutableListOf<OdfTableCell>()
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) when (parser.name) {
            "table-cell" -> {
                val colSpan = getAttr(parser, "number-columns-spanned")?.toIntOrNull() ?: 1
                val rowSpan = getAttr(parser, "number-rows-spanned")?.toIntOrNull() ?: 1
                val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                val styleName = getAttr(parser, "style-name")
                val resolved = resolveStyle(styleName, styles)
                val formula = getAttr(parser, "formula")
                val paragraphs = parseTableCellContent(parser, styles)
                repeat(repeated.coerceAtMost(100)) {
                    cells.add(OdfTableCell(
                        paragraphs = paragraphs,
                        colSpan = colSpan, rowSpan = rowSpan,
                        backgroundColor = resolved.cellBackgroundColor,
                        borderColor = resolved.cellBorderColor,
                        formula = formula,
                        verticalAlign = resolved.cellVerticalAlign
                    ))
                }
            }
            "covered-table-cell" -> {
                val repeated = getAttr(parser, "number-columns-repeated")?.toIntOrNull() ?: 1
                repeat(repeated.coerceAtMost(100)) { cells.add(OdfTableCell(isCovered = true)) }
                skipElement(parser)
            }
        }
        eventType = parser.next()
    }
    return cells
}

internal fun OdfParser.parseTableCellContent(parser: XmlPullParser, styles: Map<String, StyleInfo>): List<OdfParagraph> {
    val paragraphs = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG && parser.name == "p") {
            val spans = parseInlineContent(parser, "p", styles)
            paragraphs.add(OdfParagraph(spans))
        }
        eventType = parser.next()
    }
    return paragraphs
}

// --- TOC ---

internal fun OdfParser.parseTocContent(parser: XmlPullParser, styles: Map<String, StyleInfo>, content: MutableList<OdfContentBlock>) {
    val depth = parser.depth
    var eventType = parser.next()
    var inIndexBody = false
    var inIndexTitle = false
    var title = "Table of Contents"
    val entries = mutableListOf<OdfParagraph>()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        when {
            eventType == XmlPullParser.START_TAG && parser.name == "index-body" -> inIndexBody = true
            eventType == XmlPullParser.END_TAG && parser.name == "index-body" -> inIndexBody = false
            eventType == XmlPullParser.START_TAG && parser.name == "index-title" -> inIndexTitle = true
            eventType == XmlPullParser.END_TAG && parser.name == "index-title" -> inIndexTitle = false
            eventType == XmlPullParser.START_TAG && parser.name == "p" && inIndexBody -> {
                val spans = parseInlineContent(parser, "p", styles)
                val text = spans.joinToString("") { it.text }.trim()
                if (inIndexTitle) {
                    if (text.isNotEmpty()) title = text
                } else if (spans.isNotEmpty()) {
                    entries.add(OdfParagraph(spans))
                }
            }
        }
        eventType = parser.next()
    }
    content.add(OdfContentBlock.TableOfContents(title, entries))
}

/** Parses text:tracked-changes into change metadata + a map of deletion text by change-id. (Priority 6) */
internal fun OdfParser.parseTrackedChanges(parser: XmlPullParser): Pair<List<OdfChange>, Map<String, String>> {
    val list = mutableListOf<OdfChange>()
    val delText = HashMap<String, String>()
    val depth = parser.depth
    fun collectText(): String {
        val d = parser.depth; val sb = StringBuilder(); var ev = parser.next()
        while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
            if (ev == XmlPullParser.TEXT) sb.append(parser.text)
            if (ev == XmlPullParser.END_DOCUMENT) break
            ev = parser.next()
        }
        return sb.toString()
    }
    var curId: String? = null
    var curType: String? = null
    var author: String? = null
    var date: String? = null
    val delBuf = StringBuilder()
    var inDeletion = false
    fun flush() {
        val id = curId ?: return
        val type = curType ?: "insertion"
        list.add(OdfChange(id, type, author, date))
        if (type == "deletion") delText[id] = delBuf.toString()
    }
    var ev = parser.next()
    while (!(ev == XmlPullParser.END_TAG && parser.depth == depth)) {
        when (ev) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "changed-region" -> {
                    curId = getAttr(parser, "id"); curType = null; author = null; date = null
                    delBuf.clear(); inDeletion = false
                }
                "insertion" -> curType = "insertion"
                "deletion" -> { curType = "deletion"; inDeletion = true }
                "format-change" -> curType = "format-change"
                "creator" -> author = collectText().trim()
                "date" -> date = collectText().trim()
                "p" -> if (inDeletion) { if (delBuf.isNotEmpty()) delBuf.append("\n"); delBuf.append(collectText()) }
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "changed-region" -> { flush(); curId = null }
                "deletion" -> inDeletion = false
            }
        }
        ev = parser.next()
    }
    return list to delText
}
