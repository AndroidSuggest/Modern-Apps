package com.vayunmathur.library.ui.odf

import android.util.Base64

/** ODF -> HTML export half of [HtmlOdfConverter]. */
internal object HtmlOdfExport {

    fun odfToHtml(doc: OdfDocument.TextDocument): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<title>${esc(doc.title)}</title>\n</head>\n<body>\n")
        val listStack = ArrayDeque<Pair<ListType, Int>>() // (type, level)
        fun closeListsTo(level: Int) {
            while (listStack.isNotEmpty() && listStack.last().second >= level) {
                sb.append(if (listStack.removeLast().first == ListType.NUMBERED) "</ol>\n" else "</ul>\n")
            }
        }
        var i = 0
        while (i < doc.content.size) {
            when (val block = doc.content[i]) {
                is OdfContentBlock.Paragraph -> {
                    val p = block.paragraph
                    if (p.style == ParagraphStyle.LIST_ITEM) {
                        val level = p.listLevel.coerceAtLeast(1)
                        // Close deeper/different lists, open to this level.
                        while (listStack.isNotEmpty() && (listStack.last().second > level ||
                                (listStack.last().second == level && listStack.last().first != p.listType))) {
                            sb.append(if (listStack.removeLast().first == ListType.NUMBERED) "</ol>\n" else "</ul>\n")
                        }
                        while (listStack.size < level) {
                            val tag = if (p.listType == ListType.NUMBERED) "ol" else "ul"
                            sb.append("<$tag>\n"); listStack.addLast(p.listType to (listStack.size + 1))
                        }
                        val checkbox = if (p.listType == ListType.CHECKBOX) (if (p.listChecked) "\u2611 " else "\u2610 ") else ""
                        sb.append("<li>$checkbox${spansHtml(p.spans)}</li>\n")
                    } else {
                        closeListsTo(1)
                        sb.append(paragraphHtml(p))
                    }
                }
                is OdfContentBlock.Table -> { closeListsTo(1); sb.append(tableHtml(block.table)) }
                is OdfContentBlock.Image -> { closeListsTo(1); sb.append(imageHtml(block.image)) }
                is OdfContentBlock.Formula -> { closeListsTo(1); sb.append("<p><code>${esc(block.mathml)}</code></p>\n") }
                is OdfContentBlock.TableOfContents -> { closeListsTo(1); for (e in block.entries) sb.append(paragraphHtml(e)) }
                is OdfContentBlock.PageBreak -> { closeListsTo(1); sb.append("<hr style=\"page-break-after:always\">\n") }
                else -> {}
            }
            i++
        }
        closeListsTo(1)
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    private fun paragraphHtml(p: OdfParagraph): String {
        if (p.borderColor != null && p.spans.all { it.text.isEmpty() }) return "<hr>\n"
        val align = when (p.alignment) {
            androidx.compose.ui.text.style.TextAlign.Center -> "center"
            androidx.compose.ui.text.style.TextAlign.End, androidx.compose.ui.text.style.TextAlign.Right -> "right"
            androidx.compose.ui.text.style.TextAlign.Justify -> "justify"
            else -> null
        }
        val styleAttr = buildString {
            if (align != null) append("text-align:$align;")
            p.backgroundColor?.let { append("background-color:${color(it)};") }
        }.let { if (it.isNotEmpty()) " style=\"$it\"" else "" }
        val tag = when (p.style) {
            ParagraphStyle.HEADING1 -> "h1"; ParagraphStyle.HEADING2 -> "h2"
            ParagraphStyle.HEADING3 -> "h3"; ParagraphStyle.HEADING4 -> "h4"; else -> null
        }
        val inner = spansHtml(p.spans)
        return when {
            tag != null -> "<$tag$styleAttr>$inner</$tag>\n"
            p.backgroundColor != null && p.marginLeft > 0f -> "<blockquote$styleAttr>$inner</blockquote>\n"
            else -> "<p$styleAttr>$inner</p>\n"
        }
    }

    private fun spansHtml(spans: List<OdfSpan>): String = spans.joinToString("") { spanHtml(it) }

    private fun spanHtml(s: OdfSpan): String {
        if (s.text.isEmpty()) return ""
        var inner = esc(s.text).replace("\n", "<br>")
        val css = buildString {
            s.color?.let { append("color:${color(it)};") }
            s.backgroundColor?.let { append("background-color:${color(it)};") }
            s.fontSize?.let { append("font-size:${it}pt;") }
            s.fontFamily?.let { append("font-family:'${it}';") }
        }
        if (css.isNotEmpty()) inner = "<span style=\"$css\">$inner</span>"
        if (s.subscript) inner = "<sub>$inner</sub>"
        if (s.superscript) inner = "<sup>$inner</sup>"
        if (s.strikethrough) inner = "<s>$inner</s>"
        if (s.underline) inner = "<u>$inner</u>"
        if (s.italic) inner = "<em>$inner</em>"
        if (s.bold) inner = "<strong>$inner</strong>"
        if (s.href != null) inner = "<a href=\"${esc(s.href)}\">$inner</a>"
        return inner
    }

    private fun tableHtml(table: OdfTable): String {
        val sb = StringBuilder("<table border=\"1\" style=\"border-collapse:collapse\">\n")
        table.rows.forEachIndexed { ri, row ->
            sb.append("<tr>")
            for (cell in row.cells) {
                if (cell.isCovered) continue
                val tag = if (ri < table.headerRowCount) "th" else "td"
                val attrs = buildString {
                    if (cell.colSpan > 1) append(" colspan=\"${cell.colSpan}\"")
                    if (cell.rowSpan > 1) append(" rowspan=\"${cell.rowSpan}\"")
                    cell.backgroundColor?.let { append(" style=\"background-color:${color(it)}\"") }
                }
                val content = cell.paragraphs.joinToString("<br>") { spansHtml(it.spans) }
                sb.append("<$tag$attrs>$content</$tag>")
            }
            sb.append("</tr>\n")
        }
        sb.append("</table>\n")
        return sb.toString()
    }

    private fun imageHtml(image: OdfImage): String {
        if (image.imageData.isEmpty()) return ""
        val mime = when (image.path.substringAfterLast('.', "png").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; else -> "image/png"
        }
        val b64 = Base64.encodeToString(image.imageData, Base64.NO_WRAP)
        val dim = buildString {
            if (image.width > 0) append(" width=\"${image.width.toInt()}\"")
            if (image.height > 0) append(" height=\"${image.height.toInt()}\"")
        }
        return "<p><img src=\"data:$mime;base64,$b64\"$dim alt=\"${esc(image.altDesc ?: "")}\"></p>\n"
    }

    private fun color(c: Long): String = "#%06X".format(c and 0xFFFFFF)

    private fun esc(s: String): String = buildString {
        for (ch in s) when (ch) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}
