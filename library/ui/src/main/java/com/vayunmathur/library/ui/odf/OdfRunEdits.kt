package com.vayunmathur.library.ui.odf

/**
 * Framework-free single-paragraph + continuous (multi-paragraph run) edits for an
 * [OdfDocument.TextDocument]. Pure transforms shared by the Office ViewModel and the
 * standalone [OdfTextEditorState]. Each mutation returns a new document (or null when it
 * is a no-op / invalid request); queries return their value directly.
 */

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
