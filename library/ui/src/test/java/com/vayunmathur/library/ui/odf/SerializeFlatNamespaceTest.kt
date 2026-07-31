package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertTrue

class SerializeFlatNamespaceTest {
    @Test
    fun flat_declares_meta_namespace_before_use() {
        val doc = OdfDocument.TextDocument(
            title = "Hello",
            content = listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("hi")))))
        )
        val flat = OdfSerializer.serializeFlat(doc)
        assertTrue(flat.contains("<office:document"), "flat ODF must be a single office:document")
        // Any meta:-prefixed element must be preceded by an xmlns:meta declaration on the root.
        if (flat.contains("<meta:")) {
            assertTrue(
                flat.substringBefore("<meta:").contains("xmlns:meta="),
                "xmlns:meta must be declared before the first <meta: element",
            )
        }
    }
}
