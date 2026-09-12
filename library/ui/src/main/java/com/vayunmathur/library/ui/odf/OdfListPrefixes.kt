package com.vayunmathur.library.ui.odf

/** Default bullet glyph per nesting level: • → ◦ → ▪ (cycling). */
private fun bulletForLevel(level: Int): String = when ((level - 1).coerceAtLeast(0) % 3) {
    0 -> "\u2022"   // • filled disc
    1 -> "\u25E6"   // ◦ hollow circle
    else -> "\u25AA" // ▪ small square
}

/** Default number format + suffix per nesting level: 1. → a) → i) (cycling). */
private fun numberStyleForLevel(level: Int): Pair<String, String> = when ((level - 1).coerceAtLeast(0) % 3) {
    0 -> "1" to "."
    1 -> "a" to ")"
    else -> "i" to ")"
}

/** Visible list prefix (indent + bullet/number) for a paragraph. (A1/F42) */
fun listPrefixFor(para: OdfParagraph): String {
    // Headings: show automatic outline numbering if present, else no generic prefix. (bugfix + Round 3)
    if (para.style == ParagraphStyle.HEADING1 || para.style == ParagraphStyle.HEADING2 ||
        para.style == ParagraphStyle.HEADING3 || para.style == ParagraphStyle.HEADING4) {
        return para.outlineNumber?.let { "$it " } ?: ""
    }
    if (para.listLevel <= 0 && para.style != ParagraphStyle.LIST_ITEM) return ""
    val level = maxOf(para.listLevel, 1)
    val indent = "    ".repeat(level - 1)
    return when (para.listType) {
        ListType.NUMBERED -> {
            // Default markdown numbering varies the style by nesting level (1. → a) → i) → …).
            val defaultStyle = para.listNumberFormat == "1" && para.listNumberSuffix == "." && para.listNumberPrefix.isEmpty()
            if (defaultStyle) {
                val (fmt, suffix) = numberStyleForLevel(level)
                indent + formatListNumber(para.listItemIndex, fmt) + suffix + "  "
            } else {
                indent + para.listNumberPrefix + formatListNumber(para.listItemIndex, para.listNumberFormat) + para.listNumberSuffix + "  "
            }
        }
        ListType.CHECKBOX ->
            indent + (if (para.listChecked) "\u2611" else "\u2610") + "  "
        ListType.BULLET -> {
            // Default markdown bullets vary the glyph by nesting level (• → ◦ → ▪).
            val ch = if (para.listBulletChar == "\u2022") bulletForLevel(level) else sanitizeBulletChar(para.listBulletChar)
            indent + ch + "  "
        }
    }
}

/**
 * Maps a list bullet glyph to something the app font can actually render. ODF list styles often
 * specify bullets from a symbol font's Private Use Area (StarSymbol/OpenSymbol/Wingdings), which
 * show as a "notdef" tofu box; fall back to a standard bullet for those. (bugfix)
 */
internal fun sanitizeBulletChar(ch: String): String {
    if (ch.isBlank()) return "\u2022"
    val c = ch[0]
    return when {
        c.code in 0xE000..0xF8FF -> "\u2022"   // Private Use Area (symbol fonts)
        c == '\uFFFD' -> "\u2022"              // replacement char
        c.isISOControl() -> "\u2022"
        else -> ch
    }
}
