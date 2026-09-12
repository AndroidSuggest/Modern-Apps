package com.vayunmathur.library.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

// ---------------------------------------------------------------------------
// Markdown editing helpers (shared by the notes and email editors).
// ---------------------------------------------------------------------------

internal fun isCheckboxPrefix(line: String) =
    line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ")

internal fun stripLinePrefix(line: String): String {
    Regex("^#{1,6} ").find(line)?.let { return line.substring(it.value.length) }
    if (isCheckboxPrefix(line)) return line.substring(6)
    if (line.startsWith("- ")) return line.substring(2)
    Regex("^\\d+\\. ").find(line)?.let { return line.substring(it.value.length) }
    if (line.startsWith("> ")) return line.substring(2)
    return line
}

internal fun getLinePrefix(line: String): String {
    Regex("^#{1,6} ").find(line)?.let { return it.value }
    if (isCheckboxPrefix(line)) return line.substring(0, 6)
    if (line.startsWith("- ")) return "- "
    Regex("^\\d+\\. ").find(line)?.let { return it.value }
    if (line.startsWith("> ")) return "> "
    return ""
}

internal fun getSelectedLines(text: String, selection: TextRange): Pair<Int, Int> {
    val start = minOf(selection.start, selection.end).coerceIn(0, text.length)
    val end = maxOf(selection.start, selection.end).coerceIn(0, text.length)
    val blockStart = text.lastIndexOf('\n', start - 1) + 1
    val effectiveEnd = if (start != end && end > 0 && text.getOrNull(end - 1) == '\n') end - 1 else end
    val blockEnd = text.indexOf('\n', effectiveEnd).let { if (it == -1) text.length else it }
    return blockStart to blockEnd.coerceAtLeast(blockStart)
}

internal fun matchesPrefix(line: String, prefix: String): Boolean = when {
    prefix == "1. " -> Regex("^\\d+\\. ").containsMatchIn(line)
    prefix == "- [ ] " -> isCheckboxPrefix(line)
    prefix == "- " -> line.startsWith("- ") && !isCheckboxPrefix(line)
    else -> line.startsWith(prefix)
}

internal fun computeBlockSelection(
    text: String,
    selection: TextRange,
    blockStart: Int,
    lines: List<String>,
    newLines: List<String>,
    newBlockText: String,
): TextRange = if (selection.collapsed) {
    val lineIndex = text.substring(blockStart, selection.start).count { it == '\n' }
    val diff = newLines[lineIndex].length - lines[lineIndex].length
    TextRange((selection.start + diff).coerceAtLeast(blockStart))
} else {
    TextRange(blockStart, blockStart + newBlockText.length)
}

internal fun hasInlineMarker(content: String, marker: String): Boolean {
    if (content.length < marker.length * 2) return false
    if (!content.startsWith(marker) || !content.endsWith(marker)) return false
    if (marker == "*" && content.startsWith("**") && !content.startsWith("***")) return false
    if (marker == "*" && content.endsWith("**") && !content.endsWith("***")) return false
    return true
}

fun toggleInlineFormat(value: TextFieldValue, marker: String): TextFieldValue {
    val text = value.text
    val sel = value.selection
    val start = minOf(sel.start, sel.end)
    val end = maxOf(sel.start, sel.end)
    if (start == end) {
        // No selection: drop an empty pair and place the cursor between them.
        val newText = text.substring(0, start) + marker + marker + text.substring(start)
        return value.copy(text = newText, selection = TextRange(start + marker.length))
    }

    val selected = text.substring(start, end)

    // The markers are part of the selection itself.
    if (hasInlineMarker(selected, marker)) {
        val unwrapped = selected.substring(marker.length, selected.length - marker.length)
        val newText = text.substring(0, start) + unwrapped + text.substring(end)
        return value.copy(text = newText, selection = TextRange(start, start + unwrapped.length))
    }

    // The markers sit just outside the selection (greedy, delimiter-run aware).
    if (emphasisActive(text, start, end, marker)) {
        val newText = text.substring(0, start - marker.length) + selected + text.substring(end + marker.length)
        return value.copy(text = newText, selection = TextRange(start - marker.length, start - marker.length + selected.length))
    }

    // Otherwise wrap exactly the selected characters (e.g. a single word), not the line.
    val newText = text.substring(0, start) + marker + selected + marker + text.substring(end)
    return value.copy(text = newText, selection = TextRange(start + marker.length, start + marker.length + selected.length))
}

fun toggleLinePrefix(value: TextFieldValue, prefix: String): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val lines = text.substring(blockStart, blockEnd).split("\n")

    val allHavePrefix = lines.all { matchesPrefix(it, prefix) }

    val newLines = if (allHavePrefix) {
        lines.map { stripLinePrefix(it) }
    } else {
        lines.mapIndexed { index, line ->
            val stripped = stripLinePrefix(line)
            if (prefix == "1. ") "${index + 1}. $stripped" else prefix + stripped
        }
    }

    val newBlockText = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlockText + text.substring(blockEnd)
    return value.copy(text = newText, selection = computeBlockSelection(text, selection, blockStart, lines, newLines, newBlockText))
}

fun insertHeading(value: TextFieldValue, level: Int): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    val lines = text.substring(blockStart, blockEnd).split("\n")
    val targetPrefix = "#".repeat(level) + " "

    val allHaveHeading = lines.all { Regex("^#{1,6} ").find(it)?.value == targetPrefix }

    val newLines = if (allHaveHeading) {
        lines.map { stripLinePrefix(it) }
    } else {
        lines.map { targetPrefix + stripLinePrefix(it) }
    }

    val newBlockText = newLines.joinToString("\n")
    val newText = text.substring(0, blockStart) + newBlockText + text.substring(blockEnd)
    return value.copy(text = newText, selection = computeBlockSelection(text, selection, blockStart, lines, newLines, newBlockText))
}

fun isInlineFormatActive(text: String, selection: TextRange, marker: String): Boolean {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    if (start == end) return false
    val selected = text.substring(start, end)
    return hasInlineMarker(selected, marker) || emphasisActive(text, start, end, marker)
}

/** Length of the run of [ch] immediately to the left of [pos]. */
internal fun runLengthLeft(text: String, pos: Int, ch: Char): Int {
    var i = pos; var n = 0
    while (i > 0 && text[i - 1] == ch) { n++; i-- }
    return n
}

/** Length of the run of [ch] immediately to the right of [pos]. */
internal fun runLengthRight(text: String, pos: Int, ch: Char): Int {
    var i = pos; var n = 0
    while (i < text.length && text[i] == ch) { n++; i++ }
    return n
}

/**
 * Whether [marker] emphasis surrounds the selection, using greedy CommonMark
 * delimiter-run semantics. For asterisks a run is consumed as bold pairs (`**`)
 * with any leftover single asterisk being italic (`*`), so `**`=bold, `*`=italic
 * and `***`=both with no ambiguity. Other markers (`~~`, `` ` ``) just need a
 * full run on each side.
 */
internal fun emphasisActive(text: String, start: Int, end: Int, marker: String): Boolean {
    val ch = marker[0]
    val left = runLengthLeft(text, start, ch)
    val right = runLengthRight(text, end, ch)
    return if (ch == '*') {
        if (marker == "*") (left % 2 == 1) && (right % 2 == 1) // italic = leftover single
        else left >= 2 && right >= 2                            // bold = a pair available
    } else {
        left >= marker.length && right >= marker.length
    }
}

fun isLinePrefixActive(text: String, selection: TextRange, prefix: String): Boolean {
    val (blockStart, blockEnd) = getSelectedLines(text, selection)
    return text.substring(blockStart, blockEnd).split("\n").all { matchesPrefix(it, prefix) }
}

fun getActiveHeadingLevel(text: String, cursorPos: Int): Int? {
    val lineStart = text.lastIndexOf('\n', (cursorPos - 1).coerceAtLeast(0)) + 1
    val lineEnd = text.indexOf('\n', cursorPos).let { if (it == -1) text.length else it }
    val match = Regex("^(#{1,6}) ").find(text.substring(lineStart, lineEnd)) ?: return null
    return match.groupValues[1].length
}

fun insertCodeBlock(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val selection = value.selection
    if (selection.collapsed) {
        val insert = "```\n\n```"
        val newText = text.substring(0, selection.start) + insert + text.substring(selection.start)
        return value.copy(text = newText, selection = TextRange(selection.start + 4))
    }
    val selectedText = text.substring(selection.start, selection.end)
    val newText = text.substring(0, selection.start) + "```\n" + selectedText + "\n```" + text.substring(selection.end)
    return value.copy(text = newText, selection = TextRange(selection.start + 4, selection.start + 4 + selectedText.length))
}

fun insertHorizontalRule(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val cursor = value.selection.start
    val insert = "\n---\n"
    val newText = text.substring(0, cursor) + insert + text.substring(cursor)
    return value.copy(text = newText, selection = TextRange(cursor + insert.length))
}

internal val markdownLinkRegex = Regex("\\[([^\\]]*)]\\(([^)]*)\\)")

/** An existing `[text](url)` link spanning [range] in the document. */
data class MarkdownLinkMatch(val range: IntRange, val text: String, val url: String)

/** The markdown link whose `[text](url)` span contains [cursor], or null. */
fun findMarkdownLinkAt(text: String, cursor: Int): MarkdownLinkMatch? {
    for (m in markdownLinkRegex.findAll(text)) {
        if (cursor >= m.range.first && cursor <= m.range.last + 1) {
            return MarkdownLinkMatch(m.range.first..m.range.last, m.groupValues[1], m.groupValues[2])
        }
    }
    return null
}

/** Link-button state for a markdown [value]; null means the button is disabled. */
fun markdownLinkContext(value: TextFieldValue): LinkContext? {
    val sel = value.selection
    val existing = if (sel.collapsed) findMarkdownLinkAt(value.text, sel.start) else null
    return when {
        existing != null -> LinkContext(editing = true, text = existing.text, url = existing.url)
        !sel.collapsed -> LinkContext(
            editing = false,
            text = value.text.substring(minOf(sel.start, sel.end), maxOf(sel.start, sel.end)),
            url = "",
        )
        else -> null
    }
}

/** Create or edit a markdown link given a [context] (from [markdownLinkContext]). */
fun applyMarkdownLink(value: TextFieldValue, context: LinkContext, text: String, url: String): TextFieldValue {
    val replacement = "[$text]($url)"
    return if (context.editing) {
        val m = findMarkdownLinkAt(value.text, value.selection.start) ?: return value
        val newText = value.text.substring(0, m.range.first) + replacement + value.text.substring(m.range.last + 1)
        value.copy(text = newText, selection = TextRange(m.range.first + replacement.length))
    } else {
        val start = minOf(value.selection.start, value.selection.end)
        val end = maxOf(value.selection.start, value.selection.end)
        val newText = value.text.substring(0, start) + replacement + value.text.substring(end)
        value.copy(text = newText, selection = TextRange(start + replacement.length))
    }
}

internal val checkboxPattern = Regex("^(\\s*- )\\[([ xX])] ")

fun tryToggleCheckbox(offset: Int, value: TextFieldValue): TextFieldValue? {
    val text = value.text
    val lineStart = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1
    val lineEnd = text.indexOf('\n', offset).let { if (it == -1) text.length else it }
    val match = checkboxPattern.find(text.substring(lineStart, lineEnd)) ?: return null
    val bracketOffset = lineStart + match.groups[1]!!.value.length
    if (offset > bracketOffset + 3) return null
    val newChar = if (match.groups[2]!!.value.lowercase() == "x") " " else "x"
    val newText = text.substring(0, bracketOffset + 1) + newChar + text.substring(bracketOffset + 2)
    return value.copy(text = newText, selection = TextRange(offset))
}

fun findCheckboxPositions(text: String): List<Pair<Int, Boolean>> =
    Regex("(?m)^(\\s*- )\\[([ xX])] ").findAll(text).map { match ->
        val bracketOffset = match.groups[1]!!.range.last + 1
        val isChecked = match.groups[2]!!.value.lowercase() == "x"
        bracketOffset to isChecked
    }.toList()
