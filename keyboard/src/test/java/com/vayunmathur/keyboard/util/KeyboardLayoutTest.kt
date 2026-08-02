package com.vayunmathur.keyboard.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the layout catalogue, which is the sort of data that breaks silently: a shifted row
 * one character out of step mislabels every key after it, and nothing in the UI would notice.
 */
class KeyboardLayoutTest {

    @Test
    fun `ids are unique`() {
        val ids = KeyboardLayouts.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate layout id")
    }

    /** Three rows, or four for the layouts that take over the digit row (注音, JIS kana). */
    @Test
    fun `every layout has three or four non-empty rows`() {
        for (layout in KeyboardLayouts.ALL) {
            assertTrue(layout.rows.size in 3..4, "${layout.id} has ${layout.rows.size} rows")
            assertTrue(layout.rows.all { it.isNotEmpty() }, "${layout.id} has an empty row")
        }
    }

    /** A shift layer is positional, so a row that is a character short shifts every key after it. */
    @Test
    fun `shifted rows line up with base rows`() {
        for (layout in KeyboardLayouts.ALL) {
            val shifted = layout.shiftedRows ?: continue
            assertEquals(layout.rows.size, shifted.size, "${layout.id} shifted row count")
            layout.rows.forEachIndexed { i, row ->
                assertEquals(row.length, shifted[i].length, "${layout.id} shifted row $i length")
            }
        }
    }

    @Test
    fun `no layout repeats a character`() {
        for (layout in KeyboardLayouts.ALL) {
            val chars = layout.rows.joinToString("").toList()
            assertEquals(chars.size, chars.toSet().size, "${layout.id} repeats a key")
        }
    }

    /** Long-pressing a key that isn't on the layout can never fire. */
    @Test
    fun `alternates are keyed by characters the layout actually has`() {
        for (layout in KeyboardLayouts.ALL) {
            val chars = layout.rows.joinToString("").toSet()
            for (base in layout.alternates.keys) {
                assertTrue(base in chars, "${layout.id} has an alternate for the absent key '$base'")
            }
        }
    }

    @Test
    fun `only English layouts claim the English dictionary`() {
        for (layout in KeyboardLayouts.ALL.filter { it.englishDictionary }) {
            assertTrue(layout.id.startsWith("en_"), "${layout.id} claims the English dictionary")
        }
    }

    @Test
    fun `charAt applies case for Latin and the shift layer for Devanagari`() {
        val english = assertNotNull(KeyboardLayouts.byId("en_qwerty"))
        assertEquals("q", english.charAt(0, 0, shifted = false))
        assertEquals("Q", english.charAt(0, 0, shifted = true))
        assertTrue(english.cased)

        val hindi = assertNotNull(KeyboardLayouts.byId("hi_inscript"))
        assertEquals("ब", hindi.charAt(0, 5, shifted = false))
        assertEquals("भ", hindi.charAt(0, 5, shifted = true))
        // Devanagari has no case, so auto-capitalize must not flip it to the shift layer.
        assertFalse(hindi.cased)
        assertTrue(hindi.hasShift)
    }

    /** Arabic, Hebrew and Persian have neither case nor a second layer; the shift key is dropped. */
    @Test
    fun `caseless scripts report no shift`() {
        for (id in listOf("ar", "he", "fa")) {
            val layout = assertNotNull(KeyboardLayouts.byId(id))
            assertFalse(layout.hasShift, "$id should have no shift key")
            assertFalse(layout.cased)
        }
    }

    /**
     * Composed scripts must not auto-capitalize: their shift key selects a second character
     * layer (katakana, tense consonants), so "capitalizing" would change what is typed.
     */
    @Test
    fun `composed layouts do not auto-capitalize`() {
        for (layout in KeyboardLayouts.ALL.filter { it.composer != null }) {
            assertFalse(layout.cased, "${layout.id} would auto-capitalize")
        }
    }

    @Test
    fun `the composed scripts are all present`() {
        val byComposer = KeyboardLayouts.ALL.mapNotNull { it.composer }.toSet()
        assertEquals(ComposerKind.entries.toSet(), byComposer, "an engine has no layout")
    }

    /** Only the Chinese engines put anything in the strip; the others compose silently. */
    @Test
    fun `candidate layouts are the Chinese ones`() {
        val offering = KeyboardLayouts.ALL.filter { it.offersCandidates }.map { it.id }
        assertEquals(listOf("zh_pinyin", "zh_pinyin_tc", "zh_bopomofo"), offering)
    }

    @Test
    fun `settings resolve layouts and fall back when ids go missing`() {
        val settings = KeyboardSettings(layoutIds = listOf("ru", "nonsense"), activeLayoutId = "nonsense")
        assertEquals(listOf("ru"), settings.layouts.map { it.id })
        assertEquals("ru", settings.activeLayout.id)

        val empty = KeyboardSettings(layoutIds = emptyList(), activeLayoutId = "gone")
        assertEquals(KeyboardLayouts.DEFAULT.id, empty.activeLayout.id)
    }

    @Test
    fun `layout ids round-trip through settings storage`() {
        val ids = listOf("en_qwerty", "ru", "el")
        assertEquals(ids, KeyboardSettings.decodeLayouts(KeyboardSettings.encodeLayouts(ids)))
        assertEquals(listOf(KeyboardLayouts.DEFAULT.id), KeyboardSettings.decodeLayouts(null))
        assertEquals(listOf(KeyboardLayouts.DEFAULT.id), KeyboardSettings.decodeLayouts(""))
    }
}
