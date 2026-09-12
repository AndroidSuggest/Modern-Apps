package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.vayunmathur.office.R

// --- Track changes / tables / paragraph ops (split from OfficeViewModel.kt for file length) ---

internal fun OfficeViewModel.transformChangeSpans(doc: OdfDocument.TextDocument, id: String, removeSpans: Boolean): OdfDocument.TextDocument {
    fun mapSpans(spans: List<OdfSpan>): List<OdfSpan> {
        if (spans.none { it.changeId == id }) return spans
        val out = ArrayList<OdfSpan>()
        for (s in spans) {
            if (s.changeId == id) {
                if (removeSpans) continue
                out.add(s.copy(changeId = null, changeKind = null))
            } else out.add(s)
        }
        return out.ifEmpty { listOf(OdfSpan(text = "")) }
    }
    val newContent = doc.content.map { block ->
        when (block) {
            is OdfContentBlock.Paragraph -> OdfContentBlock.Paragraph(block.paragraph.copy(spans = mapSpans(block.paragraph.spans)))
            is OdfContentBlock.Table -> OdfContentBlock.Table(block.table.copy(rows = block.table.rows.map { r ->
                r.copy(cells = r.cells.map { c -> c.copy(paragraphs = c.paragraphs.map { p -> p.copy(spans = mapSpans(p.spans)) }) })
            }))
            is OdfContentBlock.TableOfContents -> OdfContentBlock.TableOfContents(block.title, block.entries.map { it.copy(spans = mapSpans(it.spans)) })
            else -> block
        }
    }
    return doc.copy(content = newContent, changes = doc.changes.filterNot { it.id == id })
}

/** Accept a tracked change: insertions stay, deletions are applied (text removed). */
fun OfficeViewModel.acceptChange(id: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val type = doc.changes.find { it.id == id }?.type ?: return
    updateDocument(transformChangeSpans(doc, id, removeSpans = type == "deletion"))
}

/** Reject a tracked change: insertions are removed, deletions are reverted (text kept). */
fun OfficeViewModel.rejectChange(id: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val type = doc.changes.find { it.id == id }?.type ?: return
    updateDocument(transformChangeSpans(doc, id, removeSpans = type != "deletion"))
}

fun OfficeViewModel.acceptAllChanges() {
    var doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    for (id in doc.changes.map { it.id }) {
        val type = doc.changes.find { it.id == id }?.type ?: continue
        doc = transformChangeSpans(doc, id, removeSpans = type == "deletion")
    }
    updateDocument(doc)
}

fun OfficeViewModel.rejectAllChanges() {
    var doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    for (id in doc.changes.map { it.id }) {
        val type = doc.changes.find { it.id == id }?.type ?: continue
        doc = transformChangeSpans(doc, id, removeSpans = type != "deletion")
    }
    updateDocument(doc)
}

/** Updates page geometry (size/margins/orientation), persisted to styles.xml on save. (Priority 7) */
fun OfficeViewModel.setPageSetup(setup: OdfPageSetup) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    updateDocument(doc.copy(pageSetup = setup))
}

/** Inserts a footnote with a citation marker at the caret (B15). */
fun OfficeViewModel.insertFootnote(start: Int, endInclusive: Int, gPos: Int, body: String, isEndnote: Boolean = false) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val citation = (doc.footnotes.size + 1).toString()
    val footnotes = doc.footnotes + OdfFootnote(citation, listOf(OdfParagraph(listOf(OdfSpan(text = body)))), isEndnote)
    // Insert the citation marker text at the caret, then attach the footnote.
    val paras = runParas(start, endInclusive)
    if (paras != null) {
        val full = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
        val pos = gPos.coerceIn(0, full.length)
        val newText = full.substring(0, pos) + "[$citation]" + full.substring(pos)
        updateParagraphRun(start, endInclusive, newText)
    }
    val current = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    updateDocument(current.copy(footnotes = footnotes))
}

/** Removes the annotation span at [spanIndex] within paragraph [blockIndex] (resolve comment). (C3) */
fun OfficeViewModel.resolveComment(blockIndex: Int, spanIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
    val spans = block.paragraph.spans.toMutableList()
    val span = spans.getOrNull(spanIndex) ?: return
    if (span.annotation == null) return
    spans.removeAt(spanIndex)
    if (spans.isEmpty()) spans.add(OdfSpan(text = ""))
    val content = doc.content.toMutableList()
    content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = spans))
    updateDocument(doc.copy(content = content))
}

/** Appends a comment/annotation marker to a paragraph (B16). */
fun OfficeViewModel.insertComment(blockIndex: Int, author: String, text: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val content = doc.content.toMutableList()
    val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
    val annotation = OdfAnnotation(
        author = author.ifBlank { null },
        date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()),
        paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = text))))
    )
    val newSpans = block.paragraph.spans + OdfSpan(text = " \uD83D\uDCDD ", annotation = annotation)
    content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = newSpans))
    updateDocument(doc.copy(content = content))
}

/** Sets header/footer text (in-session edit; B20). */
fun OfficeViewModel.setHeaderText(text: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val paras = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
    updateDocument(doc.copy(headerParagraphs = paras))
}

fun OfficeViewModel.setFooterText(text: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val paras = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
    updateDocument(doc.copy(footerParagraphs = paras))
}

// --- Text-document table editing ---

internal fun OfficeViewModel.withTextTable(blockIndex: Int, transform: (OdfTable) -> OdfTable) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Table ?: return
    val newContent = doc.content.toMutableList()
    newContent[blockIndex] = OdfContentBlock.Table(transform(block.table))
    updateDocument(doc.copy(content = newContent))
}

fun OfficeViewModel.updateTextTableCell(blockIndex: Int, row: Int, col: Int, newText: String) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val r = rows.getOrNull(row) ?: return@withTextTable table
    val cells = r.cells.toMutableList()
    if (col !in cells.indices) return@withTextTable table
    val paras = newText.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
    cells[col] = cells[col].copy(paragraphs = paras)
    rows[row] = OdfTableRow(cells)
    table.copy(rows = rows)
}

fun OfficeViewModel.textTableAddRow(blockIndex: Int, afterRow: Int) = withTextTable(blockIndex) { table ->
    val colCount = table.rows.firstOrNull()?.cells?.size ?: 1
    val newRow = OdfTableRow(List(colCount) { OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = ""))))) })
    val rows = table.rows.toMutableList()
    val at = (afterRow + 1).coerceIn(0, rows.size)
    rows.add(at, newRow)
    table.copy(rows = rows)
}

fun OfficeViewModel.textTableAddColumn(blockIndex: Int, afterCol: Int) = withTextTable(blockIndex) { table ->
    table.copy(rows = table.rows.map { row ->
        val cells = row.cells.toMutableList()
        val at = (afterCol + 1).coerceIn(0, cells.size)
        cells.add(at, OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = ""))))))
        OdfTableRow(cells)
    })
}

fun OfficeViewModel.textTableDeleteRow(blockIndex: Int, row: Int) = withTextTable(blockIndex) { table ->
    if (table.rows.size <= 1 || row !in table.rows.indices) table
    else table.copy(rows = table.rows.toMutableList().apply { removeAt(row) })
}

fun OfficeViewModel.textTableDeleteColumn(blockIndex: Int, col: Int) = withTextTable(blockIndex) { table ->
    table.copy(rows = table.rows.map { row ->
        if (row.cells.size <= 1 || col !in row.cells.indices) row
        else OdfTableRow(row.cells.toMutableList().apply { removeAt(col) })
    })
}

/** Per-cell character formatting for text-document tables (C26). */
fun OfficeViewModel.setTextTableCellSpanFormat(blockIndex: Int, row: Int, col: Int, transform: (OdfSpan) -> OdfSpan) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val r = rows.getOrNull(row) ?: return@withTextTable table
    val cells = r.cells.toMutableList()
    val cell = cells.getOrNull(col) ?: return@withTextTable table
    val newParas = cell.paragraphs.map { p -> p.copy(spans = p.spans.map(transform)) }
    cells[col] = cell.copy(paragraphs = newParas)
    rows[row] = OdfTableRow(cells)
    table.copy(rows = rows)
}

fun OfficeViewModel.setTextTableCellAlignment(blockIndex: Int, row: Int, col: Int, alignment: androidx.compose.ui.text.style.TextAlign?) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val r = rows.getOrNull(row) ?: return@withTextTable table
    val cells = r.cells.toMutableList()
    val cell = cells.getOrNull(col) ?: return@withTextTable table
    cells[col] = cell.copy(paragraphs = cell.paragraphs.map { it.copy(alignment = alignment) })
    rows[row] = OdfTableRow(cells)
    table.copy(rows = rows)
}

fun OfficeViewModel.setTextTableCellBackground(blockIndex: Int, row: Int, col: Int, color: Long?) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val r = rows.getOrNull(row) ?: return@withTextTable table
    val cells = r.cells.toMutableList()
    val cell = cells.getOrNull(col) ?: return@withTextTable table
    cells[col] = cell.copy(backgroundColor = color)
    rows[row] = OdfTableRow(cells)
    table.copy(rows = rows)
}

/** Merge a rectangular block of text-table cells (C27). */
fun OfficeViewModel.mergeTextTableCells(blockIndex: Int, startRow: Int, startCol: Int, endRow: Int, endCol: Int) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val colSpan = endCol - startCol + 1
    val rowSpan = endRow - startRow + 1
    if (colSpan < 1 || rowSpan < 1) return@withTextTable table
    for (r in startRow..endRow) {
        val rr = rows.getOrNull(r) ?: continue
        val cells = rr.cells.toMutableList()
        for (c in startCol..endCol) {
            if (c !in cells.indices) continue
            cells[c] = if (r == startRow && c == startCol) cells[c].copy(colSpan = colSpan, rowSpan = rowSpan)
            else cells[c].copy(isCovered = true)
        }
        rows[r] = OdfTableRow(cells)
    }
    table.copy(rows = rows)
}

fun OfficeViewModel.unmergeTextTableCells(blockIndex: Int, row: Int, col: Int) = withTextTable(blockIndex) { table ->
    val rows = table.rows.toMutableList()
    val cell = rows.getOrNull(row)?.cells?.getOrNull(col) ?: return@withTextTable table
    if (cell.colSpan <= 1 && cell.rowSpan <= 1) return@withTextTable table
    for (r in row until minOf(row + cell.rowSpan, rows.size)) {
        val cells = rows[r].cells.toMutableList()
        for (c in col until minOf(col + cell.colSpan, cells.size)) {
            cells[c] = if (r == row && c == col) cells[c].copy(colSpan = 1, rowSpan = 1) else cells[c].copy(isCovered = false)
        }
        rows[r] = OdfTableRow(cells)
    }
    table.copy(rows = rows)
}

fun OfficeViewModel.insertChart(blockIndex: Int, chart: OdfChart) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val content = doc.content.toMutableList()
    val at = (blockIndex + 1).coerceIn(0, content.size)
    content.add(at, OdfContentBlock.Chart(chart))
    updateDocument(doc.copy(content = content))
}

fun OfficeViewModel.updateChart(blockIndex: Int, chart: OdfChart) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    if (doc.content.getOrNull(blockIndex) !is OdfContentBlock.Chart) return
    val content = doc.content.toMutableList()
    content[blockIndex] = OdfContentBlock.Chart(chart)
    updateDocument(doc.copy(content = content))
}

fun OfficeViewModel.addParagraphAfter(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.addParagraphAfter(blockIndex))
}

fun OfficeViewModel.deleteParagraph(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.deleteParagraph(blockIndex) ?: return)
}

fun OfficeViewModel.toggleBold(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(bold = !it.bold) }
fun OfficeViewModel.toggleItalic(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(italic = !it.italic) }
fun OfficeViewModel.toggleUnderline(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(underline = !it.underline) }
fun OfficeViewModel.toggleStrikethrough(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(strikethrough = !it.strikethrough) }

fun OfficeViewModel.setSpanColor(blockIndex: Int, color: Long?) = toggleSpanFormat(blockIndex) { it.copy(color = color) }
fun OfficeViewModel.setSpanFontSize(blockIndex: Int, size: Float) = toggleSpanFormat(blockIndex) { it.copy(fontSize = size) }

fun OfficeViewModel.setParagraphStyle(blockIndex: Int, style: ParagraphStyle) {
    val doc = curText() ?: return
    updateDocument(doc.setParagraphStyle(blockIndex, style) ?: return)
}

fun OfficeViewModel.setParagraphAlignment(blockIndex: Int, alignment: androidx.compose.ui.text.style.TextAlign?) {
    val doc = curText() ?: return
    updateDocument(doc.setParagraphAlignment(blockIndex, alignment) ?: return)
}

fun OfficeViewModel.replaceInDocument(search: String, replacement: String, replaceAll: Boolean, matchCase: Boolean = false, wholeWord: Boolean = false): Int {
    if (search.isEmpty()) return 0
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return 0
    val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
    val boundary = if (wholeWord) "\\b" else ""
    val pattern = try { Regex(boundary + Regex.escape(search) + boundary, opts) } catch (_: Exception) { return 0 }
    val content = doc.content.toMutableList()
    var count = 0
    for (i in content.indices) {
        val block = content[i] as? OdfContentBlock.Paragraph ?: continue
        val para = block.paragraph
        var changed = false
        val newSpans = para.spans.map { span ->
            if (!replaceAll && count > 0) return@map span
            val matches = pattern.findAll(span.text).toList()
            if (matches.isEmpty()) return@map span
            changed = true
            if (replaceAll) {
                count += matches.size
                span.copy(text = pattern.replace(span.text) { replacement })
            } else {
                val m = matches.first()
                count += 1
                span.copy(text = span.text.substring(0, m.range.first) + replacement + span.text.substring(m.range.last + 1))
            }
        }
        if (changed) content[i] = OdfContentBlock.Paragraph(para.copy(spans = newSpans))
    }
    if (count > 0) updateDocument(doc.copy(content = content))
    return count
}

/** Returns the content block indices that contain a match for the query (B23 find-next navigation). */
fun OfficeViewModel.findMatchBlocks(search: String, matchCase: Boolean = false, wholeWord: Boolean = false): List<Int> {
    if (search.isEmpty()) return emptyList()
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return emptyList()
    val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
    val boundary = if (wholeWord) "\\b" else ""
    val pattern = try { Regex(boundary + Regex.escape(search) + boundary, opts) } catch (_: Exception) { return emptyList() }
    val result = mutableListOf<Int>()
    doc.content.forEachIndexed { i, block ->
        if (block is OdfContentBlock.Paragraph && block.paragraph.spans.any { pattern.containsMatchIn(it.text) }) result.add(i)
    }
    return result
}

fun OfficeViewModel.duplicateParagraph(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.duplicateParagraph(blockIndex) ?: return)
}

fun OfficeViewModel.moveParagraphUp(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.moveParagraphUp(blockIndex) ?: return)
}

fun OfficeViewModel.moveParagraphDown(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.moveParagraphDown(blockIndex) ?: return)
}

fun OfficeViewModel.toggleListItem(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.toggleListItem(blockIndex) ?: return)
}

fun OfficeViewModel.toggleNumberedList(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.toggleNumberedList(blockIndex) ?: return)
}

fun OfficeViewModel.indentParagraph(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.indentParagraph(blockIndex) ?: return)
}

fun OfficeViewModel.outdentParagraph(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.outdentParagraph(blockIndex) ?: return)
}

fun OfficeViewModel.insertTable(blockIndex: Int, rows: Int, cols: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val content = doc.content.toMutableList()
    val tableRows = (0 until rows).map { OdfTableRow((0 until cols).map { OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan(text = ""))))) }) }
    content.add(blockIndex + 1, OdfContentBlock.Table(OdfTable("Table", emptyList(), tableRows)))
    updateDocument(doc.copy(content = content))
}

fun OfficeViewModel.insertPageBreak(blockIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val content = doc.content.toMutableList()
    content.add(blockIndex + 1, OdfContentBlock.PageBreak)
    updateDocument(doc.copy(content = content))
}

fun OfficeViewModel.insertHyperlink(blockIndex: Int, text: String, url: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val content = doc.content.toMutableList()
    val linkSpan = OdfSpan(text = text, href = url, underline = true, color = 0xFF0066CCL)
    content.add(blockIndex + 1, OdfContentBlock.Paragraph(OdfParagraph(listOf(linkSpan))))
    updateDocument(doc.copy(content = content))
}

fun OfficeViewModel.addBookmark(name: String, contentIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val bookmarks = doc.bookmarks.toMutableList()
    bookmarks.add(OdfBookmark(name, contentIndex))
    updateDocument(doc.copy(bookmarks = bookmarks))
}

fun OfficeViewModel.removeBookmark(name: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val bookmarks = doc.bookmarks.filter { it.name != name }
    updateDocument(doc.copy(bookmarks = bookmarks))
}

/** Edit document metadata (G47). */
fun OfficeViewModel.updateMetadata(transform: (OdfMetadata) -> OdfMetadata) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return
    val newDoc = when (doc) {
        is OdfDocument.TextDocument -> doc.copy(metadata = transform(doc.metadata))
        is OdfDocument.Spreadsheet -> doc.copy(metadata = transform(doc.metadata))
        is OdfDocument.Presentation -> doc.copy(metadata = transform(doc.metadata))
        is OdfDocument.Drawing -> doc.copy(metadata = transform(doc.metadata))
    }
    updateDocument(newDoc)
}

internal fun OfficeViewModel.toggleSpanFormat(blockIndex: Int, transform: (OdfSpan) -> OdfSpan) {
    val doc = curText() ?: return
    updateDocument(doc.toggleSpanFormat(blockIndex, transform) ?: return)
}
