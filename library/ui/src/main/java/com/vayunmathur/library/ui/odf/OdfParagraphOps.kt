package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign

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
