package com.vayunmathur.library.ui.odf

/**
 * Framework-free span/paragraph locating helpers shared by the ODF text transforms.
 * Pure Kotlin — no Android or Compose state.
 */

internal fun spansToChars(spans: List<OdfSpan>): MutableList<OdfSpan> {
    val out = ArrayList<OdfSpan>()
    for (span in spans) for (ch in span.text) out.add(span.copy(text = ch.toString()))
    return out
}

internal fun charsToSpans(chars: List<OdfSpan>): List<OdfSpan> {
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
internal fun removeLeadingChars(spans: List<OdfSpan>, n: Int): List<OdfSpan> {
    val chars = spansToChars(spans)
    val kept = if (n >= chars.size) emptyList() else chars.subList(n, chars.size)
    return charsToSpans(kept)
}

internal fun OdfDocument.TextDocument.runParas(start: Int, endInclusive: Int): List<OdfParagraph>? {
    if (start < 0 || endInclusive >= content.size || start > endInclusive) return null
    val list = ArrayList<OdfParagraph>()
    for (i in start..endInclusive) {
        val b = content[i] as? OdfContentBlock.Paragraph ?: return null
        list.add(b.paragraph)
    }
    return list
}

internal fun paraLens(paras: List<OdfParagraph>) = paras.map { p -> p.spans.sumOf { it.text.length } }

internal fun runLocate(lens: List<Int>, pos: Int): Pair<Int, Int> {
    var rem = pos
    for (i in lens.indices) {
        if (rem <= lens[i]) return i to rem
        rem -= lens[i] + 1 // consume paragraph chars + separator
        if (rem < 0) return i to lens[i]
    }
    return lens.lastIndex.coerceAtLeast(0) to (lens.lastOrNull() ?: 0)
}

/** Global offset of the start of paragraph [pi] within the run. */
internal fun paragraphStartGlobal(lens: List<Int>, pi: Int): Int {
    var acc = 0
    for (k in 0 until pi) acc += lens[k] + 1
    return acc
}
