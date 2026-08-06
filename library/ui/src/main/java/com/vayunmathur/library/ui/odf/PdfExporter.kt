package com.vayunmathur.library.ui.odf

import java.io.ByteArrayOutputStream

/**
 * Minimal, dependency-free PDF writer for [OdfDocument.TextDocument]. Produces a valid PDF 1.4 with
 * word-wrapped text across Letter-size pages, headings (larger/bold), bold/italic runs (via the
 * standard Helvetica family), and bullet/numbered lists. Tables render as tab-separated lines;
 * images are omitted. Intended as a convenience export, not a full layout engine.
 */
object PdfExporter {

    private const val PAGE_W = 612f
    private const val PAGE_H = 792f
    private const val MARGIN = 72f
    private const val BODY_SIZE = 11f

    private const val F_REG = "F1"; private const val F_BOLD = "F2"; private const val F_ITAL = "F3"; private const val F_BI = "F4"

    fun export(doc: OdfDocument.TextDocument): ByteArray {
        val pages = ArrayList<StringBuilder>()
        pages.add(StringBuilder())
        val newPage: () -> StringBuilder = { val sb = StringBuilder(); pages.add(sb); sb }
        var y = PAGE_H - MARGIN
        for (block in doc.content) {
            when (block) {
                is OdfContentBlock.Paragraph -> y = drawParagraph(pages.last(), block.paragraph, y, newPage)
                is OdfContentBlock.TableOfContents -> for (e in block.entries) y = drawParagraph(pages.last(), e, y, newPage)
                is OdfContentBlock.Table -> for (row in block.table.rows) {
                    val line = row.cells.filter { !it.isCovered }.joinToString("    ") { c -> c.paragraphs.joinToString(" ") { it.spans.joinToString("") { s -> s.text } } }
                    y = drawParagraph(pages.last(), OdfParagraph(listOf(OdfSpan(line))), y, newPage)
                }
                is OdfContentBlock.Formula -> y = drawParagraph(pages.last(), OdfParagraph(listOf(OdfSpan(block.mathml))), y, newPage)
                else -> {}
            }
        }
        return assemble(pages)
    }

    private fun drawParagraph(sbIn: StringBuilder, p: OdfParagraph, yIn: Float, newPage: () -> StringBuilder): Float {
        var sb = sbIn
        var y = yIn
        val (size, boldAll) = when (p.style) {
            ParagraphStyle.HEADING1 -> 20f to true; ParagraphStyle.HEADING2 -> 17f to true
            ParagraphStyle.HEADING3 -> 14f to true; ParagraphStyle.HEADING4 -> 12f to true
            else -> BODY_SIZE to false
        }
        val lineH = size * 1.35f
        if (boldAll) y -= size * 0.4f // extra space before headings
        val indent = if (p.style == ParagraphStyle.LIST_ITEM) 18f * p.listLevel.coerceAtLeast(1) else 0f
        val leftX = MARGIN + indent + p.marginLeft
        val maxX = PAGE_W - MARGIN

        // Marker for list items.
        val marker = if (p.style == ParagraphStyle.LIST_ITEM) when (p.listType) {
            ListType.NUMBERED -> "${p.listItemIndex.coerceAtLeast(1)}. "
            ListType.CHECKBOX -> if (p.listChecked) "[x] " else "[ ] "
            ListType.BULLET -> "\u2022 "
        } else ""

        // Tokenize into (word, bold, italic).
        data class Tok(val w: String, val b: Boolean, val i: Boolean)
        val toks = ArrayList<Tok>()
        if (marker.isNotEmpty()) toks.add(Tok(marker.trim(), boldAll, false))
        for (span in p.spans) {
            if (span.text.isEmpty()) continue
            for (w in span.text.split(Regex("\\s+"))) if (w.isNotEmpty()) toks.add(Tok(w, span.bold || boldAll, span.italic))
        }
        if (toks.isEmpty()) return y - lineH * 0.5f

        val alignCenter = p.alignment == androidx.compose.ui.text.style.TextAlign.Center
        var x = leftX
        var lineToks = ArrayList<Tok>()
        var lineWidth = 0f
        val spaceW = size * 0.28f

        fun emitLine() {
            if (lineToks.isEmpty()) return
            if (y - lineH < MARGIN) { sb = newPage(); y = PAGE_H - MARGIN }
            var startX = leftX
            if (alignCenter) startX = leftX + ((maxX - leftX) - lineWidth) / 2f
            var cx = startX
            for (t in lineToks) {
                val font = when { t.b && t.i -> F_BI; t.b -> F_BOLD; t.i -> F_ITAL; else -> F_REG }
                sb.append("BT /$font $size Tf ").append(fmt(cx)).append(' ').append(fmt(y)).append(" Td (").append(pdfStr(t.w)).append(") Tj ET\n")
                cx += wordWidth(t.w, size) + spaceW
            }
            y -= lineH
            lineToks = ArrayList(); lineWidth = 0f
        }

        for (t in toks) {
            val ww = wordWidth(t.w, size)
            if (lineToks.isNotEmpty() && x + ww > maxX) { emitLine(); x = leftX }
            lineToks.add(t); lineWidth += ww + spaceW; x += ww + spaceW
        }
        emitLine()
        y -= lineH * 0.35f
        return y
    }

    private fun wordWidth(w: String, size: Float): Float = w.length * size * 0.5f

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    private fun pdfStr(s: String): String {
        val sb = StringBuilder()
        for (c in s) when {
            c == '(' -> sb.append("\\(")
            c == ')' -> sb.append("\\)")
            c == '\\' -> sb.append("\\\\")
            c == '\u2022' -> sb.append("\\225") // bullet in WinAnsi
            c.code in 32..126 -> sb.append(c)
            c.code in 160..255 -> sb.append("\\" + Integer.toOctalString(c.code))
            else -> sb.append('?')
        }
        return sb.toString()
    }

    private fun assemble(pages: List<StringBuilder>): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = StringBuilder()
        out.append("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n")
        val offsets = ArrayList<Int>()
        val objs = ArrayList<String>()

        // Reserve object numbers: 1 catalog, 2 pages, 3-6 fonts, then per page (content, page).
        val fontObjs = mapOf(F_REG to 3, F_BOLD to 4, F_ITAL to 5, F_BI to 6)
        val pageObjNums = ArrayList<Int>()
        var next = 7
        val pageBodies = ArrayList<Pair<Int, Int>>() // contentObj, pageObj
        for (i in pages.indices) { val contentObj = next++; val pageObj = next++; pageBodies.add(contentObj to pageObj); pageObjNums.add(pageObj) }

        objs.add("<< /Type /Catalog /Pages 2 0 R >>")
        objs.add("<< /Type /Pages /Kids [${pageObjNums.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>")
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Oblique /Encoding /WinAnsiEncoding >>")
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-BoldOblique /Encoding /WinAnsiEncoding >>")
        for (i in pages.indices) {
            val stream = pages[i].toString()
            objs.add("<< /Length ${stream.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n$stream\nendstream")
            objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_W $PAGE_H] " +
                "/Resources << /Font << /$F_REG 3 0 R /$F_BOLD 4 0 R /$F_ITAL 5 0 R /$F_BI 6 0 R >> >> " +
                "/Contents ${pageBodies[i].first} 0 R >>")
        }
        // Reorder objs to match object numbers 1..N (contentObj/pageObj interleave already appended in order 7,8,9,10...).
        var objNum = 1
        for (body in objs) {
            offsets.add(out.toString().toByteArray(Charsets.ISO_8859_1).size)
            out.append("$objNum 0 obj\n$body\nendobj\n")
            objNum++
        }
        val xrefOff = out.toString().toByteArray(Charsets.ISO_8859_1).size
        out.append("xref\n0 ${objs.size + 1}\n")
        out.append("0000000000 65535 f \n")
        for (off in offsets) out.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", off))
        out.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOff\n%%EOF\n")

        bos.write(out.toString().toByteArray(Charsets.ISO_8859_1))
        return bos.toByteArray()
    }
}
