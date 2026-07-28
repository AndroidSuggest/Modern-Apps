package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfNumberToken

/**
 * Converts Excel number-format codes (builtin ids 0-49 and custom format strings) to the ODF
 * [OdfNumberFormat] model (Phases C2/X2). Best-effort: covers decimals, thousands grouping,
 * percent, currency, scientific, fraction, and date/time token lists.
 */
internal object ExcelNumFmt {

    /** Builtin numFmtId -> format code (the standard subset; ids without an entry are "General"). */
    private val BUILTINS: Map<Int, String> = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "mm-dd-yy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss",
        22 to "m/d/yy h:mm", 37 to "#,##0;(#,##0)", 38 to "#,##0;[Red](#,##0)",
        39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mmss.0", 48 to "##0.0E+0", 49 to "@"
    )

    fun forBuiltin(id: Int): OdfNumberFormat? {
        // 27-36 and 50-58 are locale/CJK date-time builtins; use a sensible default date pattern
        // (the literal "date" is not a valid format code and yields garbage tokens).
        val code = BUILTINS[id] ?: if (id in 27..36 || id in 50..58) return parse("yyyy-mm-dd") else return null
        if (code == "General") return null
        return parse(code)
    }

    /** True if a builtin id is a date/time format (used to tag value-type without a full parse). */
    fun isDateTimeBuiltin(id: Int): Boolean =
        id in 14..22 || id in 45..47 || id in 27..36 || id in 50..58

    /**
     * Parses a custom format code (uses only the first ';' section for positive numbers). Returns
     * null for "General" / empty.
     */
    fun parse(codeRaw: String): OdfNumberFormat? {
        val full = codeRaw.trim()
        if (full.isEmpty() || full.equals("General", true)) return null
        val section = full.split(';').first().trim()
        // Strip color / condition brackets like [Red], [$-409], [>100] but keep [$...] currency payloads.
        val currency = extractCurrency(section)
        val cleaned = stripBrackets(section)
        // Date detection must ignore quoted literals: 0" days" is a number, not a date.
        val dateProbe = stripLiterals(section)

        val isText = cleaned.contains("@")
        val isScientific = cleaned.contains("E+", true) || cleaned.contains("E-", true)
        val hasDate = !isScientific && containsDateToken(dateProbe)
        val isFraction = cleaned.contains("/") && Regex("[?#0]\\s*/\\s*[?#0]").containsMatchIn(cleaned)

        // Keep quotes for the token parser so literal segments aren't parsed as date letters.
        if (hasDate) return parseDateTime(stripBracketsKeepQuotes(section))

        if (isText) return null

        val percent = cleaned.contains("%")
        val grouping = cleaned.contains(",") && Regex("#,##0|0,0").containsMatchIn(cleaned)
        val decimals = cleaned.substringAfter('.', "").takeWhile { it == '0' || it == '#' }.count { it == '0' || it == '#' }
        val fracDigits = if (isFraction) cleaned.substringAfterLast('/').takeWhile { it == '?' || it == '#' || it == '0' }.length.coerceAtLeast(1) else 1

        return OdfNumberFormat(
            decimals = if (cleaned.contains('.') || isScientific) decimals else if (percent) 0 else 0,
            percent = percent,
            currencySymbol = currency,
            grouping = grouping,
            isScientific = isScientific,
            isFraction = isFraction,
            fractionDenominatorDigits = fracDigits
        )
    }

    private fun containsDateToken(code: String): Boolean {
        // A code is a date/time if it has y/d/s tokens, or 'm'/'h' outside of scientific notation.
        val c = code.replace(Regex("\\[[^]]*]"), "")
        return Regex("[yYdDsS]").containsMatchIn(c) || Regex("[hH]").containsMatchIn(c) ||
            (Regex("[mM]").containsMatchIn(c) && !c.contains("E", true))
    }

    private fun parseDateTime(codeIn: String): OdfNumberFormat {
        val code = codeIn
        val tokens = mutableListOf<OdfNumberToken>()
        var i = 0
        var seenHour = false
        var seenTime = false
        val ampm = code.contains("AM/PM", true) || code.contains("A/P", true)
        while (i < code.length) {
            val c = code[i]
            when (c.lowercaseChar()) {
                'y' -> { val run = runLen(code, i, 'y'); tokens.add(OdfNumberToken("year", style = if (run >= 4) "long" else "short")); i += run }
                'm' -> {
                    val run = runLen(code, i, 'm')
                    // 'm' after an hour token (or before seconds) is minutes; else month.
                    val isMinute = seenHour || nextNonSpaceIsSeconds(code, i + run)
                    if (isMinute) { tokens.add(OdfNumberToken("minutes", style = if (run >= 2) "long" else "short")); seenTime = true }
                    else tokens.add(OdfNumberToken("month", style = if (run >= 4) "long" else "short", textual = run >= 3))
                    i += run
                }
                'd' -> { val run = runLen(code, i, 'd'); tokens.add(OdfNumberToken(if (run >= 3) "day-of-week" else "day", style = if (run == 4 || run == 2) "long" else "short", textual = run >= 3)); i += run }
                'h' -> { val run = runLen(code, i, 'h'); tokens.add(OdfNumberToken("hours", style = if (run >= 2) "long" else "short")); seenHour = true; seenTime = true; i += run }
                's' -> { val run = runLen(code, i, 's'); tokens.add(OdfNumberToken("seconds", style = if (run >= 2) "long" else "short")); seenTime = true; i += run }
                '"' -> {
                    // Quoted literal segment.
                    val end = code.indexOf('"', i + 1)
                    val lit = if (end >= 0) code.substring(i + 1, end) else code.substring(i + 1)
                    if (lit.isNotEmpty()) tokens.add(OdfNumberToken("text", text = lit))
                    i = if (end >= 0) end + 1 else code.length
                }
                '\\' -> {
                    // Backslash-escaped single literal char.
                    if (i + 1 < code.length) { tokens.add(OdfNumberToken("text", text = code[i + 1].toString())); i += 2 } else i++
                }
                else -> {
                    if (code.startsWith("AM/PM", i, true)) { tokens.add(OdfNumberToken("am-pm")); i += 5 }
                    else if (code.startsWith("A/P", i, true)) { tokens.add(OdfNumberToken("am-pm")); i += 3 }
                    else {
                        // literal text run until next token char / quote / escape
                        val start = i
                        while (i < code.length && code[i].lowercaseChar() !in "ymdhs" && code[i] != '"' && code[i] != '\\' && !code.startsWith("AM/PM", i, true)) i++
                        val lit = code.substring(start, i)
                        if (lit.isNotEmpty()) tokens.add(OdfNumberToken("text", text = lit))
                    }
                }
            }
        }
        if (ampm && tokens.none { it.kind == "am-pm" }) tokens.add(OdfNumberToken("am-pm"))
        return OdfNumberFormat(isDate = !seenTime || tokens.any { it.kind in setOf("year", "month", "day", "day-of-week") },
            isTime = seenTime && tokens.none { it.kind in setOf("year", "month", "day", "day-of-week") },
            dateTimeTokens = tokens)
    }

    private fun nextNonSpaceIsSeconds(code: String, from: Int): Boolean {
        var i = from
        while (i < code.length && (code[i] == ':' || code[i] == ' ')) i++
        return i < code.length && code[i].lowercaseChar() == 's'
    }

    private fun runLen(s: String, start: Int, ch: Char): Int {
        var i = start
        while (i < s.length && s[i].lowercaseChar() == ch.lowercaseChar()) i++
        return i - start
    }

    private fun extractCurrency(code: String): String? {
        Regex("\\[\\$([^\\]-]*)").find(code)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        for (sym in listOf("$", "€", "£", "¥", "₹")) if (code.contains(sym)) return sym
        return null
    }

    private fun stripBrackets(code: String): String =
        code.replace(Regex("\\[[^]]*]"), "").replace("\"", "").replace("\\", "")

    /** Removes bracket sections but keeps quoted literals intact (for the date token parser). */
    private fun stripBracketsKeepQuotes(code: String): String =
        code.replace(Regex("\\[[^]]*]"), "")

    /** Removes bracket sections, quoted literals, and backslash escapes (for date detection). */
    private fun stripLiterals(code: String): String =
        code.replace(Regex("\\[[^]]*]"), "").replace(Regex("\"[^\"]*\""), "").replace(Regex("\\\\."), "")
}
