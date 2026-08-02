package com.vayunmathur.keyboard.util

/** Which page of keys is currently shown. */
enum class KeyboardPage { LETTERS, SYMBOLS, MORE_SYMBOLS, NUMERIC, PHONE, PHONE_SYMBOLS, EMOJI }

/** Shift/caps state for the letter page. */
enum class ShiftState { OFF, SHIFTED, CAPS_LOCK }

/**
 * Static key layouts. Each page is a list of character rows (one [String] per row, one
 * key per character). Functional keys (shift, backspace, space, enter, page toggles) are
 * added by the UI around these character rows so the data here stays simple.
 */
object Layouts {
    /**
     * QWERTY letters (always lowercase here; the UI upper-cases per shift state). The letter
     * rows the keyboard actually draws come from the user's chosen [KeyboardLayout] — this is
     * the English one, shared by the other layouts that are plain QWERTY.
     */
    val LETTER_ROWS = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
    )

    val SYMBOL_ROWS = listOf(
        "1234567890",
        "@#\$_&-+()/",
        "*\"':;!?",
    )

    val MORE_SYMBOL_ROWS = listOf(
        "~`|•√π÷×¶",
        "£¢€¥^°={}",
        "\\%©®™✓[]",
    )

    /** Compact numeric pad used for number/phone input types. */
    val NUMERIC_ROWS = listOf(
        "123",
        "456",
        "789",
        "*0#",
    )

    /**
     * Long-press alternates shared by the Latin layouts: vowels (and ç/ñ) expose common
     * accents. No digits here — there's a dedicated number row; an empty string = no
     * alternate. Individual layouts override the keys their language cares about, so the
     * Polish `a` gives `ą` rather than `à` (see [KeyboardLayouts]).
     */
    val LATIN_ALTERNATES: Map<Char, String> = mapOf(
        'q' to "", 'w' to "", 'e' to "éèêë", 'r' to "", 't' to "",
        'y' to "ÿ", 'u' to "úùûü", 'i' to "íìîï", 'o' to "óòôöø", 'p' to "",
        'a' to "àáâäã", 's' to "ß", 'd' to "", 'f' to "", 'g' to "",
        'h' to "", 'j' to "", 'k' to "", 'l' to "",
        'z' to "", 'x' to "", 'c' to "ç", 'v' to "", 'b' to "", 'n' to "ñ", 'm' to "",
    )

    /** Rows for the current character page, or null for pages the UI renders specially. */
    fun rowsFor(page: KeyboardPage): List<String>? = when (page) {
        KeyboardPage.LETTERS -> LETTER_ROWS
        KeyboardPage.SYMBOLS -> SYMBOL_ROWS
        KeyboardPage.MORE_SYMBOLS -> MORE_SYMBOL_ROWS
        KeyboardPage.NUMERIC -> NUMERIC_ROWS
        // Phone/emoji pages are laid out directly by the UI (special keys, hints, grid).
        KeyboardPage.PHONE, KeyboardPage.PHONE_SYMBOLS, KeyboardPage.EMOJI -> null
    }
}
