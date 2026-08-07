package com.vayunmathur.code.util

import com.vayunmathur.code.syntax.Language
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Diagnostic severity, ordered most→least severe for sorting and gutter colouring. */
enum class DiagnosticSeverity { ERROR, WARNING, INFO }

/**
 * One diagnostic on a single [line] (0-based), spanning columns `[startCol, endCol)` (0-based).
 * A zero-width range ([startCol] == [endCol]) marks a whole-line issue.
 */
data class Diagnostic(
    val line: Int,
    val startCol: Int,
    val endCol: Int,
    val severity: DiagnosticSeverity,
    val message: String,
)

private const val MAX_DIAGNOSTICS = 200

/**
 * In-process, offline diagnostics — a lightweight stand-in for a language server (which cannot run
 * on-device; see the plan). It composes several pure validators: unresolved merge-conflict markers
 * (all languages, via [parseConflicts]), JSON / XML well-formedness, a couple of YAML sanity checks,
 * bracket balance for brace languages, and TODO/FIXME notes. Everything except JSON (`org.json`) and
 * XML (`javax.xml`) is pure Kotlin, so the core is unit-tested directly.
 */
fun computeDiagnostics(text: String, language: Language): List<Diagnostic> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<Diagnostic>()

    out += mergeMarkerDiagnostics(text)
    when (language) {
        Language.JSON -> out += jsonDiagnostics(text)
        Language.XML -> out += xmlDiagnostics(text)
        Language.YAML -> out += yamlDiagnostics(text)
        else -> {}
    }
    if (language in BRACE_LANGUAGES) out += bracketDiagnostics(text, language)
    out += todoDiagnostics(text)

    return out.asSequence()
        .sortedWith(compareBy({ it.line }, { it.startCol }, { it.severity.ordinal }))
        .take(MAX_DIAGNOSTICS)
        .toList()
}

// ---- Merge markers ----

private fun mergeMarkerDiagnostics(text: String): List<Diagnostic> =
    parseConflicts(text).map { conflict ->
        val lines = text.split("\n")
        val marker = lines.getOrNull(conflict.startLine).orEmpty()
        Diagnostic(
            line = conflict.startLine,
            startCol = 0,
            endCol = marker.length,
            severity = DiagnosticSeverity.ERROR,
            message = "Unresolved merge conflict",
        )
    }

// ---- JSON ----

private fun jsonDiagnostics(text: String): List<Diagnostic> {
    val trimmed = text.trimStart()
    if (trimmed.isEmpty()) return emptyList()
    val result = runCatching {
        if (trimmed.startsWith("[")) JSONArray(text) else JSONObject(text)
    }
    val error = result.exceptionOrNull() ?: return emptyList()
    val message = error.message ?: "Invalid JSON"
    // org.json reports the failing offset as "... at character N ...".
    val offset = Regex("character (\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull()
    val (line, col) = if (offset != null) offsetToLineCol(text, offset) else 0 to 0
    return listOf(Diagnostic(line, col, col, DiagnosticSeverity.ERROR, message.substringBefore(" at ").ifBlank { "Invalid JSON" }))
}

// ---- XML ----

private fun xmlDiagnostics(text: String): List<Diagnostic> {
    val head = text.trimStart().take(64).lowercase()
    // HTML isn't required to be well-formed XML; don't flag it.
    if (head.startsWith("<!doctype html") || head.startsWith("<html")) return emptyList()
    if (text.isBlank()) return emptyList()

    val factory = DocumentBuilderFactory.newInstance()
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    val error = runCatching {
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(null)
        builder.parse(InputSource(StringReader(text)))
    }.exceptionOrNull() ?: return emptyList()

    return if (error is SAXParseException) {
        val line = (error.lineNumber - 1).coerceAtLeast(0)
        val col = (error.columnNumber - 1).coerceAtLeast(0)
        listOf(Diagnostic(line, col, col, DiagnosticSeverity.ERROR, error.message ?: "Malformed XML"))
    } else {
        listOf(Diagnostic(0, 0, 0, DiagnosticSeverity.ERROR, error.message ?: "Malformed XML"))
    }
}

// ---- YAML ----

private fun yamlDiagnostics(text: String): List<Diagnostic> {
    val out = ArrayList<Diagnostic>()
    text.split("\n").forEachIndexed { i, line ->
        // YAML forbids tabs for indentation.
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        val tab = leading.indexOf('\t')
        if (tab >= 0) {
            out.add(Diagnostic(i, tab, tab + 1, DiagnosticSeverity.ERROR, "YAML does not allow tabs for indentation"))
        }
    }
    return out
}

// ---- Bracket balance ----

private val BRACE_LANGUAGES = setOf(
    Language.KOTLIN, Language.JAVA, Language.JAVASCRIPT, Language.TYPESCRIPT,
    Language.C, Language.CPP, Language.GO, Language.CSS,
)

private val OPEN_TO_CLOSE = mapOf('(' to ')', '[' to ']', '{' to '}')

private class OpenBracket(val ch: Char, val line: Int, val col: Int)

/**
 * String/comment-aware bracket balance for brace languages. Skips `//` line comments (except CSS),
 * `/* */` blocks, and `"`, `'`, `` ` `` and `"""` string forms so brackets inside them don't count.
 */
private fun bracketDiagnostics(text: String, language: Language): List<Diagnostic> {
    val allowLineComment = language != Language.CSS
    val out = ArrayList<Diagnostic>()
    val stack = ArrayDeque<OpenBracket>()

    var line = 0
    var lineStart = 0
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        if (c == '\n') {
            line++
            lineStart = i + 1
            i++
            continue
        }
        val col = i - lineStart

        // Comments.
        if (allowLineComment && c == '/' && i + 1 < n && text[i + 1] == '/') {
            val nl = text.indexOf('\n', i)
            i = if (nl < 0) n else nl
            continue
        }
        if (c == '/' && i + 1 < n && text[i + 1] == '*') {
            val close = text.indexOf("*/", i + 2)
            if (close < 0) { i = n } else {
                // advance line/lineStart across the block comment
                var k = i
                while (k < close + 2) { if (text[k] == '\n') { line++; lineStart = k + 1 }; k++ }
                i = close + 2
            }
            continue
        }

        // Strings.
        when (c) {
            '"' -> {
                if (i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                    i = skipTriple(text, i + 3) { if (text[it] == '\n') { line++; lineStart = it + 1 } }
                } else {
                    i = skipString(text, i + 1, '"') { if (text[it] == '\n') { line++; lineStart = it + 1 } }
                }
                continue
            }
            '\'' -> { i = skipString(text, i + 1, '\'') { if (text[it] == '\n') { line++; lineStart = it + 1 } }; continue }
            '`' -> { i = skipString(text, i + 1, '`') { if (text[it] == '\n') { line++; lineStart = it + 1 } }; continue }
        }

        // Brackets.
        if (c in OPEN_TO_CLOSE) {
            stack.addLast(OpenBracket(c, line, col))
        } else if (c == ')' || c == ']' || c == '}') {
            val top = stack.lastOrNull()
            if (top == null || OPEN_TO_CLOSE[top.ch] != c) {
                out.add(Diagnostic(line, col, col + 1, DiagnosticSeverity.ERROR, "Unmatched '$c'"))
            } else {
                stack.removeLast()
            }
        }
        i++
    }
    for (open in stack.take(5)) {
        out.add(Diagnostic(open.line, open.col, open.col + 1, DiagnosticSeverity.WARNING, "Unclosed '${open.ch}'"))
    }
    return out
}

/** Advances past a `"..."`/`'...'`/`` `...` `` string starting at [from]; returns the index after it. */
private inline fun skipString(text: String, from: Int, quote: Char, onNewline: (Int) -> Unit): Int {
    var i = from
    val n = text.length
    while (i < n) {
        val c = text[i]
        if (c == '\\') { i += 2; continue }
        if (c == '\n') onNewline(i)
        if (c == quote) return i + 1
        i++
    }
    return n
}

/** Advances past a `"""..."""` triple-quoted string starting at [from]; returns the index after it. */
private inline fun skipTriple(text: String, from: Int, onNewline: (Int) -> Unit): Int {
    var i = from
    val n = text.length
    while (i < n) {
        if (text[i] == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') return i + 3
        if (text[i] == '\n') onNewline(i)
        i++
    }
    return n
}

// ---- TODO / FIXME ----

private val TODO_REGEX = Regex("\\b(TODO|FIXME)\\b")

private fun todoDiagnostics(text: String): List<Diagnostic> {
    val out = ArrayList<Diagnostic>()
    text.split("\n").forEachIndexed { i, line ->
        val match = TODO_REGEX.find(line) ?: return@forEachIndexed
        out.add(
            Diagnostic(
                line = i,
                startCol = match.range.first,
                endCol = match.range.last + 1,
                severity = DiagnosticSeverity.INFO,
                message = match.value,
            ),
        )
    }
    return out
}

// ---- Shared ----

/** Maps a character [offset] in [text] to a 0-based (line, column) pair. */
private fun offsetToLineCol(text: String, offset: Int): Pair<Int, Int> {
    val safe = offset.coerceIn(0, text.length)
    var line = 0
    var lineStart = 0
    for (i in 0 until safe) {
        if (text[i] == '\n') { line++; lineStart = i + 1 }
    }
    return line to (safe - lineStart)
}
