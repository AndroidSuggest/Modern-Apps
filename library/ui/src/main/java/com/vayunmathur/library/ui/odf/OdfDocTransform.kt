package com.vayunmathur.library.ui.odf

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** OffsetMapping for list prefixes injected at the start of each paragraph in a run. (A1) */
internal class PrefixOffsetMapping(
    private val origStarts: IntArray,
    private val transStarts: IntArray,
    private val prefixLens: IntArray,
    private val lens: IntArray,
    private val origLen: Int,
    private val transLen: Int
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        val o = offset.coerceIn(0, origLen)
        var p = 0
        for (i in origStarts.indices) { if (origStarts[i] <= o) p = i else break }
        val inPara = (o - origStarts[p]).coerceIn(0, lens[p])
        return (transStarts[p] + prefixLens[p] + inPara).coerceIn(0, transLen)
    }
    override fun transformedToOriginal(offset: Int): Int {
        val t = offset.coerceIn(0, transLen)
        var p = 0
        for (i in transStarts.indices) { if (transStarts[i] <= t) p = i else break }
        val afterPrefix = t - transStarts[p] - prefixLens[p]
        val inPara = afterPrefix.coerceIn(0, lens[p])
        return (origStarts[p] + inPara).coerceIn(0, origLen)
    }
}

private fun sameLayout(a: OdfParagraph, b: OdfParagraph): Boolean =
    a.alignment == b.alignment && a.lineHeightPercent == b.lineHeightPercent &&
        a.textIndent == 0f && b.textIndent == 0f &&
        a.marginLeft == b.marginLeft && a.listLevel == b.listLevel

/**
 * Transformed-text start offset (start of the injected prefix) for each paragraph. Every paragraph
 * boundary contributes exactly one separator char (see [buildDocTransformed]), so the accounting is
 * simply prefix + text + 1 per non-final paragraph.
 */
internal fun runTransStarts(lens: List<Int>, prefixes: List<String>): IntArray {
    val n = lens.size
    val transStarts = IntArray(n)
    var t = 0
    for (i in 0 until n) {
        transStarts[i] = t
        t += prefixes[i].length + lens[i] + (if (i < n - 1) 1 else 0)
    }
    return transStarts
}

internal fun buildDocTransformed(
    text: String, paras: List<OdfParagraph>, lens: List<Int>, prefixes: List<String>,
    baseColor: Color, prefixColor: Color, mult: Float
): TransformedText {
    val n = paras.size
    val origStarts = IntArray(n)
    val transStarts = IntArray(n)
    val prefixLens = IntArray(n) { prefixes[it].length }

    // Paragraph-break model. Compose makes each ParagraphStyle range its own paragraph, and a '\n'
    // that lands between two ParagraphStyle ranges becomes an extra blank "gap" line. So we keep a
    // '\n' separator only between consecutive paragraphs that share the same paragraph-level layout
    // (alignment, line spacing, no first-line indent): those sit inside ONE shared ParagraphStyle,
    // where the '\n' is an ordinary single line break. At a real layout change the two paragraphs
    // need separate ParagraphStyles, so instead of a '\n' (which would blank-line) OR nothing (which
    // would collapse the end of one block and the start of the next onto a single caret offset and
    // break caret placement / hit-testing), we emit a zero-width space (U+200B) kept INSIDE the
    // first paragraph's ParagraphStyle range: it adds no blank line and no visible glyph, but gives
    // the boundary a real, distinct offset so the caret lands correctly. Either way exactly one
    // separator char is emitted between paragraphs, so origStarts and transStarts both advance by
    // len + 1 per boundary.
    val withinGroup = BooleanArray(n) // withinGroup[i] = paragraphs i and i+1 share a ParagraphStyle
    for (i in 0 until n - 1) {
        withinGroup[i] = sameLayout(paras[i], paras[i + 1]) || lens[i + 1] == 0
    }
    if (n > 0 && lens[0] == 0 && n > 1) withinGroup[0] = true // keep a leading empty paragraph with its neighbour

    var oAcc = 0; var tAcc = 0
    for (i in 0 until n) {
        origStarts[i] = oAcc
        transStarts[i] = tAcc
        oAcc += lens[i] + 1
        tAcc += prefixLens[i] + lens[i] + (if (i < n - 1) 1 else 0)
    }
    val origLen = text.length
    val transLen = if (n == 0) 0 else transStarts[n - 1] + prefixLens[n - 1] + lens[n - 1]

    val annotated = buildAnnotatedString {
        var groupStart = 0   // annotated offset where the current ParagraphStyle group began
        var groupFirst = 0   // index of the first paragraph in the current group
        for (i in paras.indices) {
            if (i == 0 || !withinGroup[i - 1]) { groupStart = length; groupFirst = i }
            val para = paras[i]
            // prefix (styled muted, never bold/italic)
            if (prefixes[i].isNotEmpty()) {
                withStyle(SpanStyle(color = prefixColor)) { append(prefixes[i]) }
            }
            // paragraph text from original
            val pStartOrig = origStarts[i]
            val pEndOrig = (pStartOrig + lens[i]).coerceAtMost(text.length)
            val paraText = if (pEndOrig > pStartOrig) text.substring(pStartOrig, pEndOrig) else ""
            val textStart = length
            append(paraText)
            val textEnd = length
            headingSizeSp(para.style)?.let { addStyle(SpanStyle(fontSize = (it * mult).sp, fontWeight = FontWeight.Bold), textStart, textEnd) }
            var off = textStart
            for (span in para.spans) {
                val segEnd = (off + span.text.length).coerceAtMost(textEnd)
                if (segEnd > off) {
                    val decorations = mutableListOf<TextDecoration>()
                    if (span.underline) decorations.add(TextDecoration.Underline)
                    if (span.strikethrough) decorations.add(TextDecoration.LineThrough)
                    if (span.changeKind == "insertion") decorations.add(TextDecoration.Underline)
                    if (span.changeKind == "deletion") decorations.add(TextDecoration.LineThrough)
                    val changeColor = when (span.changeKind) { "insertion" -> Color(0xFF1B7F3B); "deletion" -> Color(0xFFC62828); else -> null }
                    addStyle(
                        SpanStyle(
                            fontWeight = if (span.bold) FontWeight.Bold else null,
                            fontStyle = if (span.italic) FontStyle.Italic else null,
                            fontSize = span.fontSize?.let { (it * mult).sp } ?: TextUnit.Unspecified,
                            textDecoration = if (decorations.isNotEmpty()) TextDecoration.combine(decorations) else null,
                            color = changeColor ?: span.color?.let { Color(it.toInt()) } ?: Color.Unspecified,
                            background = span.backgroundColor?.let { Color(it.toInt()) } ?: Color.Unspecified,
                            letterSpacing = span.letterSpacing?.sp ?: TextUnit.Unspecified,
                            baselineShift = when { span.superscript -> BaselineShift.Superscript; span.subscript -> BaselineShift.Subscript; else -> null }
                        ), off, segEnd
                    )
                }
                off = segEnd
            }
            val groupEnds = i == n - 1 || !withinGroup[i]
            // Between-group separator: append the zero-width space BEFORE applying the group's
            // ParagraphStyle so it stays inside the group's range (no orphan blank line).
            if (groupEnds && i != n - 1) append("​")
            // At the end of a group, emit ONE ParagraphStyle spanning every paragraph in the group
            // (prefixes, text and interior '\n' separators). The group's paragraph-level layout is
            // taken from its first non-empty paragraph so that an absorbed empty paragraph never
            // overrides the alignment/indent of real content. (A5/A6 + paragraph-spacing fix)
            if (groupEnds) {
                val styleSrc = (groupFirst..i).firstOrNull { lens[it] > 0 }?.let { paras[it] } ?: paras[groupFirst]
                // Left indent for the whole paragraph = paragraph margin + nested-list level; applied to
                // every line via TextIndent.restLine, with the first line getting the extra first-line indent.
                val leftIndent = styleSrc.marginLeft + maxOf(0, styleSrc.listLevel - 1) * 16f
                val firstLineIndent = (leftIndent + styleSrc.textIndent).sp
                val restIndent = leftIndent.sp
                // Justify is stretched at draw time, but BasicTextField computes caret / selection /
                // handle positions from the UNSTRETCHED glyph advances, so they drift left of the
                // displayed justified glyphs. Render the editable field with Start alignment so the
                // caret and selection match the glyphs exactly; the OdfParagraph.alignment model is
                // untouched, so read-only rendering (ParagraphView) and export stay justified.
                // (justified-selection alignment fix)
                val editorAlign = if (styleSrc.alignment == TextAlign.Justify) TextAlign.Start else (styleSrc.alignment ?: TextAlign.Unspecified)
                addStyle(
                    androidx.compose.ui.text.ParagraphStyle(
                        textAlign = editorAlign,
                        lineHeight = styleSrc.lineHeightPercent?.let { (22f * mult * it).sp } ?: TextUnit.Unspecified,
                        textIndent = if (leftIndent != 0f || styleSrc.textIndent != 0f) androidx.compose.ui.text.style.TextIndent(firstLine = firstLineIndent, restLine = restIndent) else androidx.compose.ui.text.style.TextIndent.None
                    ),
                    groupStart, length
                )
            } else {
                // Within a group: an ordinary single line break that stays inside the group's range.
                append("\n")
            }
        }
    }
    return TransformedText(annotated, PrefixOffsetMapping(origStarts, transStarts, prefixLens, lens.toIntArray(), origLen, transLen))
}
