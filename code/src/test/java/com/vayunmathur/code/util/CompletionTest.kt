package com.vayunmathur.code.util

import com.vayunmathur.code.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for the pure completion core: prefix extraction, ranking and snippet expansion. */
class CompletionTest {

    @Test
    fun currentWordPrefixReadsIdentifierBeforeCaret() {
        assertEquals("foo", currentWordPrefix("val foo", 7))
        assertEquals("", currentWordPrefix("val foo ", 8))
        assertEquals("ba", currentWordPrefix("foo.ba", 6))
    }

    @Test
    fun currentWordPrefixHandlesBounds() {
        assertEquals("", currentWordPrefix("", 0))
        assertEquals("", currentWordPrefix("abc", 0))
    }

    @Test
    fun emptyPrefixYieldsNoCompletions() {
        assertEquals(emptyList(), computeCompletions("", Language.KOTLIN, listOf("value")))
    }

    @Test
    fun bufferWordsAreCompletedAndRankedByFrequency() {
        val buffers = listOf("counter counter total", "counter")
        val result = computeCompletions("co", Language.PLAINTEXT, buffers)
        val words = result.filter { it.kind == CompletionKind.WORD }.map { it.insertText }
        assertEquals("counter", words.first())
    }

    @Test
    fun exactPrefixWordIsExcluded() {
        val result = computeCompletions("counter", Language.PLAINTEXT, listOf("counter counters"))
        val words = result.filter { it.kind == CompletionKind.WORD }.map { it.insertText }
        assertEquals(listOf("counters"), words)
    }

    @Test
    fun keywordsAreOfferedForTheLanguage() {
        val result = computeCompletions("fu", Language.KOTLIN, emptyList())
        assertTrue(result.any { it.kind == CompletionKind.KEYWORD && it.insertText == "fun" })
    }

    @Test
    fun snippetExpandsWithCaretInside() {
        val result = computeCompletions("fun", Language.KOTLIN, emptyList())
        val snippet = result.first { it.kind == CompletionKind.SNIPPET }
        assertEquals("fun () {\n}", snippet.insertText)
        // Caret should land right after "fun " (inside the parentheses).
        assertEquals("fun ".length, snippet.caretOffset)
    }

    @Test
    fun resultsAreCapped() {
        val many = (1..100).joinToString(" ") { "var$it" }
        val result = computeCompletions("var", Language.PLAINTEXT, listOf(many), limit = 10)
        assertTrue(result.size <= 10)
    }
}
