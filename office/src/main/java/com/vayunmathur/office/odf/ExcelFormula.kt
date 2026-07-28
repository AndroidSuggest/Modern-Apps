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
                while (j < excel.length) {
                    if (excel[j] == '"') { if (j + 1 < excel.length && excel[j + 1] == '"') { j += 2; continue }; break }
                    j++
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
        val colAbs = m.groupValues[1] == "$"
        val rowAbs = m.groupValues[3] == "$"
        val col = if (colAbs) m.groupValues[2] else indexToCol((colToIndex(m.groupValues[2]) + dCol).coerceAtLeast(0))
        val row = if (rowAbs) m.groupValues[4].toInt() else (m.groupValues[4].toInt() + dRow).coerceAtLeast(1)
        "${m.groupValues[1]}$col${m.groupValues[3]}$row"
    }

    private fun colToIndex(letters: String): Int {
        var n = 0
        for (c in letters) n = n * 26 + (c.uppercaseChar() - 'A' + 1)
        return n - 1
    }

    private fun indexToCol(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) { val rem = (n - 1) % 26; sb.insert(0, ('A' + rem)); n = (n - 1) / 26 }
        return sb.toString()
    }

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
                while (j < body.length) {
                    if (body[j] == '"') {
                        if (j + 1 < body.length && body[j + 1] == '"') { j += 2; continue }
                        break
                    }
                    j++
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
