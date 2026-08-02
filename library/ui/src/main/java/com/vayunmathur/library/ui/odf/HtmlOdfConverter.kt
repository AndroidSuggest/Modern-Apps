package com.vayunmathur.library.ui.odf

import android.util.Base64

/**
 * Pure, Android-light converter between HTML and an [OdfDocument.TextDocument]. Import is tolerant
 * (best-effort tag handling, entity decoding), export produces clean semantic HTML. Covers
 * headings, paragraphs, bold/italic/underline/strike/color/size/font, links, bullet/numbered
 * lists (with nesting), tables, blockquotes, horizontal rules, and inline data-URI images.
 */
object HtmlOdfConverter {

    // region ODF -> HTML

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

    // endregion

    // region HTML -> ODF

    fun htmlToOdf(html: String, title: String = ""): OdfDocument.TextDocument {
        val blocks = ArrayList<OdfContentBlock>()
        val parser = HtmlParser(html, blocks)
        parser.run()
        if (blocks.isEmpty()) blocks.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("")))))
        return OdfDocument.TextDocument(title = title, content = blocks)
    }

    /** A tolerant streaming HTML reader that materializes paragraphs/tables/images into [out]. */
    private class HtmlParser(private val html: String, private val out: MutableList<OdfContentBlock>) {
        private var pos = 0
        private val styleStack = ArrayDeque<OdfSpan>().apply { addLast(OdfSpan("")) }
        private var curSpans = ArrayList<OdfSpan>()
        private var curStyle: ParagraphStyle = ParagraphStyle.BODY
        private var listType: ListType? = null
        private var listLevel = 0
        /** Running item number per open list level, so <ol> items count 1, 2, 3 … (bugfix) */
        private val listCounters = ArrayList<Int>()
        private var curListIndex = 0
        private var align: androidx.compose.ui.text.style.TextAlign? = null
        private var pendingHref: String? = null

        fun run() {
            while (pos < html.length) {
                val lt = html.indexOf('<', pos)
                if (lt < 0) { appendText(html.substring(pos)); break }
                if (lt > pos) appendText(html.substring(pos, lt))
                val gt = html.indexOf('>', lt)
                if (gt < 0) break
                val tag = html.substring(lt + 1, gt).trim()
                pos = gt + 1               // advance first; handlers (skip/table) may move pos further
                handleTag(tag)
            }
            flushParagraph()
        }

        private fun appendText(raw: String) {
            val text = decodeEntities(raw).replace(Regex("\\s+"), " ")
            if (text.isEmpty()) return
            if (text.isBlank() && curSpans.isEmpty()) return
            curSpans.add(styleStack.last().copy(text = text, href = pendingHref))
        }

        private fun handleTag(tagRaw: String) {
            if (tagRaw.startsWith("!") || tagRaw.startsWith("?")) return
            val closing = tagRaw.startsWith("/")
            val body = tagRaw.removePrefix("/")
            val name = body.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
            val attrs = if (!closing) parseAttrs(body) else emptyMap()
            when (name) {
                "b", "strong" -> pushPop(closing) { it.copy(bold = true) }
                "i", "em" -> pushPop(closing) { it.copy(italic = true) }
                "u" -> pushPop(closing) { it.copy(underline = true) }
                "s", "strike", "del" -> pushPop(closing) { it.copy(strikethrough = true) }
                "sup" -> pushPop(closing) { it.copy(superscript = true) }
                "sub" -> pushPop(closing) { it.copy(subscript = true) }
                "span", "font" -> if (closing) popStyle() else styleStack.addLast(applyInlineStyle(styleStack.last(), attrs))
                "a" -> if (closing) { pendingHref = null; popStyle() } else { pendingHref = attrs["href"]; styleStack.addLast(styleStack.last().copy(underline = true, color = 0xFF0066CC)) }
                "br" -> if (curSpans.isNotEmpty()) curSpans.add(styleStack.last().copy(text = "\n"))
                "p", "div" -> if (closing) flushParagraph() else { flushParagraph(); align = textAlign(attrs["style"]) }
                "h1", "h2", "h3", "h4", "h5", "h6" -> if (closing) flushParagraph() else { flushParagraph(); curStyle = heading(name) }
                "blockquote" -> if (closing) flushParagraph() else flushParagraph()
                "hr" -> { flushParagraph(); out.add(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("")), borderColor = 0xFF888888))) }
                "ul" -> if (closing) closeList() else { listLevel++; listType = ListType.BULLET; listCounters.add(0) }
                "ol" -> if (closing) closeList() else {
                    listLevel++; listType = ListType.NUMBERED
                    listCounters.add((attrs["start"]?.toIntOrNull() ?: 1) - 1)
                }
                "li" -> if (closing) flushListItem() else {
                    flushParagraph()
                    curStyle = ParagraphStyle.LIST_ITEM
                    curListIndex = attrs["value"]?.toIntOrNull() ?: ((listCounters.lastOrNull() ?: 0) + 1)
                    if (listCounters.isNotEmpty()) listCounters[listCounters.size - 1] = curListIndex
                }
                "img" -> emitImage(attrs)
                "table" -> parseTable()
                "style", "script", "head" -> skipUntilClose(name)
                else -> {}
            }
        }

        private inline fun pushPop(closing: Boolean, crossinline f: (OdfSpan) -> OdfSpan) {
            if (closing) popStyle() else styleStack.addLast(f(styleStack.last()))
        }
        private fun popStyle() { if (styleStack.size > 1) styleStack.removeLast() }

        private fun closeList() {
            listLevel--
            if (listCounters.isNotEmpty()) listCounters.removeAt(listCounters.size - 1)
            if (listLevel <= 0) {
                listType = null
                // Text after the list is body text, not one more (unnumbered) item. (bugfix)
                if (curSpans.isEmpty() && curStyle == ParagraphStyle.LIST_ITEM) curStyle = ParagraphStyle.BODY
                curListIndex = 0
            }
        }

        private fun flushParagraph() {
            if (curSpans.isEmpty()) { align = null; if (curStyle != ParagraphStyle.LIST_ITEM) curStyle = ParagraphStyle.BODY; return }
            out.add(OdfContentBlock.Paragraph(OdfParagraph(
                spans = coalesce(curSpans),
                style = curStyle,
                alignment = align,
                listType = listType ?: ListType.BULLET,
                listLevel = if (curStyle == ParagraphStyle.LIST_ITEM) listLevel.coerceAtLeast(1) else 0,
                listItemIndex = if (curStyle == ParagraphStyle.LIST_ITEM) curListIndex.coerceAtLeast(1) else 0
            )))
            curSpans = ArrayList()
            curStyle = if (listType != null) ParagraphStyle.LIST_ITEM else ParagraphStyle.BODY
            align = null
        }

        private fun flushListItem() {
            if (curSpans.isEmpty()) { curStyle = ParagraphStyle.LIST_ITEM; return }
            curStyle = ParagraphStyle.LIST_ITEM
            flushParagraph()
        }

        private fun emitImage(attrs: Map<String, String>) {
            val src = attrs["src"] ?: return
            if (!src.startsWith("data:")) return
            val comma = src.indexOf(','); if (comma < 0) return
            val bytes = try { Base64.decode(src.substring(comma + 1), Base64.DEFAULT) } catch (_: Exception) { return }
            val ext = when { src.contains("jpeg") -> "jpg"; src.contains("gif") -> "gif"; else -> "png" }
            flushParagraph()
            out.add(OdfContentBlock.Image(OdfImage("media/img${out.size}.$ext", bytes,
                width = attrs["width"]?.toFloatOrNull() ?: 0f, height = attrs["height"]?.toFloatOrNull() ?: 0f,
                altDesc = attrs["alt"])))
        }

        private fun parseTable() {
            // Find matching </table> and parse rows/cells with a nested reader.
            val end = indexOfClose("table")
            val inner = if (end > 0) html.substring(pos, end) else html.substring(pos)
            val rows = ArrayList<OdfTableRow>()
            var headerRows = 0
            val rowRe = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            val cellRe = Regex("<(td|th)\\b([^>]*)>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            for (rm in rowRe.findAll(inner)) {
                val cells = ArrayList<OdfTableCell>()
                var anyHeader = false
                for (cm in cellRe.findAll(rm.groupValues[1])) {
                    if (cm.groupValues[1].equals("th", true)) anyHeader = true
                    val cattrs = parseAttrs(cm.groupValues[1] + cm.groupValues[2])
                    val text = decodeEntities(cm.groupValues[3].replace(Regex("<[^>]+>"), " ")).trim().replace(Regex("\\s+"), " ")
                    cells.add(OdfTableCell(
                        paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text)))),
                        colSpan = cattrs["colspan"]?.toIntOrNull() ?: 1,
                        rowSpan = cattrs["rowspan"]?.toIntOrNull() ?: 1
                    ))
                }
                if (cells.isNotEmpty()) { rows.add(OdfTableRow(cells)); if (anyHeader && rows.size == headerRows + 1) headerRows++ }
            }
            if (rows.isNotEmpty()) { flushParagraph(); out.add(OdfContentBlock.Table(OdfTable(rows = rows, headerRowCount = headerRows))) }
            if (end > 0) pos = html.indexOf('>', end) + 1
        }

        private fun indexOfClose(tag: String): Int {
            val m = Regex("</$tag\\s*>", RegexOption.IGNORE_CASE).find(html, pos)
            return m?.range?.first ?: -1
        }

        private fun skipUntilClose(tag: String) {
            val end = indexOfClose(tag)
            if (end > 0) pos = html.indexOf('>', end) + 1
        }

        private fun heading(name: String): ParagraphStyle = when (name) {
            "h1" -> ParagraphStyle.HEADING1; "h2" -> ParagraphStyle.HEADING2
            "h3" -> ParagraphStyle.HEADING3; else -> ParagraphStyle.HEADING4
        }

        private fun applyInlineStyle(base: OdfSpan, attrs: Map<String, String>): OdfSpan {
            var s = base
            val style = attrs["style"] ?: ""
            Regex("color\\s*:\\s*#?([0-9a-fA-F]{6})").find(style)?.let { s = s.copy(color = 0xFF000000L or it.groupValues[1].toLong(16)) }
            Regex("font-size\\s*:\\s*([0-9.]+)\\s*(pt|px)").find(style)?.let { m ->
                val v = m.groupValues[1].toFloat(); s = s.copy(fontSize = if (m.groupValues[2] == "px") v * 0.75f else v)
            }
            if (style.contains("font-weight") && (style.contains("bold") || Regex("font-weight\\s*:\\s*[6-9]00").containsMatchIn(style))) s = s.copy(bold = true)
            if (Regex("font-style\\s*:\\s*italic").containsMatchIn(style)) s = s.copy(italic = true)
            if (style.contains("line-through")) s = s.copy(strikethrough = true)
            if (Regex("text-decoration[^;]*underline").containsMatchIn(style)) s = s.copy(underline = true)
            attrs["color"]?.let { c -> parseNamedOrHex(c)?.let { s = s.copy(color = it) } }
            return s
        }

        private fun textAlign(style: String?): androidx.compose.ui.text.style.TextAlign? {
            style ?: return null
            return when (Regex("text-align\\s*:\\s*(\\w+)").find(style)?.groupValues?.get(1)) {
                "center" -> androidx.compose.ui.text.style.TextAlign.Center
                "right" -> androidx.compose.ui.text.style.TextAlign.End
                "justify" -> androidx.compose.ui.text.style.TextAlign.Justify
                "left" -> androidx.compose.ui.text.style.TextAlign.Start
                else -> null
            }
        }

        private fun parseNamedOrHex(v: String): Long? {
            val t = v.trim()
            if (t.startsWith("#") && t.length == 7) return runCatching { 0xFF000000L or t.substring(1).toLong(16) }.getOrNull()
            return when (t.lowercase()) { "red" -> 0xFFFF0000; "green" -> 0xFF008000; "blue" -> 0xFF0000FF; "black" -> 0xFF000000; else -> null }
        }
    }

    private fun parseAttrs(tagBody: String): Map<String, String> {
        val map = HashMap<String, String>()
        val re = Regex("([a-zA-Z_:-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        for (m in re.findAll(tagBody)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[3].ifEmpty { m.groupValues[4].ifEmpty { m.groupValues[5] } }
            map[key] = decodeEntities(value)
        }
        return map
    }

    private fun coalesce(spans: List<OdfSpan>): List<OdfSpan> {
        if (spans.isEmpty()) return listOf(OdfSpan(""))
        val out = ArrayList<OdfSpan>()
        var cur = spans[0]; val sb = StringBuilder(cur.text)
        for (k in 1 until spans.size) {
            val s = spans[k]
            if (s.copy(text = "") == cur.copy(text = "")) sb.append(s.text)
            else { out.add(cur.copy(text = sb.toString())); cur = s; sb.setLength(0); sb.append(s.text) }
        }
        out.add(cur.copy(text = sb.toString()))
        return out
    }

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
    }

    // endregion
}
