package com.vayunmathur.keyboard.util

/**
 * One selectable letter layout.
 *
 * Only the three letter rows vary between languages — the number row, the symbol pages, the
 * numeric/phone pads and every functional key are shared — so a layout is just character data
 * plus the handful of script-dependent rules the UI and the service have to know about.
 */
data class KeyboardLayout(
    /** Stable id persisted in settings. Never shown, never localized. */
    val id: String,
    /** Endonym: what speakers call the language, shown in the picker and on the space bar. */
    val name: String,
    /** English name and layout family ("Russian · ЙЦУКЕН"), shown under [name] in the picker. */
    val description: String,
    /** Three rows of characters, one key per character. Rows need not be the same length. */
    val rows: List<String>,
    /**
     * What each key produces while shift is held, for scripts where shift is a second layer
     * rather than upper case (Devanagari, Thai, Georgian, and Turkish's dotted/dotless i).
     * Positionally 1:1 with [rows]; null means "upper-case the base character".
     */
    val shiftedRows: List<String>? = null,
    /**
     * Long-press alternates keyed by the base (unshifted) character. Long-pressing commits
     * the *first* alternate, so each list is ordered with the character that language needs
     * most first (Polish `a` gives `ą`, not `à`).
     */
    val alternates: Map<Char, String> = emptyMap(),
    /**
     * True only when the bundled English word list describes this layout's language.
     * Suggestions and autocorrect stay off for every other layout rather than proposing
     * English words to someone writing Greek.
     */
    val englishDictionary: Boolean = false,
) {
    /** Key columns in the widest row; shorter rows are centred within this width. */
    val width: Int = rows.maxOf { it.length }

    /** False for scripts with neither case nor a shift layer (Arabic, Hebrew, Persian). */
    val hasShift: Boolean =
        shiftedRows != null || rows.any { row -> row.any { it.uppercaseChar() != it } }

    /** True when shift means "upper case", which is also what makes auto-capitalize meaningful. */
    val cased: Boolean = shiftedRows == null && hasShift

    /** What the key at [col] of [row] produces in the given shift state. */
    fun charAt(row: Int, col: Int, shifted: Boolean): String {
        val base = rows[row][col]
        if (!shifted) return base.toString()
        return (shiftedRows?.getOrNull(row)?.getOrNull(col) ?: base.uppercaseChar()).toString()
    }
}
