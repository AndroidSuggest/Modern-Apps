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

// --- Text document editing (split from OfficeViewModel.kt for file length) ---

internal fun OfficeViewModel.curText(): OdfDocument.TextDocument? = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument

fun OfficeViewModel.updateParagraphText(blockIndex: Int, newText: String) {
    val doc = curText() ?: return
    updateDocument(doc.updateParagraphText(blockIndex, newText) ?: return)
}

/** Applies a span transform to the character range [start, end). If the range is empty, applies to the whole paragraph. */
fun OfficeViewModel.applySpanStyleToRange(blockIndex: Int, start: Int, end: Int, transform: (OdfSpan) -> OdfSpan) {
    val doc = curText() ?: return
    updateDocument(doc.applySpanStyleToRange(blockIndex, start, end, transform) ?: return)
}

/** True if every character in [start, end) (or the whole paragraph when empty) satisfies [predicate]. */
fun OfficeViewModel.rangeHasFormat(blockIndex: Int, start: Int, end: Int, predicate: (OdfSpan) -> Boolean): Boolean =
    curText()?.rangeHasFormat(blockIndex, start, end, predicate) ?: false

internal fun OfficeViewModel.spansToChars(spans: List<OdfSpan>): MutableList<OdfSpan> {
    val out = ArrayList<OdfSpan>()
    for (span in spans) for (ch in span.text) out.add(span.copy(text = ch.toString()))
    return out
}

internal fun OfficeViewModel.charsToSpans(chars: List<OdfSpan>): List<OdfSpan> {
    if (chars.isEmpty()) return listOf(OdfSpan(text = ""))
    val out = ArrayList<OdfSpan>()
    var current = chars[0]
    val sb = StringBuilder(current.text)
    for (i in 1 until chars.size) {
        val c = chars[i]
        if (c.copy(text = "") == current.copy(text = "")) {
            sb.append(c.text)
        } else {
            out.add(current.copy(text = sb.toString()))
            current = c
            sb.setLength(0)
            sb.append(c.text)
        }
    }
    out.add(current.copy(text = sb.toString()))
    return out
}

// --- Continuous (multi-paragraph run) editing ---

internal fun OfficeViewModel.runParas(start: Int, endInclusive: Int): List<OdfParagraph>? {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return null
    if (start < 0 || endInclusive >= doc.content.size || start > endInclusive) return null
    val list = ArrayList<OdfParagraph>()
    for (i in start..endInclusive) {
        val b = doc.content[i] as? OdfContentBlock.Paragraph ?: return null
        list.add(b.paragraph)
    }
    return list
}

internal fun OfficeViewModel.paraLens(paras: List<OdfParagraph>) = paras.map { p -> p.spans.sumOf { it.text.length } }

internal fun OfficeViewModel.runLocate(lens: List<Int>, pos: Int): Pair<Int, Int> {
    var rem = pos
    for (i in lens.indices) {
        if (rem <= lens[i]) return i to rem
        rem -= lens[i] + 1 // consume paragraph chars + separator
        if (rem < 0) return i to lens[i]
    }
    return lens.lastIndex.coerceAtLeast(0) to (lens.lastOrNull() ?: 0)
}

/** Edits a run of consecutive paragraphs [start, endInclusive] as one continuous text (paragraphs joined by '\n'). */
fun OfficeViewModel.updateParagraphRun(start: Int, endInclusive: Int, newText: String) {
    val doc = curText() ?: return
    updateDocument(doc.updateParagraphRun(start, endInclusive, newText) ?: return)
}

/** Smart Enter: splits/continues a list line, or exits an empty list item. Returns the new caret. */
fun OfficeViewModel.handleListEnter(start: Int, endInclusive: Int, gPos: Int): Int? {
    val doc = curText() ?: return null
    val (newDoc, caret) = doc.handleListEnter(start, endInclusive, gPos) ?: return null
    updateDocument(newDoc)
    return caret
}

/** Smart Backspace at the start of a list item: outdent/remove the marker. Returns the new caret. */
fun OfficeViewModel.handleListBackspace(start: Int, endInclusive: Int, gPos: Int): Int? {
    val doc = curText() ?: return null
    val (newDoc, caret) = doc.handleListBackspace(start, endInclusive, gPos) ?: return null
    updateDocument(newDoc)
    return caret
}

/** Toggles a checklist item on/off for a paragraph. */
fun OfficeViewModel.toggleCheckbox(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.toggleCheckbox(blockIndex) ?: return)
}

/** Sets the checked state of a checklist item (tapping the box). */
fun OfficeViewModel.setCheckboxChecked(blockIndex: Int, checked: Boolean) {
    val doc = curText() ?: return
    updateDocument(doc.setCheckboxChecked(blockIndex, checked) ?: return)
}

/** Finds the link covering the caret in a run, or null. */
fun OfficeViewModel.linkAt(start: Int, endInclusive: Int, gPos: Int): OdfLinkSpan? =
    curText()?.linkAt(start, endInclusive, gPos)

/** Plain text of the current run selection (used to pre-fill the link dialog). */
fun OfficeViewModel.runSelectedText(start: Int, endInclusive: Int, gStart: Int, gEnd: Int): String {
    val doc = curText() ?: return ""
    val full = (start..endInclusive)
        .mapNotNull { (doc.content.getOrNull(it) as? OdfContentBlock.Paragraph)?.paragraph }
        .joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    val s = minOf(gStart, gEnd).coerceIn(0, full.length)
    val e = maxOf(gStart, gEnd).coerceIn(s, full.length)
    return full.substring(s, e)
}

/** Replaces a run range with a single link span. */
fun OfficeViewModel.setLink(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, text: String, url: String) {
    val doc = curText() ?: return
    updateDocument(doc.setLinkInRun(start, endInclusive, gStart, gEnd, text, url) ?: return)
}

/** Removes the link over a run range, keeping the text. */
fun OfficeViewModel.removeLinkInRun(start: Int, endInclusive: Int, gStart: Int, gEnd: Int) {
    val doc = curText() ?: return
    updateDocument(doc.applyRunSpanStyle(start, endInclusive, gStart, gEnd) {
        it.copy(href = null, underline = false, color = null)
    } ?: return)
}

/** Applies a span transform across a (possibly multi-paragraph) selection within a run. Empty selection = caret's whole paragraph. */
fun OfficeViewModel.applyRunSpanStyle(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfSpan) -> OdfSpan) {
    val doc = curText() ?: return
    updateDocument(doc.applyRunSpanStyle(start, endInclusive, gStart, gEnd, transform) ?: return)
}

/** True if every character in the run selection (or caret's whole paragraph) satisfies [predicate]. */
fun OfficeViewModel.runRangeHasFormat(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, predicate: (OdfSpan) -> Boolean): Boolean =
    curText()?.runRangeHasFormat(start, endInclusive, gStart, gEnd, predicate) ?: false

/** Paragraph index (within the document) at the given run-global caret position. */
fun OfficeViewModel.runParagraphIndexAt(start: Int, endInclusive: Int, gPos: Int): Int =
    curText()?.runParagraphIndexAt(start, endInclusive, gPos) ?: start

/** Applies a paragraph-level mutation to every paragraph touched by the run selection. */
fun OfficeViewModel.mutateRunParagraphs(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfParagraph) -> OdfParagraph) {
    val doc = curText() ?: return
    updateDocument(doc.mutateRunParagraphs(start, endInclusive, gStart, gEnd, transform) ?: return)
}

/** Inserts literal text at the caret position within a paragraph run (B17/B18). */
fun OfficeViewModel.insertTextInRun(start: Int, endInclusive: Int, gPos: Int, insert: String) {
    val doc = curText() ?: return
    updateDocument(doc.insertTextInRun(start, endInclusive, gPos, insert) ?: return)
}

/** Inserts a real ODF text field (date/time/page-number/...) at the caret. (Priority 2) */
fun OfficeViewModel.insertFieldInRun(start: Int, endInclusive: Int, gPos: Int, kind: String, value: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val paras = runParas(start, endInclusive) ?: return
    val lens = paraLens(paras)
    val (pi, off) = runLocate(lens, gPos)
    val para = paras[pi]
    val chars = spansToChars(para.spans)
    val template = OdfSpan(text = "", field = kind)
    val fieldChars = value.map { template.copy(text = it.toString()) }
    val at = off.coerceIn(0, chars.size)
    chars.addAll(at, fieldChars)
    val newContent = doc.content.toMutableList()
    newContent[start + pi] = OdfContentBlock.Paragraph(para.copy(spans = charsToSpans(chars)))
    updateDocument(doc.copy(content = newContent))
}

/** Computes the current display value for a newly-inserted field. (Priority 2) */
fun OfficeViewModel.fieldDisplayValue(kind: String): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document
    val meta = doc?.metadata
    return when (kind) {
        "date" -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        "time" -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        "page-number" -> "1"
        "page-count" -> "1"
        "file-name" -> doc?.title ?: "Untitled"
        "author-name" -> meta?.author ?: meta?.creator ?: ""
        "title" -> meta?.title ?: doc?.title ?: ""
        "subject" -> meta?.subject ?: ""
        "description" -> meta?.description ?: ""
        else -> ""
    }
}

/** Clears all character formatting across a run selection (B24). */
fun OfficeViewModel.clearRunFormatting(start: Int, endInclusive: Int, gStart: Int, gEnd: Int) {
    val doc = curText() ?: return
    updateDocument(doc.clearRunFormatting(start, endInclusive, gStart, gEnd) ?: return)
}

/** Promote/demote a list item's nesting level (B13). */
fun OfficeViewModel.changeListLevel(blockIndex: Int, delta: Int) {
    val doc = curText() ?: return
    updateDocument(doc.changeListLevel(blockIndex, delta) ?: return)
}

/** Restart numbering at 1 for a numbered list item (B13). */
fun OfficeViewModel.restartNumbering(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.restartNumbering(blockIndex) ?: return)
}

/** Inserts an image (already-read bytes) after the given block (B14). */
fun OfficeViewModel.insertImage(blockIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val content = doc.content.toMutableList()
    val at = (blockIndex + 1).coerceIn(0, content.size)
    content.add(at, OdfContentBlock.Image(OdfImage(path = path, imageData = bytes)))
    updateDocument(doc.copy(content = content, images = doc.images + (path to bytes)))
}

/** Generates a unique package path for a newly-inserted image so two inserts never collide. (A6) */
internal fun OfficeViewModel.uniqueImagePath(existing: Set<String>, fileName: String): String {
    val safe = fileName.substringAfterLast('/').ifBlank { "image" }
    val base = safe.substringBeforeLast('.', safe)
    val ext = safe.substringAfterLast('.', "")
    var candidate = "Pictures/$safe"
    var n = 1
    while (candidate in existing) {
        candidate = if (ext.isNotEmpty()) "Pictures/${base}_$n.$ext" else "Pictures/${base}_$n"
        n++
    }
    return candidate
}

/** Sets non-destructive crop insets on a text-document image block. (Phase 5) */
fun OfficeViewModel.setImageCrop(blockIndex: Int, left: Float, top: Float, right: Float, bottom: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Image ?: return
    val content = doc.content.toMutableList()
    content[blockIndex] = OdfContentBlock.Image(block.image.copy(
        cropLeftPct = left, cropTopPct = top, cropRightPct = right, cropBottomPct = bottom
    ))
    updateDocument(doc.copy(content = content))
}

/** Inserts a horizontal rule (rendered as a bordered empty paragraph) (B19). */
fun OfficeViewModel.insertHorizontalLine(blockIndex: Int) {
    val doc = curText() ?: return
    updateDocument(doc.insertHorizontalLine(blockIndex))
}

/**
 * Deletes the block immediately before [runStart] if it's a non-paragraph object (image, page
 * break, chart, table of contents, formula, table…). Lets Backspace at the very start of a run
 * remove the object above it. Returns true if something was deleted.
 */
fun OfficeViewModel.deleteBlockBefore(runStart: Int): Boolean {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return false
    val idx = runStart - 1
    val block = doc.content.getOrNull(idx) ?: return false
    if (block is OdfContentBlock.Paragraph) return false // ordinary text merge is handled by the editor
    val content = doc.content.toMutableList()
    content.removeAt(idx)
    // Drop an image's binary data too so it isn't left orphaned in the package.
    val newDoc = if (block is OdfContentBlock.Image)
        doc.copy(content = content, images = doc.images - block.image.path)
    else doc.copy(content = content)
    updateDocument(newDoc)
    return true
}

/** Generates a Table of Contents from headings and inserts it at [blockIndex] (the cursor). */
fun OfficeViewModel.insertTableOfContents(blockIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val headingStyles = setOf(ParagraphStyle.HEADING1, ParagraphStyle.HEADING2, ParagraphStyle.HEADING3, ParagraphStyle.HEADING4)
    val entries = mutableListOf<OdfParagraph>()
    for (block in doc.content) {
        val para = (block as? OdfContentBlock.Paragraph)?.paragraph ?: continue
        if (para.style !in headingStyles) continue
        val text = para.spans.joinToString("") { it.text }.trim()
        if (text.isEmpty()) continue
        val level = when (para.style) { ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2; ParagraphStyle.HEADING3 -> 3; else -> 4 }
        entries.add(OdfParagraph(listOf(OdfSpan(text = text)), marginLeft = (level - 1) * 18f))
    }
    if (entries.isEmpty()) return
    val toc = OdfContentBlock.TableOfContents("Table of Contents", entries)
    val content = doc.content.toMutableList()
    val at = (blockIndex + 1).coerceIn(0, content.size)
    content.add(at, OdfContentBlock.PageBreak)
    content.add(at, toc)
    updateDocument(doc.copy(content = content))
}
