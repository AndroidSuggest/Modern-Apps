package com.vayunmathur.code.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import com.vayunmathur.code.syntax.LanguageSpec
import com.vayunmathur.code.syntax.SyntaxColors
import com.vayunmathur.code.syntax.TsColorSpan
import com.vayunmathur.code.util.Diagnostic
import com.vayunmathur.code.util.DiagnosticSeverity
import com.vayunmathur.code.util.FoldRegion

/** Largest visible-line index k with `visualStarts[k] <= visualRow` (soft-wrap row lookup). */
internal fun visualRowToVisibleIndex(visibleLines: List<Int>, visualStarts: IntArray, visualRow: Int): Int {
    if (visibleLines.isEmpty()) return 0
    var lo = 0
    var hi = visibleLines.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (visualStarts[mid] <= visualRow) lo = mid else hi = mid - 1
    }
    return lo
}

/** Non-wrapping draw path: one row per visible source line, monospaced columns. */
internal fun DrawScope.drawPlainLines(
    measurer: TextMeasurer,
    style: TextStyle,
    charWidth: Float,
    lineHeight: Float,
    gutterWidth: Float,
    scrollX: Float,
    scrollY: Float,
    visibleLines: List<Int>,
    lines: List<String>,
    lineStarts: IntArray,
    caret: Int,
    selMin: Int,
    selMax: Int,
    highlightMatches: List<IntRange>,
    activeMatch: Int,
    caretColor: Color,
    colors: SyntaxColors,
    spec: LanguageSpec?,
    tsSpans: List<TsColorSpan>?,
    diagnosticsByLine: Map<Int, List<Diagnostic>>,
    severityColor: (DiagnosticSeverity) -> Color,
    foldedHeaders: Set<Int>,
    foldByHeader: Map<Int, FoldRegion>,
    extraCarets: List<Int>,
    extraCaretColor: Color,
    composing: TextRange?,
    showIndentGuides: Boolean,
    tabWidth: Int,
    guideColor: Color,
    showWhitespace: Boolean,
    gutterColor: Color,
) {
    val firstRow = (scrollY / lineHeight).toInt().coerceAtLeast(0)
    val rowsInView = (size.height / lineHeight).toInt() + 2
    val lastRow = (firstRow + rowsInView).coerceAtMost(visibleLines.size - 1)

    for (row in firstRow..lastRow) {
        val source = visibleLines[row]
        val y = row * lineHeight - scrollY
        val lineStart = lineStarts[source]
        val lineText = lines[source]
        val lineLen = lineText.length

        if (caret in lineStart..(lineStart + lineLen)) {
            drawRect(colors.currentLine, topLeft = androidx.compose.ui.geometry.Offset(0f, y), size = Size(size.width, lineHeight))
        }

        if (selMax > selMin) {
            val a = (selMin - lineStart).coerceIn(0, lineLen)
            val spansEol = selMax > lineStart + lineLen
            val b = if (spansEol) lineLen else (selMax - lineStart).coerceIn(0, lineLen)
            if (b > a || (selMin <= lineStart + lineLen && spansEol)) {
                val startX = gutterWidth + a * charWidth - scrollX
                val w = (b - a).coerceAtLeast(0) * charWidth + if (spansEol) charWidth else 0f
                if (w > 0f) drawRect(colors.match, topLeft = androidx.compose.ui.geometry.Offset(startX, y), size = Size(w, lineHeight))
            }
        }

        // Find-match highlight (Phase 4): the active match is drawn more strongly.
        if (highlightMatches.isNotEmpty()) {
            for ((mi, m) in highlightMatches.withIndex()) {
                val ms = m.first
                val me = m.last + 1
                if (me <= lineStart || ms >= lineStart + lineLen) continue
                val a = (ms - lineStart).coerceIn(0, lineLen)
                val b = (me - lineStart).coerceIn(0, lineLen)
                if (b > a) {
                    val startX = gutterWidth + a * charWidth - scrollX
                    val w = (b - a) * charWidth
                    val matchColor = if (mi == activeMatch) caretColor.copy(alpha = 0.35f) else colors.match
                    drawRect(matchColor, topLeft = androidx.compose.ui.geometry.Offset(startX, y), size = Size(w, lineHeight))
                }
            }
        }

        if (showIndentGuides) {
            val columns = leadingColumns(lineText, tabWidth)
            var level = tabWidth
            while (level < columns) {
                val gx = gutterWidth + level * charWidth - scrollX
                drawRect(guideColor, topLeft = androidx.compose.ui.geometry.Offset(gx, y), size = Size(1f, lineHeight))
                level += tabWidth
            }
        }

        if (showWhitespace) {
            var i = 0
            var col = 0
            while (i < lineText.length && (lineText[i] == ' ' || lineText[i] == '\t')) {
                val cx = gutterWidth + col * charWidth - scrollX
                if (lineText[i] == ' ') {
                    drawCircle(guideColor, radius = 1.5f, center = androidx.compose.ui.geometry.Offset(cx + charWidth / 2f, y + lineHeight / 2f))
                    col++
                } else {
                    col += tabWidth
                }
                i++
            }
        }

        // Gutter: line number and fold arrow.
        val number = measurer.measure(AnnotatedString((source + 1).toString()), style)
        drawText(number, color = gutterColor, topLeft = androidx.compose.ui.geometry.Offset(gutterWidth - number.size.width - 6f, y))
        if (foldByHeader.containsKey(source)) {
            val arrow = if (source in foldedHeaders) "▸" else "▾"
            val arrowLayout = measurer.measure(AnnotatedString(arrow), style)
            drawText(arrowLayout, color = gutterColor, topLeft = androidx.compose.ui.geometry.Offset(2f, y))
        }

        // Lines past MAX_LINE_HIGHLIGHT render unstyled, so only the columns inside the
        // viewport need measuring — a one-line multi-megabyte document (a minified JSON
        // blob, say) would otherwise lay its whole line out on every frame.
        if (lineLen > MAX_LINE_HIGHLIGHT) {
            val firstCol = (scrollX / charWidth).toInt().coerceIn(0, lineLen)
            val lastCol = (firstCol + (size.width / charWidth).toInt() + 2).coerceAtMost(lineLen)
            if (lastCol > firstCol) {
                val slice = measurer.measure(AnnotatedString(lineText.substring(firstCol, lastCol)), style)
                drawText(slice, topLeft = androidx.compose.ui.geometry.Offset(gutterWidth + firstCol * charWidth - scrollX, y))
            }
        } else {
            val annotated = annotatedLine(lineText, spec, colors, tsSpans, lineStart)
            drawText(measurer.measure(annotated, style), topLeft = androidx.compose.ui.geometry.Offset(gutterWidth - scrollX, y))
        }

        if (caret in lineStart..(lineStart + lineLen)) {
            val cx = gutterWidth + (caret - lineStart) * charWidth - scrollX
            drawRect(caretColor, topLeft = androidx.compose.ui.geometry.Offset(cx, y), size = Size(2f, lineHeight))
        }
        composing?.let { c ->
            val cs = c.min
            val ce = c.max
            if (ce > lineStart && cs < lineStart + lineLen) {
                val a = (cs - lineStart).coerceIn(0, lineLen)
                val b = (ce - lineStart).coerceIn(0, lineLen)
                if (b > a) {
                    val ux = gutterWidth + a * charWidth - scrollX
                    drawRect(caretColor, topLeft = androidx.compose.ui.geometry.Offset(ux, y + lineHeight - 2f), size = Size((b - a) * charWidth, 2f))
                }
            }
        }
        for (extra in extraCarets) {
            if (extra in lineStart..(lineStart + lineLen)) {
                val cx = gutterWidth + (extra - lineStart) * charWidth - scrollX
                drawRect(extraCaretColor, topLeft = androidx.compose.ui.geometry.Offset(cx, y), size = Size(2f, lineHeight))
            }
        }

        // Diagnostics: squiggly underline per range + a severity dot in the gutter.
        diagnosticsByLine[source]?.let { diags ->
            for (d in diags) {
                val a = d.startCol.coerceIn(0, lineLen)
                val b = (if (d.endCol > d.startCol) d.endCol else lineLen).coerceIn(a + 1, (lineLen + 1))
                val ax = gutterWidth + a * charWidth - scrollX
                val bx = gutterWidth + b * charWidth - scrollX
                drawSquiggle(ax, bx, y + lineHeight - 1f, severityColor(d.severity))
            }
            val worst = diags.minByOrNull { it.severity.ordinal }
            if (worst != null) {
                drawCircle(severityColor(worst.severity), radius = 3f, center = androidx.compose.ui.geometry.Offset(12f, y + lineHeight / 2f))
            }
        }
    }
}

/** Soft-wrap draw path: one measured layout per visible source line. */
internal fun DrawScope.drawWrappedLines(
    measurer: TextMeasurer,
    style: TextStyle,
    wrapConstraints: Constraints,
    lineHeight: Float,
    gutterWidth: Float,
    scrollY: Float,
    visibleLines: List<Int>,
    visualStarts: IntArray,
    totalVisualRows: Int,
    wrapCounts: IntArray,
    lines: List<String>,
    lineStarts: IntArray,
    caret: Int,
    selMin: Int,
    selMax: Int,
    highlightMatches: List<IntRange>,
    activeMatch: Int,
    caretColor: Color,
    colors: SyntaxColors,
    spec: LanguageSpec?,
    tsSpans: List<TsColorSpan>?,
    diagnosticsByLine: Map<Int, List<Diagnostic>>,
    severityColor: (DiagnosticSeverity) -> Color,
    foldedHeaders: Set<Int>,
    foldByHeader: Map<Int, FoldRegion>,
    extraCarets: List<Int>,
    extraCaretColor: Color,
    composing: TextRange?,
    gutterColor: Color,
) {
    val firstVisual = (scrollY / lineHeight).toInt().coerceAtLeast(0)
    val rowsInView = (size.height / lineHeight).toInt() + 2
    val lastVisual = (firstVisual + rowsInView).coerceAtMost((totalVisualRows - 1).coerceAtLeast(0))
    if (totalVisualRows > 0) {
        val firstK = visualRowToVisibleIndex(visibleLines, visualStarts, firstVisual)
        val lastK = visualRowToVisibleIndex(visibleLines, visualStarts, lastVisual)
        for (k in firstK..lastK) {
            val source = visibleLines[k]
            val lineTop = visualStarts[k] * lineHeight - scrollY
            val lineStart = lineStarts[source]
            val lineText = lines[source]
            val lineLen = lineText.length
            val layout = measurer.measure(annotatedLine(lineText, spec, colors, tsSpans, lineStart), style, constraints = wrapConstraints)
            val blockHeight = wrapCounts[source] * lineHeight

            if (caret in lineStart..(lineStart + lineLen)) {
                drawRect(colors.currentLine, topLeft = androidx.compose.ui.geometry.Offset(0f, lineTop), size = Size(size.width, blockHeight))
            }

            if (selMax > selMin) {
                val a = (selMin - lineStart).coerceIn(0, lineLen)
                val b = (selMax - lineStart).coerceIn(0, lineLen)
                if (b > a) {
                    val path = layout.getPathForRange(a, b).apply { translate(androidx.compose.ui.geometry.Offset(gutterWidth, lineTop)) }
                    drawPath(path, colors.match)
                }
            }

            if (highlightMatches.isNotEmpty()) {
                for ((mi, m) in highlightMatches.withIndex()) {
                    val ms = m.first
                    val me = m.last + 1
                    if (me <= lineStart || ms >= lineStart + lineLen) continue
                    val a = (ms - lineStart).coerceIn(0, lineLen)
                    val b = (me - lineStart).coerceIn(0, lineLen)
                    if (b > a) {
                        val path = layout.getPathForRange(a, b).apply { translate(androidx.compose.ui.geometry.Offset(gutterWidth, lineTop)) }
                        drawPath(path, if (mi == activeMatch) caretColor.copy(alpha = 0.35f) else colors.match)
                    }
                }
            }

            val number = measurer.measure(AnnotatedString((source + 1).toString()), style)
            drawText(number, color = gutterColor, topLeft = androidx.compose.ui.geometry.Offset(gutterWidth - number.size.width - 6f, lineTop))
            if (foldByHeader.containsKey(source)) {
                val arrow = if (source in foldedHeaders) "▸" else "▾"
                drawText(measurer.measure(AnnotatedString(arrow), style), color = gutterColor, topLeft = androidx.compose.ui.geometry.Offset(2f, lineTop))
            }

            drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(gutterWidth, lineTop))

            if (caret in lineStart..(lineStart + lineLen)) {
                val rect = layout.getCursorRect((caret - lineStart).coerceIn(0, lineLen))
                drawRect(caretColor, topLeft = androidx.compose.ui.geometry.Offset(gutterWidth + rect.left, lineTop + rect.top), size = Size(2f, rect.height))
            }
            composing?.let { c ->
                if (c.max > lineStart && c.min < lineStart + lineLen) {
                    val a = (c.min - lineStart).coerceIn(0, lineLen)
                    val b = (c.max - lineStart).coerceIn(0, lineLen)
                    if (b > a) {
                        val path = layout.getPathForRange(a, b).apply { translate(androidx.compose.ui.geometry.Offset(gutterWidth, lineTop)) }
                        drawPath(path, caretColor.copy(alpha = 0.25f))
                    }
                }
            }
            for (extra in extraCarets) {
                if (extra in lineStart..(lineStart + lineLen)) {
                    val rect = layout.getCursorRect((extra - lineStart).coerceIn(0, lineLen))
                    drawRect(extraCaretColor, topLeft = androidx.compose.ui.geometry.Offset(gutterWidth + rect.left, lineTop + rect.top), size = Size(2f, rect.height))
                }
            }

            // Diagnostics: highlight the range (wrapped) + a gutter severity dot.
            diagnosticsByLine[source]?.let { diags ->
                for (d in diags) {
                    val a = d.startCol.coerceIn(0, lineLen)
                    val b = (if (d.endCol > d.startCol) d.endCol else lineLen).coerceIn(a + 1, lineLen + 1).coerceAtMost(lineLen)
                    if (b > a) {
                        val path = layout.getPathForRange(a, b).apply { translate(androidx.compose.ui.geometry.Offset(gutterWidth, lineTop)) }
                        drawPath(path, severityColor(d.severity).copy(alpha = 0.20f))
                    }
                }
                val worst = diags.minByOrNull { it.severity.ordinal }
                if (worst != null) {
                    drawCircle(severityColor(worst.severity), radius = 3f, center = androidx.compose.ui.geometry.Offset(12f, lineTop + lineHeight / 2f))
                }
            }
        }
    }
}
