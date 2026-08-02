package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ordered lists have to count up: 1, 2, 3 … A converter that leaves every item's index unset makes
 * them all render as "1." (formatListNumber floors the index at 1), which is what these guard.
 */
class ListNumberingTest {

    private fun paragraphs(doc: OdfDocument.TextDocument) =
        doc.content.mapNotNull { (it as? OdfContentBlock.Paragraph)?.paragraph }

    private fun numbers(doc: OdfDocument.TextDocument) =
        paragraphs(doc).filter { it.style == ParagraphStyle.LIST_ITEM }.map { it.listItemIndex }

    private fun listItem(text: String, level: Int = 1, index: Int = 0) =
        OdfContentBlock.Paragraph(OdfParagraph(
            spans = listOf(OdfSpan(text)),
            style = ParagraphStyle.LIST_ITEM,
            listLevel = level,
            listType = ListType.NUMBERED,
            listItemIndex = index
        ))

    @Test
    fun htmlOrderedListItemsCountUp() {
        val doc = HtmlOdfConverter.htmlToOdf("<ol><li>one</li><li>two</li><li>three</li></ol>")
        assertEquals(listOf(1, 2, 3), numbers(doc))
    }

    @Test
    fun htmlOrderedListHonorsStartAndValue() {
        val doc = HtmlOdfConverter.htmlToOdf("""<ol start="5"><li>five</li><li value="9">nine</li><li>ten</li></ol>""")
        assertEquals(listOf(5, 9, 10), numbers(doc))
    }

    @Test
    fun htmlSeparateOrderedListsRestart() {
        val doc = HtmlOdfConverter.htmlToOdf("<ol><li>a</li><li>b</li></ol><p>gap</p><ol><li>c</li><li>d</li></ol>")
        assertEquals(listOf(1, 2, 1, 2), numbers(doc))
    }

    @Test
    fun htmlNestedOrderedListNumbersIndependently() {
        val doc = HtmlOdfConverter.htmlToOdf("<ol><li>a</li><li>b<ol><li>b1</li><li>b2</li></ol></li><li>c</li></ol>")
        // Outer 1, 2, then the nested pair, then the outer list resumes at 3.
        assertEquals(listOf(1, 2, 1, 2, 3), numbers(doc))
    }

    @Test
    fun htmlBulletsAreUnaffected() {
        val doc = HtmlOdfConverter.htmlToOdf("<ul><li>a</li><li>b</li></ul>")
        assertEquals(listOf(ListType.BULLET, ListType.BULLET), paragraphs(doc).map { it.listType })
    }

    @Test
    fun unnumberedItemsGetRunningNumbers() {
        val doc = OdfDocument.TextDocument("t", listOf(listItem("a"), listItem("b"), listItem("c")))
        assertEquals(listOf(1, 2, 3), numbers(numberUnnumberedListItems(doc)))
    }

    @Test
    fun existingNumbersAreKeptAndAdvanceTheCounter() {
        val doc = OdfDocument.TextDocument("t", listOf(listItem("a", index = 4), listItem("b"), listItem("c")))
        assertEquals(listOf(4, 5, 6), numbers(numberUnnumberedListItems(doc)))
    }

    @Test
    fun nestedLevelsCountSeparately() {
        val doc = OdfDocument.TextDocument("t", listOf(
            listItem("a"), listItem("a1", level = 2), listItem("a2", level = 2), listItem("b")
        ))
        assertEquals(listOf(1, 1, 2, 2), numbers(numberUnnumberedListItems(doc)))
    }

    @Test
    fun listsSeparatedByABodyParagraphRestart() {
        val body = OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("gap"))))
        val doc = OdfDocument.TextDocument("t", listOf(listItem("a"), listItem("b"), body, listItem("c")))
        assertEquals(listOf(1, 2, 1), numbers(numberUnnumberedListItems(doc)))
    }
}
