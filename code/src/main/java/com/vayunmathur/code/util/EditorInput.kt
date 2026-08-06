package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Pure editor-input logic, kept out of the ViewModel so it can be unit-tested and so
 * `onEditorChange` stays a thin call site.
 *
 * [applyEditorInput] looks at a single-character insertion (the common typing case) and, when
 * enabled, carries indentation onto a new line, opens an indented block between a bracket pair,
 * auto-closes brackets/quotes and "types over" a closer that already sits after the caret.
 * Anything that is not a clean single-character insertion is returned unchanged.
 */

private const val OPENERS = "([{"
private const val CLOSERS = ")]}"
private const val QUOTES = "\"'"

private fun matchingCloser(opener: Char): Char = when (opener) {
    '(' -> ')'
    '[' -> ']'
    '{' -> '}'
    else -> opener
}

fun applyEditorInput(
    old: TextFieldValue,
    new: TextFieldValue,
    indentUnit: String,
    autoIndent: Boolean,
    autoCloseBrackets: Boolean,
): TextFieldValue {
    if (!new.selection.collapsed) return new
    val caret = new.selection.start
    // Must be exactly one character longer, inserted so that removing it reproduces the old text.
    if (new.text.length != old.text.length + 1 || caret < 1) return new
    if (old.text != new.text.substring(0, caret - 1) + new.text.substring(caret)) return new

    val c = new.text[caret - 1]
    val pos = caret - 1 // index in old.text where the character was inserted
    val afterChar = old.text.getOrNull(pos) // the character that was to the right of the caret

    // Newline: carry the current line's leading whitespace; open a block between a bracket pair.
    if (c == '\n' && autoIndent) {
        val lineStart = old.text.lastIndexOf('\n', pos - 1) + 1
        var i = lineStart
        while (i < pos && (old.text[i] == ' ' || old.text[i] == '\t')) i++
        val indent = old.text.substring(lineStart, i)

        val beforeChar = old.text.getOrNull(pos - 1)
        if (beforeChar != null && afterChar != null &&
            beforeChar in OPENERS && matchingCloser(beforeChar) == afterChar
        ) {
            val head = old.text.substring(0, pos) + "\n" + indent + indentUnit
            val text = head + "\n" + indent + old.text.substring(pos)
            return TextFieldValue(text, TextRange(head.length))
        }

        if (indent.isEmpty()) return new
        val text = old.text.substring(0, pos) + "\n" + indent + old.text.substring(pos)
        return TextFieldValue(text, TextRange(caret + indent.length))
    }

    if (autoCloseBrackets) {
        // Type-over: typing a closer/quote that already sits immediately after the caret.
        if ((c in CLOSERS || c in QUOTES) && afterChar == c) {
            return TextFieldValue(old.text, TextRange(caret))
        }
        // Auto-close: an opener gets its matching closer; a quote gets a second quote.
        val closer = when {
            c in OPENERS -> matchingCloser(c)
            c in QUOTES -> c
            else -> null
        }
        if (closer != null) {
            val text = new.text.substring(0, caret) + closer + new.text.substring(caret)
            return TextFieldValue(text, TextRange(caret))
        }
    }

    return new
}

/** Char offset of the start of a 1-based [line] in [text], clamped to `0..text.length`. */
fun lineStartOffset(text: String, line: Int): Int {
    val target = line.coerceAtLeast(1)
    var offset = 0
    var current = 1
    while (current < target) {
        val nl = text.indexOf('\n', offset)
        if (nl < 0) return text.length
        offset = nl + 1
        current++
    }
    return offset.coerceAtMost(text.length)
}

/** One matching line from a project search: its 1-based [line] number and a trimmed [preview]. */
data class LineMatch(val line: Int, val preview: String)

/**
 * Pure line-by-line matcher shared by the project search. Returns at most [limit] matching lines.
 * An invalid regex (when [useRegex]) yields no matches rather than throwing.
 */
fun findLineMatches(
    text: String,
    query: String,
    caseSensitive: Boolean,
    useRegex: Boolean,
    limit: Int = Int.MAX_VALUE,
): List<LineMatch> {
    if (query.isEmpty()) return emptyList()
    val regex = if (useRegex) {
        runCatching {
            Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        }.getOrNull() ?: return emptyList()
    } else {
        null
    }
    val result = ArrayList<LineMatch>()
    var lineNo = 0
    for (line in text.lineSequence()) {
        lineNo++
        val hit = if (regex != null) {
            regex.containsMatchIn(line)
        } else {
            line.contains(query, ignoreCase = !caseSensitive)
        }
        if (hit) {
            result.add(LineMatch(lineNo, line.trim().take(PREVIEW_LIMIT)))
            if (result.size >= limit) break
        }
    }
    return result
}

private const val PREVIEW_LIMIT = 200
