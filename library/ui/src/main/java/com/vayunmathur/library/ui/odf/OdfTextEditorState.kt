package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Framework-free editing core for an [OdfDocument.TextDocument]. The pure transforms live as
 * extension functions on [OdfDocument.TextDocument] (below) so they can be shared by both the
 * Office ViewModel and the standalone [OdfTextEditorState] used by the markdown editor. Each
 * mutation returns a new document (or null when it is a no-op / invalid request); queries return
 * their value directly. None of this touches Android or Compose state.
 */

// region pure helpers

private fun spansToChars(spans: List<OdfSpan>): MutableList<OdfSpan> {
    val out = ArrayList<OdfSpan>()
    for (span in spans) for (ch in span.text) out.add(span.copy(text = ch.toString()))
    return out
}

private fun charsToSpans(chars: List<OdfSpan>): List<OdfSpan> {
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

/** Drops the first [n] characters from a span list, preserving remaining span styling. */
private fun removeLeadingChars(spans: List<OdfSpan>, n: Int): List<OdfSpan> {
    val chars = spansToChars(spans)
    val kept = if (n >= chars.size) emptyList() else chars.subList(n, chars.size)
    return charsToSpans(kept)
}

private fun OdfDocument.TextDocument.runParas(start: Int, endInclusive: Int): List<OdfParagraph>? {
    if (start < 0 || endInclusive >= content.size || start > endInclusive) return null
    val list = ArrayList<OdfParagraph>()
    for (i in start..endInclusive) {
        val b = content[i] as? OdfContentBlock.Paragraph ?: return null
        list.add(b.paragraph)
    }
    return list
}

private fun paraLens(paras: List<OdfParagraph>) = paras.map { p -> p.spans.sumOf { it.text.length } }

private fun runLocate(lens: List<Int>, pos: Int): Pair<Int, Int> {
    var rem = pos
    for (i in lens.indices) {
        if (rem <= lens[i]) return i to rem
        rem -= lens[i] + 1 // consume paragraph chars + separator
        if (rem < 0) return i to lens[i]
    }
    return lens.lastIndex.coerceAtLeast(0) to (lens.lastOrNull() ?: 0)
}

// endregion

// region single-paragraph edits

fun OdfDocument.TextDocument.updateParagraphText(blockIndex: Int, newText: String): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    val para = block.paragraph
    val oldText = para.spans.joinToString("") { it.text }
    if (newText == oldText) return null
    var prefix = 0
    while (prefix < oldText.length && prefix < newText.length && oldText[prefix] == newText[prefix]) prefix++
    var oldEnd = oldText.length
    var newEnd = newText.length
    while (oldEnd > prefix && newEnd > prefix && oldText[oldEnd - 1] == newText[newEnd - 1]) { oldEnd--; newEnd-- }
    val chars = spansToChars(para.spans)
    val template = (chars.getOrNull(prefix - 1) ?: chars.getOrNull(oldEnd) ?: chars.getOrNull(prefix) ?: OdfSpan(text = "")).copy(text = "")
    val result = ArrayList<OdfSpan>(newText.length)
    for (i in 0 until prefix) result.add(chars[i])
    for (i in prefix until newEnd) result.add(template.copy(text = newText[i].toString()))
    for (i in oldEnd until chars.size) result.add(chars[i])
    blocks[blockIndex] = OdfContentBlock.Paragraph(para.copy(spans = charsToSpans(result)))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.applySpanStyleToRange(blockIndex: Int, start: Int, end: Int, transform: (OdfSpan) -> OdfSpan): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    val chars = spansToChars(block.paragraph.spans)
    if (chars.isEmpty()) return null
    val s = start.coerceIn(0, chars.size)
    val e = end.coerceIn(s, chars.size)
    val range = if (s == e) chars.indices else (s until e)
    for (i in range) chars[i] = transform(chars[i])
    blocks[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = charsToSpans(chars)))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.rangeHasFormat(blockIndex: Int, start: Int, end: Int, predicate: (OdfSpan) -> Boolean): Boolean {
    val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return false
    val chars = spansToChars(block.paragraph.spans)
    if (chars.isEmpty()) return false
    val s = start.coerceIn(0, chars.size)
    val e = end.coerceIn(s, chars.size)
    val range = if (s == e) chars.indices else (s until e)
    return range.all { predicate(chars[it]) }
}

// endregion

// region continuous (multi-paragraph run) edits

fun OdfDocument.TextDocument.updateParagraphRun(start: Int, endInclusive: Int, newText: String): OdfDocument.TextDocument? {
    val paras = runParas(start, endInclusive) ?: return null
    val lens = paraLens(paras)
    val oldText = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    if (newText == oldText) return null
    var p = 0
    while (p < oldText.length && p < newText.length && oldText[p] == newText[p]) p++
    var oldEnd = oldText.length; var newEnd = newText.length
    while (oldEnd > p && newEnd > p && oldText[oldEnd - 1] == newText[newEnd - 1]) { oldEnd--; newEnd-- }
    val (sp, so) = runLocate(lens, p)
    val (ep, eo) = runLocate(lens, oldEnd)
    val middle = newText.substring(p, newEnd)
    val startChars = spansToChars(paras[sp].spans)
    val endChars = spansToChars(paras[ep].spans)
    val head = startChars.subList(0, so.coerceIn(0, startChars.size)).toMutableList()
    val tail = endChars.subList(eo.coerceIn(0, endChars.size), endChars.size).toMutableList()
    val template = (head.lastOrNull() ?: startChars.firstOrNull() ?: paras[sp].spans.firstOrNull() ?: OdfSpan(text = "")).copy(text = "")
    val segments = middle.split("\n")
    val rebuilt = ArrayList<OdfParagraph>()
    if (segments.size == 1) {
        val chars = ArrayList<OdfSpan>(head)
        for (ch in segments[0]) chars.add(template.copy(text = ch.toString()))
        chars.addAll(tail)
        rebuilt.add(paras[sp].copy(spans = charsToSpans(chars)))
    } else {
        val first = ArrayList<OdfSpan>(head)
        for (ch in segments.first()) first.add(template.copy(text = ch.toString()))
        rebuilt.add(paras[sp].copy(spans = charsToSpans(first)))
        for (k in 1 until segments.size - 1) {
            val mid = ArrayList<OdfSpan>()
            for (ch in segments[k]) mid.add(template.copy(text = ch.toString()))
            rebuilt.add(paras[sp].copy(spans = charsToSpans(mid)))
        }
        val last = ArrayList<OdfSpan>()
        for (ch in segments.last()) last.add(template.copy(text = ch.toString()))
        last.addAll(tail)
        rebuilt.add(paras[ep].copy(spans = charsToSpans(last)))
    }
    val finalParas = ArrayList<OdfParagraph>()
    finalParas.addAll(paras.subList(0, sp))
    finalParas.addAll(rebuilt)
    finalParas.addAll(paras.subList(ep + 1, paras.size))
    val newContent = content.toMutableList()
    for (i in endInclusive downTo start) newContent.removeAt(i)
    newContent.addAll(start, finalParas.map { OdfContentBlock.Paragraph(it) })
    return copy(content = newContent)
}

fun OdfDocument.TextDocument.applyRunSpanStyle(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfSpan) -> OdfSpan): OdfDocument.TextDocument? {
    val paras = runParas(start, endInclusive) ?: return null
    val lens = paraLens(paras)
    val s = minOf(gStart, gEnd); val e = maxOf(gStart, gEnd)
    val newParas = paras.toMutableList()
    if (s == e) {
        val (pi, _) = runLocate(lens, s)
        val chars = spansToChars(paras[pi].spans)
        for (k in chars.indices) chars[k] = transform(chars[k])
        newParas[pi] = paras[pi].copy(spans = charsToSpans(chars))
    } else {
        var base = 0
        for (i in paras.indices) {
            val from = (maxOf(s, base) - base)
            val to = (minOf(e, base + lens[i]) - base)
            if (to > from) {
                val chars = spansToChars(paras[i].spans)
                for (k in from until to.coerceAtMost(chars.size)) chars[k] = transform(chars[k])
                newParas[i] = paras[i].copy(spans = charsToSpans(chars))
            }
            base += lens[i] + 1
        }
    }
    val newContent = content.toMutableList()
    for (i in start..endInclusive) newContent[i] = OdfContentBlock.Paragraph(newParas[i - start])
    return copy(content = newContent)
}

fun OdfDocument.TextDocument.runRangeHasFormat(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, predicate: (OdfSpan) -> Boolean): Boolean {
    val paras = runParas(start, endInclusive) ?: return false
    val lens = paraLens(paras)
    val s = minOf(gStart, gEnd); val e = maxOf(gStart, gEnd)
    val sel = ArrayList<OdfSpan>()
    if (s == e) {
        val (pi, _) = runLocate(lens, s)
        sel.addAll(spansToChars(paras[pi].spans))
    } else {
        var base = 0
        for (i in paras.indices) {
            val from = (maxOf(s, base) - base)
            val to = (minOf(e, base + lens[i]) - base)
            if (to > from) {
                val chars = spansToChars(paras[i].spans)
                sel.addAll(chars.subList(from.coerceIn(0, chars.size), to.coerceIn(0, chars.size)))
            }
            base += lens[i] + 1
        }
    }
    return sel.isNotEmpty() && sel.all(predicate)
}

fun OdfDocument.TextDocument.runParagraphIndexAt(start: Int, endInclusive: Int, gPos: Int): Int {
    val paras = runParas(start, endInclusive) ?: return start
    val (pi, _) = runLocate(paraLens(paras), gPos)
    return start + pi
}

fun OdfDocument.TextDocument.mutateRunParagraphs(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfParagraph) -> OdfParagraph): OdfDocument.TextDocument? {
    val paras = runParas(start, endInclusive) ?: return null
    val lens = paraLens(paras)
    val lo = runLocate(lens, minOf(gStart, gEnd)).first
    val hi = runLocate(lens, maxOf(gStart, gEnd)).first
    val newContent = content.toMutableList()
    for (i in lo..hi) newContent[start + i] = OdfContentBlock.Paragraph(transform(paras[i]))
    return copy(content = newContent)
}

fun OdfDocument.TextDocument.insertTextInRun(start: Int, endInclusive: Int, gPos: Int, insert: String): OdfDocument.TextDocument? {
    val paras = runParas(start, endInclusive) ?: return null
    val full = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    val pos = gPos.coerceIn(0, full.length)
    val newText = full.substring(0, pos) + insert + full.substring(pos)
    return updateParagraphRun(start, endInclusive, newText)
}

fun OdfDocument.TextDocument.clearRunFormatting(start: Int, endInclusive: Int, gStart: Int, gEnd: Int): OdfDocument.TextDocument? =
    applyRunSpanStyle(start, endInclusive, gStart, gEnd) {
        it.copy(bold = false, italic = false, underline = false, strikethrough = false,
            color = null, backgroundColor = null, fontSize = null, superscript = false, subscript = false)
    }

// endregion

// region list / heading / indent / paragraph ops

fun OdfDocument.TextDocument.changeListLevel(blockIndex: Int, delta: Int): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    val para = block.paragraph
    if (para.style != ParagraphStyle.LIST_ITEM) return null
    blocks[blockIndex] = OdfContentBlock.Paragraph(para.copy(listLevel = (para.listLevel + delta).coerceIn(1, 9)))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.restartNumbering(blockIndex: Int): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    blocks[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(listItemIndex = 1))
    var idx = 1
    var i = blockIndex + 1
    val level = block.paragraph.listLevel
    while (i < blocks.size) {
        val b = blocks[i] as? OdfContentBlock.Paragraph ?: break
        if (b.paragraph.style != ParagraphStyle.LIST_ITEM || b.paragraph.listType != ListType.NUMBERED || b.paragraph.listLevel != level) break
        idx++
        blocks[i] = OdfContentBlock.Paragraph(b.paragraph.copy(listItemIndex = idx))
        i++
    }
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.insertHorizontalLine(blockIndex: Int): OdfDocument.TextDocument {
    val blocks = content.toMutableList()
    val at = (blockIndex + 1).coerceIn(0, blocks.size)
    blocks.add(at, OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")), borderColor = 0xFF888888L)))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.toggleListItem(blockIndex: Int): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    val para = block.paragraph
    val isBullet = para.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.BULLET
    val newPara = if (isBullet) {
        para.copy(style = ParagraphStyle.BODY, listLevel = 0)
    } else {
        para.copy(
            style = ParagraphStyle.LIST_ITEM,
            listLevel = if (para.style == ParagraphStyle.LIST_ITEM) para.listLevel else 1,
            listType = ListType.BULLET,
            listItemIndex = 1,
        )
    }
    blocks[blockIndex] = OdfContentBlock.Paragraph(newPara)
    return renumberLists(copy(content = blocks))
}

fun OdfDocument.TextDocument.toggleNumberedList(blockIndex: Int): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    val para = block.paragraph
    val isNumbered = para.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED
    val newPara = if (isNumbered) {
        para.copy(style = ParagraphStyle.BODY, listLevel = 0)
    } else {
        para.copy(
            style = ParagraphStyle.LIST_ITEM,
            listLevel = if (para.style == ParagraphStyle.LIST_ITEM) para.listLevel else 1,
            listType = ListType.NUMBERED,
            listItemIndex = 1,
        )
    }
    blocks[blockIndex] = OdfContentBlock.Paragraph(newPara)
    return renumberLists(copy(content = blocks))
}

fun OdfDocument.TextDocument.indentParagraph(blockIndex: Int): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    blocks[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(marginLeft = block.paragraph.marginLeft + 24f))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.outdentParagraph(blockIndex: Int): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    blocks[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(marginLeft = maxOf(0f, block.paragraph.marginLeft - 24f)))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.setParagraphStyle(blockIndex: Int, style: ParagraphStyle): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    blocks[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(style = style))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.setParagraphAlignment(blockIndex: Int, alignment: TextAlign?): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    blocks[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(alignment = alignment))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.toggleSpanFormat(blockIndex: Int, transform: (OdfSpan) -> OdfSpan): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    val updatedSpans = block.paragraph.spans.map(transform)
    blocks[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = updatedSpans))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.addParagraphAfter(blockIndex: Int): OdfDocument.TextDocument {
    val blocks = content.toMutableList()
    blocks.add(blockIndex + 1, OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")))))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.deleteParagraph(blockIndex: Int): OdfDocument.TextDocument? {
    if (content.size <= 1) return null
    val blocks = content.toMutableList()
    if (blockIndex !in blocks.indices) return null
    blocks.removeAt(blockIndex)
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.duplicateParagraph(blockIndex: Int): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    blocks.add(blockIndex + 1, OdfContentBlock.Paragraph(block.paragraph.copy()))
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.moveParagraphUp(blockIndex: Int): OdfDocument.TextDocument? {
    if (blockIndex <= 0 || blockIndex >= content.size) return null
    val blocks = content.toMutableList()
    val item = blocks.removeAt(blockIndex)
    blocks.add(blockIndex - 1, item)
    return copy(content = blocks)
}

fun OdfDocument.TextDocument.moveParagraphDown(blockIndex: Int): OdfDocument.TextDocument? {
    if (blockIndex < 0 || blockIndex >= content.size - 1) return null
    val blocks = content.toMutableList()
    val item = blocks.removeAt(blockIndex)
    blocks.add(blockIndex + 1, item)
    return copy(content = blocks)
}

// endregion

// region smart list editing + checkbox

/** Global offset of the start of paragraph [pi] within the run. */
private fun paragraphStartGlobal(lens: List<Int>, pi: Int): Int {
    var acc = 0
    for (k in 0 until pi) acc += lens[k] + 1
    return acc
}

/** Re-assigns listItemIndex for contiguous numbered list items per nesting level. */
fun renumberLists(doc: OdfDocument.TextDocument): OdfDocument.TextDocument {
    val counters = HashMap<Int, Int>()
    var changed = false
    val blocks = doc.content.map { block ->
        val para = (block as? OdfContentBlock.Paragraph)?.paragraph ?: run { counters.clear(); return@map block }
        if (para.style == ParagraphStyle.LIST_ITEM && para.listType == ListType.NUMBERED) {
            val level = para.listLevel.coerceAtLeast(1)
            val n = (counters[level] ?: 0) + 1
            counters[level] = n
            counters.keys.filter { it > level }.toList().forEach { counters.remove(it) }
            if (para.listItemIndex != n) { changed = true; OdfContentBlock.Paragraph(para.copy(listItemIndex = n)) } else block
        } else {
            counters.clear()
            block
        }
    }
    return if (changed) doc.copy(content = blocks) else doc
}

/**
 * Fills in running numbers for numbered list items an importer left unnumbered (index 0), which
 * would otherwise all render as "1." Items the importer *did* number are left alone — they may
 * legitimately start at N or continue a previous list — but they still advance the counter. (bugfix)
 */
fun numberUnnumberedListItems(doc: OdfDocument.TextDocument): OdfDocument.TextDocument {
    val counters = HashMap<Int, Int>()
    var changed = false
    val blocks = doc.content.map { block ->
        val para = (block as? OdfContentBlock.Paragraph)?.paragraph
        if (para == null || para.style != ParagraphStyle.LIST_ITEM || para.listType != ListType.NUMBERED) {
            counters.clear()
            return@map block
        }
        val level = para.listLevel.coerceAtLeast(1)
        if (para.listItemIndex > 0) {
            counters[level] = para.listItemIndex
            return@map block
        }
        val n = (counters[level] ?: 0) + 1
        counters[level] = n
        counters.keys.filter { it > level }.toList().forEach { counters.remove(it) }
        changed = true
        OdfContentBlock.Paragraph(para.copy(listItemIndex = n))
    }
    return if (changed) doc.copy(content = blocks) else doc
}

/**
 * Handles Enter at [gPos] by splitting the current paragraph explicitly, so the markdown editor
 * never relies on the text-diff reconciler for newline insertion (which misattributes a newline
 * inserted right before an existing list line, wrongly giving the new line that list's marker).
 *
 * Empty list item → exit the list (becomes a normal paragraph, no new line). Otherwise the paragraph
 * is split at the caret: a list item continues the list on the new line (next number for numbered
 * lists, a fresh unchecked box for checklists); a heading's continuation becomes a body paragraph;
 * everything else keeps its style. Returns the new document + caret offset, or null if [gPos] is out
 * of range (fall back to a plain newline).
 */
fun OdfDocument.TextDocument.handleListEnter(start: Int, endInclusive: Int, gPos: Int): Pair<OdfDocument.TextDocument, Int>? {
    val paras = runParas(start, endInclusive) ?: return null
    val lens = paraLens(paras)
    val (pi, off) = runLocate(lens, gPos)
    val p = paras[pi]
    val text = p.spans.joinToString("") { it.text }
    val lineStart = paragraphStartGlobal(lens, pi)
    val isList = p.style == ParagraphStyle.LIST_ITEM
    val isHeading = p.style == ParagraphStyle.HEADING1 || p.style == ParagraphStyle.HEADING2 ||
        p.style == ParagraphStyle.HEADING3 || p.style == ParagraphStyle.HEADING4
    // Empty list item: exit the list.
    if (isList && text.isBlank()) {
        val blocks = content.toMutableList()
        blocks[start + pi] = OdfContentBlock.Paragraph(p.copy(style = ParagraphStyle.BODY, listLevel = 0, listChecked = false))
        return renumberLists(copy(content = blocks)) to lineStart
    }
    // Split the paragraph at the caret.
    val chars = spansToChars(p.spans)
    val cut = off.coerceIn(0, chars.size)
    val firstPara = p.copy(spans = charsToSpans(chars.subList(0, cut)))
    val after = charsToSpans(chars.subList(cut, chars.size))
    val secondPara = when {
        isList -> p.copy(spans = after, listChecked = false, listItemIndex = p.listItemIndex + 1)
        isHeading -> OdfParagraph(spans = after)
        else -> p.copy(spans = after)
    }
    val blocks = content.toMutableList()
    blocks[start + pi] = OdfContentBlock.Paragraph(firstPara)
    blocks.add(start + pi + 1, OdfContentBlock.Paragraph(secondPara))
    val newCaret = lineStart + cut + 1
    return renumberLists(copy(content = blocks)) to newCaret
}

/**
 * Smart Backspace at the very start of a list item: outdent or remove the marker (nested → one level
 * shallower; top-level → plain paragraph) instead of merging into the previous line. Returns the new
 * document + caret, or null for the default delete.
 */
fun OdfDocument.TextDocument.handleListBackspace(start: Int, endInclusive: Int, gPos: Int): Pair<OdfDocument.TextDocument, Int>? {
    val paras = runParas(start, endInclusive) ?: return null
    val lens = paraLens(paras)
    val (pi, off) = runLocate(lens, gPos)
    if (off != 0) return null
    val p = paras[pi]
    if (p.style != ParagraphStyle.LIST_ITEM) return null
    val lineStart = paragraphStartGlobal(lens, pi)
    val np = if (p.listLevel > 1) p.copy(listLevel = p.listLevel - 1)
    else p.copy(style = ParagraphStyle.BODY, listLevel = 0, listChecked = false)
    val blocks = content.toMutableList()
    blocks[start + pi] = OdfContentBlock.Paragraph(np)
    return renumberLists(copy(content = blocks)) to lineStart
}

/** Toggles a task checklist item on/off for the given paragraph. */
fun OdfDocument.TextDocument.toggleCheckbox(blockIndex: Int): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    val p = block.paragraph
    val isCheckbox = p.style == ParagraphStyle.LIST_ITEM && p.listType == ListType.CHECKBOX
    val np = if (isCheckbox) {
        p.copy(style = ParagraphStyle.BODY, listLevel = 0, listChecked = false)
    } else {
        p.copy(
            style = ParagraphStyle.LIST_ITEM,
            listLevel = if (p.style == ParagraphStyle.LIST_ITEM) p.listLevel else 1,
            listType = ListType.CHECKBOX,
            listChecked = false,
            listItemIndex = 1,
        )
    }
    blocks[blockIndex] = OdfContentBlock.Paragraph(np)
    return renumberLists(copy(content = blocks))
}

/** Sets the checked state of a CHECKBOX list item (used by tapping the box). */
fun OdfDocument.TextDocument.setCheckboxChecked(blockIndex: Int, checked: Boolean): OdfDocument.TextDocument? {
    val blocks = content.toMutableList()
    val block = blocks.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return null
    val p = block.paragraph
    if (p.style != ParagraphStyle.LIST_ITEM || p.listType != ListType.CHECKBOX) return null
    blocks[blockIndex] = OdfContentBlock.Paragraph(p.copy(listChecked = checked))
    return copy(content = blocks)
}

/** Extent and target of a link span within a run. */
data class OdfLinkSpan(val gStart: Int, val gEnd: Int, val text: String, val url: String)

/** Finds the link covering the caret at [gPos] (inclusive of its boundaries), or null. */
fun OdfDocument.TextDocument.linkAt(start: Int, endInclusive: Int, gPos: Int): OdfLinkSpan? {
    val paras = runParas(start, endInclusive) ?: return null
    val lens = paraLens(paras)
    val (pi, off) = runLocate(lens, gPos)
    val chars = spansToChars(paras[pi].spans)
    val href = chars.getOrNull(off)?.href ?: chars.getOrNull(off - 1)?.href ?: return null
    var l = off.coerceIn(0, chars.size)
    while (l > 0 && chars[l - 1].href == href) l--
    var r = off.coerceIn(0, chars.size)
    while (r < chars.size && chars[r].href == href) r++
    val text = chars.subList(l, r).joinToString("") { it.text }
    val base = paragraphStartGlobal(lens, pi)
    return OdfLinkSpan(base + l, base + r, text, href)
}

/** Replaces the run range [gStart, gEnd) with a single link span ([text] pointing at [url]). */
fun OdfDocument.TextDocument.setLinkInRun(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, text: String, url: String): OdfDocument.TextDocument? {
    val paras = runParas(start, endInclusive) ?: return null
    val lens = paraLens(paras)
    val s = minOf(gStart, gEnd); val e = maxOf(gStart, gEnd)
    val (pStart, oStart) = runLocate(lens, s)
    val (pEnd, oEnd) = runLocate(lens, e)
    if (pStart != pEnd) return null // links live within a single paragraph
    val p = paras[pStart]
    val chars = spansToChars(p.spans)
    val cs = oStart.coerceIn(0, chars.size)
    val ce = oEnd.coerceIn(cs, chars.size)
    val template = (chars.getOrNull(cs) ?: chars.getOrNull(cs - 1) ?: OdfSpan(text = ""))
        .copy(text = "", href = url, underline = true, color = 0xFF0066CCL)
    val newChars = ArrayList<OdfSpan>()
    newChars.addAll(chars.subList(0, cs))
    for (ch in text) newChars.add(template.copy(text = ch.toString()))
    newChars.addAll(chars.subList(ce, chars.size))
    val blocks = content.toMutableList()
    blocks[start + pStart] = OdfContentBlock.Paragraph(p.copy(spans = charsToSpans(newChars)))
    return copy(content = blocks)
}

// endregion

/**
 * Stateful, observable editor over a single [OdfDocument.TextDocument] with its own undo/redo.
 * Used by the standalone markdown editor; Office keeps its own ViewModel-level state but shares the
 * pure transforms above.
 */
class OdfTextEditorState(initial: OdfDocument.TextDocument) {
    private val _document = MutableStateFlow(initial)
    val document: StateFlow<OdfDocument.TextDocument> = _document

    val current: OdfDocument.TextDocument get() = _document.value

    private val undoStack = ArrayDeque<OdfDocument.TextDocument>()
    private val redoStack = ArrayDeque<OdfDocument.TextDocument>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    private fun commit(newDoc: OdfDocument.TextDocument?) {
        val nd = newDoc ?: return
        undoStack.addLast(_document.value)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        // Keep ordered-list numbering correct after every structural edit (indent, move, delete, …).
        _document.value = renumberLists(nd)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_document.value)
        _document.value = prev
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_document.value)
        _document.value = next
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    // --- continuous run editing ---
    fun updateParagraphRun(start: Int, endInclusive: Int, newText: String) =
        commit(current.updateParagraphRun(start, endInclusive, newText))

    fun applyRunSpanStyle(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfSpan) -> OdfSpan) =
        commit(current.applyRunSpanStyle(start, endInclusive, gStart, gEnd, transform))

    fun runRangeHasFormat(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, predicate: (OdfSpan) -> Boolean): Boolean =
        current.runRangeHasFormat(start, endInclusive, gStart, gEnd, predicate)

    fun runParagraphIndexAt(start: Int, endInclusive: Int, gPos: Int): Int =
        current.runParagraphIndexAt(start, endInclusive, gPos)

    fun mutateRunParagraphs(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfParagraph) -> OdfParagraph) =
        commit(current.mutateRunParagraphs(start, endInclusive, gStart, gEnd, transform))

    fun insertTextInRun(start: Int, endInclusive: Int, gPos: Int, insert: String) =
        commit(current.insertTextInRun(start, endInclusive, gPos, insert))

    fun clearRunFormatting(start: Int, endInclusive: Int, gStart: Int, gEnd: Int) =
        commit(current.clearRunFormatting(start, endInclusive, gStart, gEnd))

    // --- paragraph / list ops ---
    fun changeListLevel(blockIndex: Int, delta: Int) = commit(current.changeListLevel(blockIndex, delta))
    fun restartNumbering(blockIndex: Int) = commit(current.restartNumbering(blockIndex))
    fun insertHorizontalLine(blockIndex: Int) = commit(current.insertHorizontalLine(blockIndex))
    fun toggleListItem(blockIndex: Int) = commit(current.toggleListItem(blockIndex))
    fun toggleNumberedList(blockIndex: Int) = commit(current.toggleNumberedList(blockIndex))
    fun indentParagraph(blockIndex: Int) = commit(current.indentParagraph(blockIndex))
    fun outdentParagraph(blockIndex: Int) = commit(current.outdentParagraph(blockIndex))
    fun setParagraphStyle(blockIndex: Int, style: ParagraphStyle) = commit(current.setParagraphStyle(blockIndex, style))
    fun setParagraphAlignment(blockIndex: Int, alignment: TextAlign?) = commit(current.setParagraphAlignment(blockIndex, alignment))
    fun duplicateParagraph(blockIndex: Int) = commit(current.duplicateParagraph(blockIndex))
    fun moveParagraphUp(blockIndex: Int) = commit(current.moveParagraphUp(blockIndex))
    fun moveParagraphDown(blockIndex: Int) = commit(current.moveParagraphDown(blockIndex))

    fun toggleCheckbox(blockIndex: Int) = commit(current.toggleCheckbox(blockIndex))

    fun setCheckboxChecked(blockIndex: Int, checked: Boolean) = commit(current.setCheckboxChecked(blockIndex, checked))

    fun linkAt(start: Int, endInclusive: Int, gPos: Int): OdfLinkSpan? = current.linkAt(start, endInclusive, gPos)

    fun setLink(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, text: String, url: String) =
        commit(current.setLinkInRun(start, endInclusive, gStart, gEnd, text, url))

    /** Smart Enter; returns the new caret offset if it handled the keystroke, else null. */
    fun handleListEnter(start: Int, endInclusive: Int, gPos: Int): Int? {
        val (doc, caret) = current.handleListEnter(start, endInclusive, gPos) ?: return null
        commit(doc)
        return caret
    }

    /** Smart Backspace; returns the new caret offset if it handled the keystroke, else null. */
    fun handleListBackspace(start: Int, endInclusive: Int, gPos: Int): Int? {
        val (doc, caret) = current.handleListBackspace(start, endInclusive, gPos) ?: return null
        commit(doc)
        return caret
    }
    fun deleteParagraph(blockIndex: Int) = commit(current.deleteParagraph(blockIndex))

    companion object {
        private const val MAX_UNDO = 30
    }
}
