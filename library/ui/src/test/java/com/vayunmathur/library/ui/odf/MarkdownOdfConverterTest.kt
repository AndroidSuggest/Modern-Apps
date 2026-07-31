package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownOdfConverterTest {

    private fun roundTrip(md: String): String =
        MarkdownOdfConverter.odfToMarkdown(MarkdownOdfConverter.markdownToOdf(md))

    private fun assertRoundTrip(md: String) = assertEquals(md, roundTrip(md))

    @Test
    fun emptyInput() {
        assertEquals("", roundTrip(""))
    }

    @Test
    fun plainParagraphs() {
        assertRoundTrip("Hello world")
        assertRoundTrip("Line one\nLine two\nLine three")
    }

    @Test
    fun blankLinesPreserved() {
        assertRoundTrip("First\n\nSecond")
    }

    @Test
    fun headings() {
        assertRoundTrip("# Title")
        assertRoundTrip("## Sub")
        assertRoundTrip("### Three")
        assertRoundTrip("#### Four")
    }

    @Test
    fun headingsFiveAndSixNormalizeToFour() {
        assertEquals("#### Deep", roundTrip("##### Deep"))
        assertEquals("#### Deep", roundTrip("###### Deep"))
    }

    @Test
    fun bold() {
        assertRoundTrip("This is **bold** text")
    }

    @Test
    fun boldUnderscoreNormalizes() {
        assertEquals("This is **bold** text", roundTrip("This is __bold__ text"))
    }

    @Test
    fun italic() {
        assertRoundTrip("This is *italic* text")
    }

    @Test
    fun strikethrough() {
        assertRoundTrip("This is ~~gone~~ text")
    }

    @Test
    fun boldItalicCombined() {
        assertRoundTrip("A ***strong emphatic*** word")
    }

    @Test
    fun inlineCode() {
        assertRoundTrip("Use `val x = 1` here")
    }

    @Test
    fun link() {
        assertRoundTrip("See [Google](https://google.com) now")
    }

    @Test
    fun bulletList() {
        assertRoundTrip("- one\n- two\n- three")
    }

    @Test
    fun bulletMarkersNormalize() {
        assertEquals("- one\n- two", roundTrip("* one\n+ two"))
    }

    @Test
    fun nestedBulletList() {
        assertRoundTrip("- top\n  - child\n    - grandchild")
    }

    @Test
    fun numberedList() {
        assertRoundTrip("1. first\n2. second\n3. third")
    }

    @Test
    fun taskListUnchecked() {
        assertRoundTrip("- [ ] todo")
    }

    @Test
    fun taskListChecked() {
        assertRoundTrip("- [x] done")
    }

    @Test
    fun taskCheckedUppercaseNormalizes() {
        assertEquals("- [x] done", roundTrip("- [X] done"))
    }

    @Test
    fun horizontalRule() {
        assertRoundTrip("above\n\n---\n\nbelow")
    }

    @Test
    fun blockquote() {
        assertRoundTrip("> quoted line")
    }

    @Test
    fun fencedCodeBlock() {
        assertEquals("```\nfun main() {}\n```", roundTrip("```kotlin\nfun main() {}\n```"))
    }

    @Test
    fun fencedCodeBlockMultiLine() {
        assertEquals("```\nline1\nline2\n```", roundTrip("```\nline1\nline2\n```"))
    }

    @Test
    fun inlineMathPreservedVerbatim() {
        assertRoundTrip("Euler: \$e^{i\\pi} + 1 = 0\$ done")
    }

    @Test
    fun blockMathPreservedVerbatim() {
        assertRoundTrip("\$\$\\int_0^1 x^2 dx\$\$")
    }

    @Test
    fun latexInMathNotTreatedAsMarkdown() {
        // Asterisks inside math must not become italics.
        assertRoundTrip("\$a * b * c\$")
    }

    @Test
    fun mixedDocument() {
        val md = """
            # Notes

            Some **bold** and *italic* and `code`.

            - item one
            - [ ] task
            - [x] done

            > a quote

            1. first
            2. second

            ---

            ```
            code line
            ```
        """.trimIndent()
        assertRoundTrip(md)
    }

    @Test
    fun markdownToOdfProducesParagraphs() {
        val doc = MarkdownOdfConverter.markdownToOdf("# Hi\nbody")
        assertEquals(2, doc.content.size)
        val first = (doc.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(ParagraphStyle.HEADING1, first.style)
    }

    @Test
    fun boldSpanHasBoldFlag() {
        val doc = MarkdownOdfConverter.markdownToOdf("**x**")
        val para = (doc.content[0] as OdfContentBlock.Paragraph).paragraph
        assertTrue(para.spans.any { it.bold })
    }
}
