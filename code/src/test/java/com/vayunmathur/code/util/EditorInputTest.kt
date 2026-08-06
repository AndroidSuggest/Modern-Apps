package com.vayunmathur.code.util

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the pure editor helpers: smart input (indent carry, block-open, auto-close,
 * type-over), go-to-line offset math and the project-search line matcher.
 */
class EditorInputTest {

    private fun apply(
        old: TextFieldValue,
        new: TextFieldValue,
        indentUnit: String = "  ",
        autoIndent: Boolean = true,
        autoCloseBrackets: Boolean = true,
    ) = applyEditorInput(old, new, indentUnit, autoIndent, autoCloseBrackets)

    @Test
    fun newlineCarriesLeadingWhitespace() {
        val old = TextFieldValue("    foo", TextRange(7))
        val new = TextFieldValue("    foo\n", TextRange(8))
        val result = apply(old, new)
        assertEquals("    foo\n    ", result.text)
        assertEquals(12, result.selection.start)
    }

    @Test
    fun newlineBetweenBracketsOpensIndentedBlock() {
        val old = TextFieldValue("{}", TextRange(1))
        val new = TextFieldValue("{\n}", TextRange(2))
        val result = apply(old, new)
        assertEquals("{\n  \n}", result.text)
        assertEquals(4, result.selection.start)
    }

    @Test
    fun newlineBetweenBracketsCarriesExistingIndent() {
        val old = TextFieldValue("    {}", TextRange(5))
        val new = TextFieldValue("    {\n}", TextRange(6))
        val result = apply(old, new)
        assertEquals("    {\n      \n    }", result.text)
        assertEquals(12, result.selection.start)
    }

    @Test
    fun autoCloseInsertsMatchingBracket() {
        val old = TextFieldValue("", TextRange(0))
        val new = TextFieldValue("(", TextRange(1))
        val result = apply(old, new)
        assertEquals("()", result.text)
        assertEquals(1, result.selection.start)
    }

    @Test
    fun autoCloseInsertsMatchingQuote() {
        val old = TextFieldValue("", TextRange(0))
        val new = TextFieldValue("\"", TextRange(1))
        val result = apply(old, new)
        assertEquals("\"\"", result.text)
        assertEquals(1, result.selection.start)
    }

    @Test
    fun typingCloserOverExistingCloserTypesOver() {
        val old = TextFieldValue("()", TextRange(1))
        val new = TextFieldValue("())", TextRange(2))
        val result = apply(old, new)
        assertEquals("()", result.text)
        assertEquals(2, result.selection.start)
    }

    @Test
    fun autoCloseDisabledLeavesInsertionUntouched() {
        val old = TextFieldValue("", TextRange(0))
        val new = TextFieldValue("(", TextRange(1))
        val result = apply(old, new, autoCloseBrackets = false)
        assertEquals("(", result.text)
        assertEquals(1, result.selection.start)
    }

    @Test
    fun autoIndentDisabledLeavesNewlineUntouched() {
        val old = TextFieldValue("    foo", TextRange(7))
        val new = TextFieldValue("    foo\n", TextRange(8))
        val result = apply(old, new, autoIndent = false)
        assertEquals("    foo\n", result.text)
        assertEquals(8, result.selection.start)
    }

    @Test
    fun multiCharacterChangeIsReturnedUnchanged() {
        val old = TextFieldValue("", TextRange(0))
        val new = TextFieldValue("abc", TextRange(3))
        val result = apply(old, new)
        assertEquals("abc", result.text)
        assertEquals(3, result.selection.start)
    }

    @Test
    fun plainCharacterIsReturnedUnchanged() {
        val old = TextFieldValue("a", TextRange(1))
        val new = TextFieldValue("ab", TextRange(2))
        val result = apply(old, new)
        assertEquals("ab", result.text)
        assertEquals(2, result.selection.start)
    }

    @Test
    fun lineStartOffsetFindsLineStarts() {
        val text = "a\nbb\nccc"
        assertEquals(0, lineStartOffset(text, 1))
        assertEquals(2, lineStartOffset(text, 2))
        assertEquals(5, lineStartOffset(text, 3))
    }

    @Test
    fun lineStartOffsetClampsOutOfRange() {
        val text = "a\nbb\nccc"
        assertEquals(text.length, lineStartOffset(text, 99))
        assertEquals(0, lineStartOffset(text, 0))
        assertEquals(0, lineStartOffset(text, -5))
    }

    @Test
    fun findLineMatchesLiteralIsCaseInsensitiveByDefault() {
        val text = "Foo\nbar\nfoobar"
        val matches = findLineMatches(text, "foo", caseSensitive = false, useRegex = false)
        assertEquals(listOf(1, 3), matches.map { it.line })
        assertEquals(listOf("Foo", "foobar"), matches.map { it.preview })
    }

    @Test
    fun findLineMatchesLiteralRespectsCaseSensitivity() {
        val text = "Foo\nfoo"
        val matches = findLineMatches(text, "foo", caseSensitive = true, useRegex = false)
        assertEquals(listOf(2), matches.map { it.line })
    }

    @Test
    fun findLineMatchesRegex() {
        val text = "foo\nbar\nfoobar"
        val matches = findLineMatches(text, "^foo", caseSensitive = true, useRegex = true)
        assertEquals(listOf(1, 3), matches.map { it.line })
    }

    @Test
    fun findLineMatchesRespectsLimit() {
        val text = "foo\nfoo\nfoo"
        val matches = findLineMatches(text, "foo", caseSensitive = false, useRegex = false, limit = 1)
        assertEquals(1, matches.size)
    }

    @Test
    fun findLineMatchesInvalidRegexYieldsNothing() {
        val text = "foo(bar"
        val matches = findLineMatches(text, "(", caseSensitive = false, useRegex = true)
        assertEquals(emptyList(), matches.map { it.line })
    }
}
