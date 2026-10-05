package com.vayunmathur.office.odf

/**
 * Best-effort translator from an Excel A1-style formula to an ODF OpenFormula string
 * ("of:=…") (Phases C2/X4). Handles cell/range references (incl. sheet-qualified and absolute),
 * converts the ',' argument separator to ';', and preserves string literals. Function names are
 * passed through unchanged — most common names (SUM/IF/AVERAGE/…) are identical in both dialects.
 */
internal object ExcelFormula {

    private val REF = Regex(
        "(?<![A-Za-z0-9_.$])" +
            "(?:('[^']+'|[A-Za-z_][A-Za-z0-9_.]*)!)?" +
            "(\\$?[A-Za-z]{1,3}\\$?[0-9]+(?::\\$?[A-Za-z]{1,3}\\$?[0-9]+)?)" +
            "(?![A-Za-z0-9_(])"
    )

    // A relative/absolute A1 cell reference (col letters + row), used by [shift].
    private val CELL = Regex(
        "(?<![A-Za-z0-9_$])" +
            "(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]+)" +
            "(?![A-Za-z0-9_(])"
    )
    private const val REF_SHEET = 1
    private const val REF_COL = 2
    private const val REF_COL_ABS = 3
    private const val REF_ROW = 4
    private const val ALPHA_BASE = 26

    /**
     * Shifts the relative references in an Excel formula body by [dRow]/[dCol] (used to re-base a
     * shared formula from its master cell to a dependent cell). Absolute ($) parts and string
     * literals are left untouched.
     */
    fun shift(excel: String, dRow: Int, dCol: Int): String {
        if (dRow == 0 && dCol == 0) return excel
        val sb = StringBuilder()
        var i = 0
        while (i < excel.length) {
            val c = excel[i]
            if (c == '"') {
                var j = i + 1
                while (j < excel.length && !isStringEnd(excel, j)) {
                    j = if (isEscapedQuote(excel, j)) j + 2 else j + 1
                }
                val end = if (j < excel.length) j else excel.length - 1
                sb.append(excel, i, minOf(end + 1, excel.length)); i = end + 1
            } else {
                val nextQuote = excel.indexOf('"', i).let { if (it < 0) excel.length else it }
                sb.append(shiftSegment(excel.substring(i, nextQuote), dRow, dCol))
                i = nextQuote
            }
        }
        return sb.toString()
    }

    private fun shiftSegment(seg: String, dRow: Int, dCol: Int): String = CELL.replace(seg) { m ->
        val colAbs = m.groupValues[REF_SHEET] == "$"
        val rowAbs = m.groupValues[REF_COL_ABS] == "$"
        val col = if (colAbs) m.groupValues[REF_COL] else indexToCol(
            (colToIndex(m.groupValues[REF_COL]) + dCol).coerceAtLeast(0))
        val row = if (rowAbs) m.groupValues[REF_ROW].toInt() else {
            (m.groupValues[REF_ROW].toInt() + dRow).coerceAtLeast(1)
        }
        "${m.groupValues[REF_SHEET]}$col${m.groupValues[REF_COL_ABS]}$row"
    }

    private fun colToIndex(letters: String): Int {
        var n = 0
        for (c in letters) n = n * ALPHA_BASE + (c.uppercaseChar() - 'A' + 1)
        return n - 1
    }

    private fun indexToCol(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % ALPHA_BASE
            sb.insert(0, ('A' + rem))
            n = (n - 1) / ALPHA_BASE
        }
        return sb.toString()
    }

    /** True at an unescaped closing quote (not part of a "" escape). */
    private fun isStringEnd(s: String, j: Int): Boolean = s[j] == '"' && !isEscapedQuote(s, j)

    /** True at the first quote of a "" escaped pair. */
    private fun isEscapedQuote(s: String, j: Int): Boolean =
        s[j] == '"' && j + 1 < s.length && s[j + 1] == '"'

    /** Returns an "of:=…" formula for the given Excel formula body (with or without a leading '='). */
    fun toOdf(excel: String): String {
        val body = excel.trim().removePrefix("=")
        val sb = StringBuilder("of:=")
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '"') {
                // Scan to the closing quote, treating "" as an escaped quote inside the literal.
                var j = i + 1
                while (j < body.length && !isStringEnd(body, j)) {
                    j = if (isEscapedQuote(body, j)) j + 2 else j + 1
                }
                val end = if (j < body.length) j else body.length - 1
                sb.append(body, i, minOf(end + 1, body.length))
                i = end + 1
            } else {
                // find next string-literal start; convert the segment between
                val nextQuote = body.indexOf('"', i).let { if (it < 0) body.length else it }
                sb.append(convertSegment(body.substring(i, nextQuote)))
                i = nextQuote
            }
        }
        return sb.toString()
    }

    private fun convertSegment(seg: String): String {
        val refWrapped = REF.replace(seg) { m ->
            val sheet = m.groupValues[1]
            val ref = m.groupValues[2]
            wrap(sheet, ref)
        }
        return refWrapped.replace(',', ';')
    }

    private fun wrap(sheet: String, ref: String): String {
        return if (ref.contains(':')) {
            val (a, b) = ref.split(':', limit = 2)
            "[${prefixSheet(sheet)}$a:.$b]"
        } else {
            "[${prefixSheet(sheet)}$ref]"
        }
    }

    private fun prefixSheet(sheet: String): String {
        if (sheet.isEmpty()) return "."
        val name = sheet.trim('\'')
        // OpenFormula requires quoting sheet names with spaces/specials: [$'My Sheet'.A1].
        val needsQuote = sheet.startsWith("'") || !name.all { it.isLetterOrDigit() || it == '_' }
        return "\$" + (if (needsQuote) "'$name'" else name) + "."
    }
}
