package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals

class RemapCaretTest {
    @Test fun insert_after_caret_leaves_it() {
        assertEquals(1, remapCaret("abcd", "abXcd", 1))
    }

    @Test fun insert_before_caret_shifts_it_right() {
        assertEquals(4, remapCaret("abcd", "abXcd", 3))
    }

    @Test fun delete_before_caret_shifts_it_left() {
        assertEquals(3, remapCaret("abXcd", "abcd", 4))
    }

    @Test fun caret_at_end_follows_appended_text() {
        assertEquals(17, remapCaret("hello world", "hello brave world", 11))
    }

    @Test fun unchanged_text_keeps_caret() {
        assertEquals(5, remapCaret("hello world", "hello world", 5))
    }

    @Test fun edit_at_caret_lands_at_change_start() {
        // Replace the middle; caret was inside the replaced region.
        val r = remapCaret("aXXXb", "aYb", 3)
        assertEquals(1, r)
    }
}
