package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exporter from the ODF document model to Microsoft OOXML packages (.docx / .xlsx / .pptx).
 * Writes rich formatting so that content imported by OoxmlImporter survives a round-trip: run &
 * paragraph typography, lists, tables (borders/merges/shading), images, hyperlinks, page setup,
 * headers/footers and footnotes (docx); cell styles/number-formats/formulas/merges/layout and
 * embedded images/charts (xlsx); shapes/fills/images/charts/backgrounds/notes (pptx).
 */
object OoxmlExporter {

    fun export(document: OdfDocument): ByteArray = when (document) {
        is OdfDocument.TextDocument -> buildDocx(document)
        is OdfDocument.Spreadsheet -> buildXlsx(document)
        is OdfDocument.Presentation -> buildPptx(document.slides, document.images)
        is OdfDocument.Drawing -> buildPptx(document.pages, document.images)
    }

    /** OOXML extension for a document type. */
    fun extensionFor(document: OdfDocument): String = when (document) {
        is OdfDocument.TextDocument -> "docx"
        is OdfDocument.Spreadsheet -> "xlsx"
        else -> "pptx"
    }

    // --- Package builder (supports binary media) ---

    private class Pkg {
        val files = LinkedHashMap<String, ByteArray>()
        val extensions = sortedSetOf<String>()
        fun text(name: String, content: String) { files[name] = content.toByteArray(Charsets.UTF_8) }
        fun bytes(name: String, data: ByteArray) { files[name] = data; extensions.add(name.substringAfterLast('.').lowercase()) }
        fun zip(): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                for ((name, content) in files) {
                    zos.putNextEntry(ZipEntry(name)); zos.write(content); zos.closeEntry()
                }
            }
            return bos.toByteArray()
        }
    }

    private class Rel(val id: String, val type: String, val target: String, val external: Boolean = false)

    private fun relsXml(rels: List<Rel>): String {
        val sb = StringBuilder(XMLDECL)
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (r in rels) {
            sb.append("<Relationship Id=\"${r.id}\" Type=\"${r.type}\" Target=\"${esc(r.target)}\"")
            if (r.external) sb.append(" TargetMode=\"External\"")
            sb.append("/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); '\'' -> sb.append("&apos;")
            '\t', '\n' -> sb.append(c)
            else -> if (c.code >= 0x20) sb.append(c)
        }
        return sb.toString()
    }

    private const val XMLDECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private fun emu(px: Float): Long = (px * 9525f).toLong()
    private fun hex(color: Long): String = "%06X".format(color and 0xFFFFFF)
    private fun mediaExt(path: String): String = path.substringAfterLast('.', "png").lowercase().let { if (it == "jpg") "jpeg" else it }

    private val IMAGE_CONTENT_TYPES = mapOf(
        "png" to "image/png", "jpeg" to "image/jpeg", "jpg" to "image/jpeg", "gif" to "image/gif",
        "bmp" to "image/bmp", "emf" to "image/x-emf", "wmf" to "image/x-wmf", "svg" to "image/svg+xml", "tiff" to "image/tiff"
    )

    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val REL_IMAGE = "$NS_R/image"
    private const val REL_HYPERLINK = "$NS_R/hyperlink"
    private const val REL_CHART = "$NS_R/chart"

    // ==================== DOCX ====================

    private class DocxAssets {
        val rels = mutableListOf<Rel>()
        val media = LinkedHashMap<String, ByteArray>()   // package path -> bytes
        val extraParts = LinkedHashMap<String, String>() // package path -> xml (charts etc.)
        val chartRels = LinkedHashMap<String, List<Rel>>()
        var seq = 1
        fun nextRid() = "rId${seq++}"

        fun imageRel(image: OdfImage): String {
            val ext = mediaExt(image.path)
            val name = "media/image${media.size + 1}.$ext"
            media["word/$name"] = image.imageData
            val rid = nextRid()
            rels.add(Rel(rid, REL_IMAGE, name))
            return rid
        }
        fun hyperlinkRel(url: String): String {
            val rid = nextRid()
            rels.add(Rel(rid, REL_HYPERLINK, url, external = true))
            return rid
        }
        fun chartRel(xml: String, rels2: List<Rel>): String {
            val idx = extraParts.size + 1
            val part = "charts/chart$idx.xml"
            extraParts["word/$part"] = xml
            if (rels2.isNotEmpty()) chartRels["word/$part"] = rels2
            val rid = nextRid()
            rels.add(Rel(rid, REL_CHART, part))
            return rid
        }
    }

    private fun buildDocx(doc: OdfDocument.TextDocument): ByteArray {
        val pkg = Pkg()
        val assets = DocxAssets()
        val numbering = DocxNumbering()

        val body = StringBuilder()
        for (block in doc.content) when (block) {
            is OdfContentBlock.Paragraph -> body.append(docxParagraph(block.paragraph, assets, numbering))
            is OdfContentBlock.Table -> body.append(docxTable(block.table, assets, numbering))
            is OdfContentBlock.TableOfContents -> for (e in block.entries) body.append(docxParagraph(e, assets, numbering))
            is OdfContentBlock.PageBreak -> body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            is OdfContentBlock.Image -> body.append(docxImageParagraph(block.image, assets))
            is OdfContentBlock.Chart -> body.append(docxChartParagraph(block.chart, assets))
            is OdfContentBlock.Formula -> body.append(docxParagraph(OdfParagraph(listOf(OdfSpan("[formula]", italic = true))), assets, numbering))
            is OdfContentBlock.SectionStart, OdfContentBlock.SectionEnd -> {}
        }

        // Footnotes: emit part + reference at matching citation spans.
        val footnotesPart = if (doc.footnotes.isNotEmpty()) buildFootnotes(doc.footnotes, assets, numbering) else null

        body.append(sectPrXml(doc.pageSetup, hasHeader = doc.headerParagraphs.isNotEmpty(), hasFooter = doc.footerParagraphs.isNotEmpty()))

        val nsDecl = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
            " xmlns:r=\"$NS_R\"" +
            " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"" +
            " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"" +
            " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"" +
            " xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"" +
            " xmlns:m=\"http://schemas.openxmlformats.org/officeMath/2006/main\""
        val document = "$XMLDECL<w:document $nsDecl><w:body>$body</w:body></w:document>"

        // Headers / footers.
        if (doc.headerParagraphs.isNotEmpty()) {
            pkg.text("word/header1.xml", "$XMLDECL<w:hdr $nsDecl>${doc.headerParagraphs.joinToString("") { docxParagraph(it, assets, numbering) }}</w:hdr>")
        }
        if (doc.footerParagraphs.isNotEmpty()) {
            pkg.text("word/footer1.xml", "$XMLDECL<w:ftr $nsDecl>${doc.footerParagraphs.joinToString("") { docxParagraph(it, assets, numbering) }}</w:ftr>")
        }

        // Content types.
        val ctypes = StringBuilder(XMLDECL)
        ctypes.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        ctypes.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ctypes.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        for (ext in pkg.extensions) IMAGE_CONTENT_TYPES[ext]?.let { ctypes.append("<Default Extension=\"$ext\" ContentType=\"$it\"/>") }
        ctypes.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        ctypes.append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>")
        if (numbering.isNotEmpty()) ctypes.append("<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>")
        if (footnotesPart != null) ctypes.append("<Override PartName=\"/word/footnotes.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml\"/>")
        if (doc.headerParagraphs.isNotEmpty()) ctypes.append("<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>")
        if (doc.footerParagraphs.isNotEmpty()) ctypes.append("<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>")
        for (part in assets.extraParts.keys) ctypes.append("<Override PartName=\"/$part\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>")
        ctypes.append("</Types>")

        pkg.text("[Content_Types].xml", ctypes.toString())
        pkg.text("_rels/.rels", relsXml(listOf(Rel("rId1", "$NS_R/officeDocument", "word/document.xml"))))
        pkg.text("word/document.xml", document)
        pkg.text("word/styles.xml", DOCX_STYLES)
        if (numbering.isNotEmpty()) pkg.text("word/numbering.xml", numbering.toXml())
        footnotesPart?.let { pkg.text("word/footnotes.xml", it) }

        // document.xml.rels (styles/numbering/footnotes/header/footer + collected image/hyperlink/chart rels).
        val docRels = mutableListOf<Rel>()
        docRels.add(Rel("rIdStyles", "$NS_R/styles", "styles.xml"))
        if (numbering.isNotEmpty()) docRels.add(Rel("rIdNum", "$NS_R/numbering", "numbering.xml"))
        if (footnotesPart != null) docRels.add(Rel("rIdFootnotes", "$NS_R/footnotes", "footnotes.xml"))
        if (doc.headerParagraphs.isNotEmpty()) docRels.add(Rel("rIdHeader", "$NS_R/header", "header1.xml"))
        if (doc.footerParagraphs.isNotEmpty()) docRels.add(Rel("rIdFooter", "$NS_R/footer", "footer1.xml"))
        docRels.addAll(assets.rels)
        pkg.text("word/_rels/document.xml.rels", relsXml(docRels))

        for ((name, data) in assets.media) pkg.bytes(name, data)
        for ((name, xml) in assets.extraParts) {
            pkg.text(name, xml)
            assets.chartRels[name]?.let { pkg.text("word/charts/_rels/${name.substringAfterLast('/')}.rels", relsXml(it)) }
        }
        return pkg.zip()
    }

    private fun sectPrXml(page: OdfPageSetup?, hasHeader: Boolean, hasFooter: Boolean): String {
        val sb = StringBuilder("<w:sectPr>")
        if (hasHeader) sb.append("<w:headerReference w:type=\"default\" r:id=\"rIdHeader\"/>")
        if (hasFooter) sb.append("<w:footerReference w:type=\"default\" r:id=\"rIdFooter\"/>")
        if (page != null) {
            val w = (page.widthPx / 96f * 1440f).toInt()
            val h = (page.heightPx / 96f * 1440f).toInt()
            val orient = if (page.isLandscape) " w:orient=\"landscape\"" else ""
            sb.append("<w:pgSz w:w=\"$w\" w:h=\"$h\"$orient/>")
            sb.append("<w:pgMar w:top=\"${px2tw(page.marginTopPx)}\" w:right=\"${px2tw(page.marginRightPx)}\" w:bottom=\"${px2tw(page.marginBottomPx)}\" w:left=\"${px2tw(page.marginLeftPx)}\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>")
        }
        sb.append("</w:sectPr>")
        return sb.toString()
    }

    private fun px2tw(px: Float): Int = (px / 96f * 1440f).toInt()

    private fun docxParagraph(p: OdfParagraph, assets: DocxAssets, numbering: DocxNumbering): String {
        val sb = StringBuilder("<w:p>")
        val ppr = StringBuilder()
        val styleId = when (p.style) {
            ParagraphStyle.HEADING1 -> "Heading1"; ParagraphStyle.HEADING2 -> "Heading2"
            ParagraphStyle.HEADING3 -> "Heading3"; ParagraphStyle.HEADING4 -> "Heading4"; else -> null
        }
        if (styleId != null) ppr.append("<w:pStyle w:val=\"$styleId\"/>")
        if (p.style == ParagraphStyle.LIST_ITEM) {
            val numId = numbering.numIdFor(p)
            ppr.append("<w:numPr><w:ilvl w:val=\"${p.listLevel}\"/><w:numId w:val=\"$numId\"/></w:numPr>")
        }
        p.backgroundColor?.let { ppr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
        docxParagraphBorders(p)?.let { ppr.append(it) }
        val ind = StringBuilder()
        if (p.marginLeft != 0f) ind.append(" w:left=\"${px2tw(p.marginLeft)}\"")
        if (p.marginRight != 0f) ind.append(" w:right=\"${px2tw(p.marginRight)}\"")
        if (p.textIndent > 0f) ind.append(" w:firstLine=\"${px2tw(p.textIndent)}\"")
        else if (p.textIndent < 0f) ind.append(" w:hanging=\"${px2tw(-p.textIndent)}\"")
        if (ind.isNotEmpty()) ppr.append("<w:ind$ind/>")
        val spacing = StringBuilder()
        if (p.marginTop != 0f) spacing.append(" w:before=\"${px2tw(p.marginTop)}\"")
        if (p.marginBottom != 0f) spacing.append(" w:after=\"${px2tw(p.marginBottom)}\"")
        p.lineHeightPercent?.let { spacing.append(" w:line=\"${(it * 240).toInt()}\" w:lineRule=\"auto\"") }
        if (spacing.isNotEmpty()) ppr.append("<w:spacing$spacing/>")
        val jc = when (p.alignment) {
            TextAlign.Center -> "center"; TextAlign.End, TextAlign.Right -> "right"; TextAlign.Justify -> "both"; else -> null
        }
        if (jc != null) ppr.append("<w:jc w:val=\"$jc\"/>")
        if (p.direction == LayoutDirection.Rtl) ppr.append("<w:bidi/>")
        if (p.keepWithNext) ppr.append("<w:keepNext/>")
        if (p.keepTogether) ppr.append("<w:keepLines/>")
        if (p.breakBeforePage) ppr.append("<w:pageBreakBefore/>")
        if (p.tabStopDetails.isNotEmpty()) {
            ppr.append("<w:tabs>")
            for (t in p.tabStopDetails) {
                val v = when (t.type) { "center" -> "center"; "right" -> "right"; "char" -> "decimal"; else -> "left" }
                val leader = when (t.leaderChar) { "." -> " w:leader=\"dot\""; "-" -> " w:leader=\"hyphen\""; "_" -> " w:leader=\"underscore\""; else -> "" }
                ppr.append("<w:tab w:val=\"$v\"$leader w:pos=\"${px2tw(t.position)}\"/>")
            }
            ppr.append("</w:tabs>")
        }
        if (ppr.isNotEmpty()) sb.append("<w:pPr>$ppr</w:pPr>")
        for (span in p.spans) sb.append(docxRun(span, assets))
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun docxParagraphBorders(p: OdfParagraph): String? {
        val b = p.borders
        val color = p.borderColor
        if (b == null && color == null) return null
        val sb = StringBuilder("<w:pBdr>")
        fun edge(name: String, value: String?) {
            val c = OdfBorders.renderColor(value) ?: color ?: return
            sb.append("<w:$name w:val=\"single\" w:sz=\"6\" w:space=\"4\" w:color=\"${hex(c)}\"/>")
        }
        if (b != null) { edge("top", b.top); edge("left", b.left); edge("bottom", b.bottom); edge("right", b.right) }
        else color?.let { for (e in listOf("top", "left", "bottom", "right")) sb.append("<w:$e w:val=\"single\" w:sz=\"6\" w:space=\"4\" w:color=\"${hex(it)}\"/>") }
        sb.append("</w:pBdr>")
        return sb.toString()
    }

    private fun docxRun(span: OdfSpan, assets: DocxAssets): String {
        if (span.text.isEmpty()) return ""
        val rpr = docxRunProps(span)
        val content = docxRunText(span.text)
        val run = "<w:r>$rpr$content</w:r>"
        // Hyperlink / field wrappers.
        if (span.href != null) {
            return if (span.href.startsWith("#")) "<w:hyperlink w:anchor=\"${esc(span.href.substring(1))}\">$run</w:hyperlink>"
            else { val rid = assets.hyperlinkRel(span.href); "<w:hyperlink r:id=\"$rid\">$run</w:hyperlink>" }
        }
        if (span.field != null) {
            val instr = fieldInstr(span.field)
            if (instr != null) return "<w:fldSimple w:instr=\"$instr\">$run</w:fldSimple>"
        }
        return run
    }

    private fun docxRunProps(span: OdfSpan): String {
        val rpr = StringBuilder()
        if (span.bold) rpr.append("<w:b/>")
        if (span.italic) rpr.append("<w:i/>")
        if (span.underline) rpr.append("<w:u w:val=\"${docxUnderline(span.underlineStyle)}\"${span.underlineColor?.let { " w:color=\"${hex(it)}\"" } ?: ""}/>")
        if (span.strikethrough) rpr.append("<w:strike/>")
        span.fontFamily?.let { rpr.append("<w:rFonts w:ascii=\"${esc(it)}\" w:hAnsi=\"${esc(it)}\"/>") }
        span.color?.let { rpr.append("<w:color w:val=\"${hex(it)}\"/>") }
        span.letterSpacing?.let { rpr.append("<w:spacing w:val=\"${(it * 20).toInt()}\"/>") }
        if (span.textTransform == "uppercase") rpr.append("<w:caps/>")
        span.backgroundColor?.let { rpr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
        span.fontSize?.let { rpr.append("<w:sz w:val=\"${(it * 2).toInt()}\"/>") }
        if (span.superscript) rpr.append("<w:vertAlign w:val=\"superscript\"/>")
        if (span.subscript) rpr.append("<w:vertAlign w:val=\"subscript\"/>")
        span.language?.let { rpr.append("<w:lang w:val=\"$it${span.country?.let { c -> "-$c" } ?: ""}\"/>") }
        return if (rpr.isNotEmpty()) "<w:rPr>$rpr</w:rPr>" else ""
    }

    private fun docxRunText(text: String): String {
        val content = StringBuilder()
        text.split("\n").forEachIndexed { i, part ->
            if (i > 0) content.append("<w:br/>")
            part.split("\t").forEachIndexed { j, tp ->
                if (j > 0) content.append("<w:tab/>")
                if (tp.isNotEmpty()) content.append("<w:t xml:space=\"preserve\">${esc(tp)}</w:t>")
            }
        }
        return content.toString()
    }

    private fun docxUnderline(style: String?): String = when (style) {
        "double" -> "double"; "dotted" -> "dotted"; "dash" -> "dash"; "wave" -> "wave"; else -> "single"
    }

    private fun fieldInstr(field: String): String? = when (field) {
        "page-number" -> " PAGE "; "page-count" -> " NUMPAGES "; "date" -> " DATE "; "time" -> " TIME "
        "author-name" -> " AUTHOR "; "file-name" -> " FILENAME "; "title" -> " TITLE "; else -> null
    }

    private fun docxImageParagraph(image: OdfImage, assets: DocxAssets): String {
        val rid = assets.imageRel(image)
        val w = emu(if (image.width > 0) image.width else 320f)
        val h = emu(if (image.height > 0) image.height else 240f)
        val desc = esc(image.altDesc ?: image.altTitle ?: "")
        val drawing = "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$w\" cy=\"$h\"/><wp:docPr id=\"1\" name=\"Picture\" descr=\"$desc\"/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"img\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>" +
            "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
        return "<w:p>$drawing</w:p>"
    }

    private fun docxChartParagraph(chart: OdfChart, assets: DocxAssets): String {
        val rid = assets.chartRel(chartXml(chart), emptyList())
        val w = emu(480f); val h = emu(300f)
        val drawing = "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$w\" cy=\"$h\"/><wp:docPr id=\"2\" name=\"Chart\"/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">" +
            "<c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"$rid\"/>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"
        return "<w:p>$drawing</w:p>"
    }

    private fun docxTable(table: OdfTable, assets: DocxAssets, numbering: DocxNumbering): String {
        val colCount = table.rows.maxOfOrNull { r -> r.cells.size } ?: 0
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV"))
            sb.append("<w:$edge w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
        sb.append("</w:tblBorders></w:tblPr><w:tblGrid>")
        for (c in 0 until colCount.coerceAtLeast(1)) {
            val w = table.columns.getOrNull(c)?.width?.let { px2tw(it) } ?: 2340
            sb.append("<w:gridCol w:w=\"$w\"/>")
        }
        sb.append("</w:tblGrid>")
        table.rows.forEachIndexed { ri, row ->
            sb.append("<w:tr>")
            if (ri < table.headerRowCount) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (cell in row.cells) {
                if (cell.isCovered && cell.rowSpan <= 1 && cell.colSpan <= 1 && cell.paragraphs.isEmpty()) {
                    // horizontally covered cell already accounted via gridSpan; skip pure covered
                    continue
                }
                if (cell.isCovered) continue
                sb.append("<w:tc><w:tcPr>")
                if (cell.colSpan > 1) sb.append("<w:gridSpan w:val=\"${cell.colSpan}\"/>")
                if (cell.rowSpan > 1) sb.append("<w:vMerge w:val=\"restart\"/>")
                cell.backgroundColor?.let { sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
                docxCellBorders(cell)?.let { sb.append(it) }
                cell.verticalAlign?.let { sb.append("<w:vAlign w:val=\"${when (it) { "middle" -> "center"; "bottom" -> "bottom"; else -> "top" }}\"/>") }
                sb.append("</w:tcPr>")
                if (cell.paragraphs.isEmpty()) sb.append("<w:p/>") else for (p in cell.paragraphs) sb.append(docxParagraph(p, assets, numbering))
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun docxCellBorders(cell: OdfTableCell): String? {
        val b = cell.borders ?: return null
        if (b.isEmpty()) return null
        val sb = StringBuilder("<w:tcBorders>")
        fun edge(name: String, value: String?) { OdfBorders.renderColor(value)?.let { sb.append("<w:$name w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"${hex(it)}\"/>") } }
        edge("top", b.top); edge("left", b.left); edge("bottom", b.bottom); edge("right", b.right)
        sb.append("</w:tcBorders>")
        return sb.toString()
    }

    private fun buildFootnotes(footnotes: List<OdfFootnote>, assets: DocxAssets, numbering: DocxNumbering): String {
        val nsDecl = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        val sb = StringBuilder("$XMLDECL<w:footnotes $nsDecl>")
        sb.append("<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>")
        sb.append("<w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>")
        footnotes.forEachIndexed { i, fn ->
            sb.append("<w:footnote w:id=\"${i + 1}\">")
            if (fn.body.isEmpty()) sb.append("<w:p/>") else for (p in fn.body) sb.append(docxParagraph(p, assets, numbering))
            sb.append("</w:footnote>")
        }
        sb.append("</w:footnotes>")
        return sb.toString()
    }

    /** Generates docx numbering.xml from the list styles encountered while emitting paragraphs. */
    private class DocxNumbering {
        private data class Key(val type: ListType, val format: String, val bullet: String, val prefix: String, val suffix: String)
        private val keys = LinkedHashMap<Key, Int>()

        fun numIdFor(p: OdfParagraph): Int {
            val key = Key(p.listType, p.listNumberFormat, p.listBulletChar, p.listNumberPrefix, p.listNumberSuffix)
            return keys.getOrPut(key) { keys.size + 1 }
        }
        fun isNotEmpty() = keys.isNotEmpty()

        fun toXml(): String {
            val sb = StringBuilder("$XMLDECL<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
            keys.values.forEach { id ->
                val key = keys.entries.first { it.value == id }.key
                sb.append("<w:abstractNum w:abstractNumId=\"${id - 1}\">")
                for (lvl in 0..8) {
                    sb.append("<w:lvl w:ilvl=\"$lvl\">")
                    sb.append("<w:start w:val=\"1\"/>")
                    if (key.type == ListType.BULLET) {
                        sb.append("<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"${esc(key.bullet)}\"/>")
                    } else {
                        val fmt = when (key.format) { "a" -> "lowerLetter"; "A" -> "upperLetter"; "i" -> "lowerRoman"; "I" -> "upperRoman"; else -> "decimal" }
                        sb.append("<w:numFmt w:val=\"$fmt\"/><w:lvlText w:val=\"${esc(key.prefix)}%${lvl + 1}${esc(key.suffix)}\"/>")
                    }
                    sb.append("<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"${720 * (lvl + 1)}\" w:hanging=\"360\"/></w:pPr>")
                    sb.append("</w:lvl>")
                }
                sb.append("</w:abstractNum>")
            }
            keys.values.forEach { id -> sb.append("<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"${id - 1}\"/></w:num>") }
            sb.append("</w:numbering>")
            return sb.toString()
        }
    }

    private val DOCX_STYLES = XMLDECL +
        "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
        (1..4).joinToString("") { n ->
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$n\"><w:name w:val=\"heading $n\"/>" +
                "<w:basedOn w:val=\"Normal\"/><w:pPr><w:outlineLvl w:val=\"${n - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:sz w:val=\"${36 - (n - 1) * 4}\"/></w:rPr></w:style>"
        } +
        "</w:styles>"

    // ==================== Chart XML (shared by docx/xlsx/pptx) ====================

    private fun chartXml(chart: OdfChart): String {
        val c = "http://schemas.openxmlformats.org/drawingml/2006/chart"
        val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val r = NS_R
        val sb = StringBuilder("$XMLDECL<c:chartSpace xmlns:c=\"$c\" xmlns:a=\"$a\" xmlns:r=\"$r\"><c:chart>")
        chart.title?.let { sb.append("<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>${esc(it)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title>") }
        sb.append("<c:plotArea><c:layout/>")
        val tag = when (chart.type) {
            ChartType.LINE -> "lineChart"; ChartType.PIE -> "pieChart"; ChartType.DONUT -> "doughnutChart"
            ChartType.AREA -> "areaChart"; ChartType.SCATTER -> "scatterChart"; ChartType.RADAR -> "radarChart"
            else -> "barChart"
        }
        sb.append("<c:$tag>")
        if (tag == "barChart") sb.append("<c:barDir val=\"col\"/>")
        if (chart.stacked) sb.append("<c:grouping val=\"stacked\"/>") else if (tag == "barChart" || tag == "lineChart" || tag == "areaChart") sb.append("<c:grouping val=\"clustered\"/>")
        chart.series.forEachIndexed { si, s ->
            sb.append("<c:ser><c:idx val=\"$si\"/><c:order val=\"$si\"/>")
            sb.append("<c:tx><c:strRef><c:f>Sheet1!\$${('B' + si)}\$1</c:f><c:strCache><c:ptCount val=\"1\"/><c:pt idx=\"0\"><c:v>${esc(s.name)}</c:v></c:pt></c:strCache></c:strRef></c:tx>")
            s.color?.let { sb.append("<c:spPr><a:solidFill><a:srgbClr val=\"${hex(it)}\"/></a:solidFill></c:spPr>") }
            sb.append("<c:cat><c:strRef><c:f>Sheet1!\$A\$2:\$A\$${chart.categories.size + 1}</c:f><c:strCache><c:ptCount val=\"${chart.categories.size}\"/>")
            chart.categories.forEachIndexed { i, cat -> sb.append("<c:pt idx=\"$i\"><c:v>${esc(cat)}</c:v></c:pt>") }
            sb.append("</c:strCache></c:strRef></c:cat>")
            sb.append("<c:val><c:numRef><c:f>Sheet1!\$${('B' + si)}\$2:\$${('B' + si)}\$${s.values.size + 1}</c:f><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"${s.values.size}\"/>")
            s.values.forEachIndexed { i, v -> sb.append("<c:pt idx=\"$i\"><c:v>$v</c:v></c:pt>") }
            sb.append("</c:numCache></c:numRef></c:val>")
            sb.append("</c:ser>")
        }
        if (tag == "barChart") sb.append("<c:axId val=\"1\"/><c:axId val=\"2\"/>")
        sb.append("</c:$tag>")
        if (tag == "barChart" || tag == "lineChart" || tag == "areaChart") {
            sb.append("<c:catAx><c:axId val=\"1\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"b\"/>")
            chart.xAxisTitle?.let { sb.append("<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>${esc(it)}</a:t></a:r></a:p></c:rich></c:tx></c:title>") }
            sb.append("<c:crossAx val=\"2\"/></c:catAx>")
            sb.append("<c:valAx><c:axId val=\"2\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"l\"/>")
            chart.yAxisTitle?.let { sb.append("<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>${esc(it)}</a:t></a:r></a:p></c:rich></c:tx></c:title>") }
            sb.append("<c:crossAx val=\"1\"/></c:valAx>")
        }
        sb.append("</c:plotArea>")
        if (chart.legend) sb.append("<c:legend><c:legendPos val=\"r\"/></c:legend>")
        sb.append("<c:plotVisOnly val=\"1\"/></c:chart></c:chartSpace>")
        return sb.toString()
    }

    // ==================== XLSX ====================

    private fun buildXlsx(doc: OdfDocument.Spreadsheet): ByteArray {
        val sheets = doc.sheets.ifEmpty { listOf(OdfSheet("Sheet1", emptyList())) }
        val pkg = Pkg()
        val styles = XlsxStyles()
        val media = LinkedHashMap<String, ByteArray>()      // package path -> bytes
        val extraParts = LinkedHashMap<String, String>()    // package path -> xml (drawings, charts)
        val extraPartRels = LinkedHashMap<String, List<Rel>>()

        // Build each sheet's xml + its external rels (hyperlinks, drawing).
        val sheetXmls = ArrayList<String>()
        val sheetRels = ArrayList<List<Rel>>()
        sheets.forEachIndexed { idx, sheet ->
            val rels = mutableListOf<Rel>()
            // Drawing (images + charts) for this sheet.
            val drawingXml = xlsxDrawing(sheet, idx, media, extraParts)
            var drawingRid: String? = null
            if (drawingXml != null) {
                val drawingPart = "xl/drawings/drawing${idx + 1}.xml"
                extraParts[drawingPart] = drawingXml.first
                if (drawingXml.second.isNotEmpty()) extraPartRels[drawingPart] = drawingXml.second
                drawingRid = "rIdDraw"
                rels.add(Rel(drawingRid, "$NS_R/drawing", "../drawings/drawing${idx + 1}.xml"))
            }
            sheetXmls.add(xlsxSheet(sheet, styles, rels, drawingRid))
            sheetRels.add(rels)
        }

        // Content types.
        val ctypes = StringBuilder(XMLDECL)
        ctypes.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        ctypes.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ctypes.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        val imgExts = media.keys.map { it.substringAfterLast('.').lowercase() }.toSortedSet()
        for (ext in imgExts) IMAGE_CONTENT_TYPES[ext]?.let { ctypes.append("<Default Extension=\"$ext\" ContentType=\"$it\"/>") }
        ctypes.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
        ctypes.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
        for (i in sheets.indices) ctypes.append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
        for (part in extraParts.keys) {
            val ct = if (part.contains("/charts/")) "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"
            else "application/vnd.openxmlformats-officedocument.drawing+xml"
            ctypes.append("<Override PartName=\"/$part\" ContentType=\"$ct\"/>")
        }
        ctypes.append("</Types>")

        // Workbook + defined names (named ranges + print areas).
        val wb = StringBuilder(XMLDECL)
        wb.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$NS_R\"><sheets>")
        sheets.forEachIndexed { i, s ->
            val vis = if (s.hidden) " state=\"hidden\"" else ""
            wb.append("<sheet name=\"${esc(s.name.take(31).ifBlank { "Sheet${i + 1}" })}\" sheetId=\"${i + 1}\"$vis r:id=\"rId${i + 1}\"/>")
        }
        wb.append("</sheets>")
        val definedNames = StringBuilder()
        for (nr in doc.namedRanges) definedNames.append("<definedName name=\"${esc(nr.name)}\">${esc(odfAddrToExcel(nr.cellRangeAddress))}</definedName>")
        sheets.forEachIndexed { i, s -> s.printRanges?.let { definedNames.append("<definedName name=\"_xlnm.Print_Area\" localSheetId=\"$i\">${esc(odfAddrToExcel(it))}</definedName>") } }
        if (definedNames.isNotEmpty()) wb.append("<definedNames>$definedNames</definedNames>")
        wb.append("</workbook>")

        val wbRels = mutableListOf<Rel>()
        sheets.forEachIndexed { i, _ -> wbRels.add(Rel("rId${i + 1}", "$NS_R/worksheet", "worksheets/sheet${i + 1}.xml")) }
        wbRels.add(Rel("rIdStyles", "$NS_R/styles", "styles.xml"))

        pkg.text("[Content_Types].xml", ctypes.toString())
        pkg.text("_rels/.rels", relsXml(listOf(Rel("rId1", "$NS_R/officeDocument", "xl/workbook.xml"))))
        pkg.text("xl/workbook.xml", wb.toString())
        pkg.text("xl/_rels/workbook.xml.rels", relsXml(wbRels))
        pkg.text("xl/styles.xml", styles.toXml())
        sheetXmls.forEachIndexed { i, xml ->
            pkg.text("xl/worksheets/sheet${i + 1}.xml", xml)
            if (sheetRels[i].isNotEmpty()) pkg.text("xl/worksheets/_rels/sheet${i + 1}.xml.rels", relsXml(sheetRels[i]))
        }
        for ((name, xml) in extraParts) {
            pkg.text(name, xml)
            extraPartRels[name]?.let {
                val dir = name.substringBeforeLast('/'); val file = name.substringAfterLast('/')
                pkg.text("$dir/_rels/$file.rels", relsXml(it))
            }
        }
        for ((name, data) in media) pkg.bytes(name, data)
        return pkg.zip()
    }

    private fun colLetter(index: Int): String {
        var n = index + 1; val sb = StringBuilder()
        while (n > 0) { val rem = (n - 1) % 26; sb.insert(0, ('A' + rem)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun xlsxSheet(sheet: OdfSheet, styles: XlsxStyles, rels: MutableList<Rel>, drawingRid: String?): String {
        val sb = StringBuilder(XMLDECL)
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"$NS_R\">")
        sheet.tabColor?.let { sb.append("<sheetPr><tabColor rgb=\"FF${hex(it)}\"/></sheetPr>") }
        // Freeze panes.
        if (sheet.freezeRows > 0 || sheet.freezeCols > 0) {
            val topLeft = "${colLetter(sheet.freezeCols)}${sheet.freezeRows + 1}"
            sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
            sb.append("<pane xSplit=\"${sheet.freezeCols}\" ySplit=\"${sheet.freezeRows}\" topLeftCell=\"$topLeft\" activePane=\"bottomRight\" state=\"frozen\"/>")
            sb.append("</sheetView></sheetViews>")
        }
        // Columns.
        if (sheet.columnWidths.any { it != null } || sheet.hiddenCols.isNotEmpty()) {
            sb.append("<cols>")
            val maxCol = maxOf(sheet.columnWidths.size, (sheet.hiddenCols.maxOrNull() ?: -1) + 1)
            for (c in 0 until maxCol) {
                val w = sheet.columnWidths.getOrNull(c)
                val hidden = c in sheet.hiddenCols
                if (w == null && !hidden) continue
                val chars = w?.let { ((it - 5f) / 7f).coerceAtLeast(1f) } ?: 8.43f
                sb.append("<col min=\"${c + 1}\" max=\"${c + 1}\" width=\"$chars\" customWidth=\"1\"${if (hidden) " hidden=\"1\"" else ""}/>")
            }
            sb.append("</cols>")
        }
        sb.append("<sheetData>")
        val merges = mutableListOf<String>()
        sheet.rows.forEachIndexed { ri, row ->
            val ht = sheet.rowHeights.getOrNull(ri)
            val hidden = ri in sheet.hiddenRows
            val rowAttrs = StringBuilder(" r=\"${ri + 1}\"")
            ht?.let { rowAttrs.append(" ht=\"${it / 96f * 72f}\" customHeight=\"1\"") }
            if (hidden) rowAttrs.append(" hidden=\"1\"")
            sb.append("<row$rowAttrs>")
            row.cells.forEachIndexed { ci, cell ->
                if (cell.spannedColumns > 1 || cell.rowSpan > 1) merges.add("${colLetter(ci)}${ri + 1}:${colLetter(ci + cell.spannedColumns - 1)}${ri + cell.rowSpan}")
                if (cell.isCovered) return@forEachIndexed
                val styleIdx = styles.styleIndex(cell)
                val sAttr = if (styleIdx > 0) " s=\"$styleIdx\"" else ""
                val ref = "${colLetter(ci)}${ri + 1}"
                val formula = cell.formula?.let { odfFormulaToExcel(it) }
                if (formula != null) {
                    val cached = cell.numberValue?.let { numStr(it) } ?: ""
                    sb.append("<c r=\"$ref\"$sAttr><f>${esc(formula)}</f>${if (cached.isNotEmpty()) "<v>$cached</v>" else ""}</c>")
                } else {
                    val num = cell.numberValue ?: (if (cell.valueType != "string") cell.text.toDoubleOrNull() else null)
                    if (num != null && cell.valueType != "string") {
                        sb.append("<c r=\"$ref\"$sAttr><v>${numStr(num)}</v></c>")
                    } else if (cell.text.isNotEmpty() || styleIdx > 0) {
                        sb.append("<c r=\"$ref\"$sAttr t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(cell.text)}</t></is></c>")
                    }
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData>")
        if (merges.isNotEmpty()) {
            sb.append("<mergeCells count=\"${merges.size}\">")
            for (m in merges) sb.append("<mergeCell ref=\"$m\"/>")
            sb.append("</mergeCells>")
        }
        // Hyperlinks.
        val links = mutableListOf<Pair<String, String>>() // ref, target
        sheet.rows.forEachIndexed { ri, row -> row.cells.forEachIndexed { ci, cell -> cell.hyperlink?.let { links.add("${colLetter(ci)}${ri + 1}" to it) } } }
        if (links.isNotEmpty()) {
            sb.append("<hyperlinks>")
            for ((ref, target) in links) {
                if (target.startsWith("#")) sb.append("<hyperlink ref=\"$ref\" location=\"${esc(target.substring(1))}\"/>")
                else { val rid = "rIdLink${rels.size + 1}"; rels.add(Rel(rid, REL_HYPERLINK, target, external = true)); sb.append("<hyperlink ref=\"$ref\" r:id=\"$rid\"/>") }
            }
            sb.append("</hyperlinks>")
        }
        if (drawingRid != null) sb.append("<drawing r:id=\"$drawingRid\"/>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun numStr(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    /** Builds an xlsx drawing part (absolute anchors) for a sheet's floating images/charts, or null. */
    private fun xlsxDrawing(
        sheet: OdfSheet, sheetIdx: Int,
        media: MutableMap<String, ByteArray>, extraParts: MutableMap<String, String>
    ): Pair<String, List<Rel>>? {
        val frames = sheet.floating.filterIsInstance<OdfSlideElement.Frame>().filter { it.frame.image != null || it.frame.chart != null }
        if (frames.isEmpty()) return null
        val rels = mutableListOf<Rel>()
        val a = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val xdr = "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
        val sb = StringBuilder("$XMLDECL<xdr:wsDr xmlns:xdr=\"$xdr\" xmlns:a=\"$a\" xmlns:r=\"$NS_R\">")
        var shapeId = 1
        frames.forEachIndexed { i, el ->
            val f = el.frame
            sb.append("<xdr:absoluteAnchor><xdr:pos x=\"${emu(f.x)}\" y=\"${emu(f.y)}\"/><xdr:ext cx=\"${emu(f.width)}\" cy=\"${emu(f.height)}\"/>")
            if (f.chart != null) {
                val part = "xl/charts/chart_${sheetIdx}_$i.xml"
                extraParts[part] = chartXml(f.chart)
                val rid = "rIdc${rels.size + 1}"
                rels.add(Rel(rid, REL_CHART, "../charts/chart_${sheetIdx}_$i.xml"))
                sb.append("<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"${shapeId++}\" name=\"Chart $i\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>")
                sb.append("<xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm>")
                sb.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"$rid\"/></a:graphicData></a:graphic></xdr:graphicFrame>")
            } else if (f.image != null) {
                val ext = mediaExt(f.image.path)
                val name = "xl/media/image_${sheetIdx}_$i.$ext"
                media[name] = f.image.imageData
                val rid = "rIdi${rels.size + 1}"
                rels.add(Rel(rid, REL_IMAGE, "../media/image_${sheetIdx}_$i.$ext"))
                sb.append("<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"${shapeId++}\" name=\"Picture $i\"/><xdr:cNvPicPr/></xdr:nvPicPr>")
                sb.append("<xdr:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>")
                sb.append("<xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${emu(f.width)}\" cy=\"${emu(f.height)}\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>")
            }
            sb.append("<xdr:clientData/></xdr:absoluteAnchor>")
        }
        sb.append("</xdr:wsDr>")
        return sb.toString() to rels
    }

    /** Deduplicating xlsx style table (fonts/fills/borders/numFmts/cellXfs). */
    private class XlsxStyles {
        private data class FontKey(val bold: Boolean, val italic: Boolean, val color: Long?, val size: Float?)
        private data class XfKey(val numFmt: Int, val font: Int, val fill: Int, val border: Int, val halign: String?, val valign: String?, val wrap: Boolean, val rotation: Int)

        private val fonts = LinkedHashMap<FontKey, Int>().apply { put(FontKey(false, false, null, null), 0) }
        private val fills = LinkedHashMap<Long?, Int>().apply { put(null, 0); put(-1L, 1) } // 0 none, 1 gray125 placeholder
        private val borders = LinkedHashMap<OdfBorders?, Int>().apply { put(null, 0) }
        private val numFmts = LinkedHashMap<String, Int>()
        private val xfs = LinkedHashMap<XfKey, Int>().apply { put(XfKey(0, 0, 0, 0, null, null, false, 0), 0) }
        private var nextNumFmtId = 164

        fun styleIndex(cell: OdfCell): Int {
            val hasFmt = cell.bold || cell.italic || cell.textColor != null || cell.backgroundColor != null ||
                cell.borders != null || cell.numberFormat != null || cell.alignment != null ||
                cell.verticalAlign != null || cell.wrap || cell.textRotation != 0
            if (!hasFmt) return 0
            val fontId = if (cell.bold || cell.italic || cell.textColor != null) fonts.getOrPut(FontKey(cell.bold, cell.italic, cell.textColor, null)) { fonts.size } else 0
            val fillId = cell.backgroundColor?.let { c -> fills.getOrPut(c) { fills.size } } ?: 0
            val borderId = cell.borders?.let { b -> borders.getOrPut(b) { borders.size } } ?: 0
            val numFmtId = cell.numberFormat?.let { numFmtId(it) } ?: 0
            val halign = when (cell.alignment) { TextAlign.Center -> "center"; TextAlign.End, TextAlign.Right -> "right"; TextAlign.Start, TextAlign.Left -> "left"; TextAlign.Justify -> "justify"; else -> null }
            val key = XfKey(numFmtId, fontId, fillId, borderId, halign, cell.verticalAlign, cell.wrap, cell.textRotation)
            return xfs.getOrPut(key) { xfs.size }
        }

        private fun numFmtId(fmt: OdfNumberFormat): Int {
            val code = numFmtCode(fmt)
            builtinNumFmtId(code)?.let { return it }
            return numFmts.getOrPut(code) { nextNumFmtId++ }
        }

        fun toXml(): String {
            val sb = StringBuilder("$XMLDECL<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            if (numFmts.isNotEmpty()) {
                sb.append("<numFmts count=\"${numFmts.size}\">")
                for ((code, id) in numFmts) sb.append("<numFmt numFmtId=\"$id\" formatCode=\"${esc(code)}\"/>")
                sb.append("</numFmts>")
            }
            sb.append("<fonts count=\"${fonts.size}\">")
            for (f in fonts.keys) {
                sb.append("<font><sz val=\"${f.size ?: 11f}\"/><name val=\"Calibri\"/>")
                if (f.bold) sb.append("<b/>"); if (f.italic) sb.append("<i/>")
                f.color?.let { sb.append("<color rgb=\"FF${hex(it)}\"/>") }
                sb.append("</font>")
            }
            sb.append("</fonts>")
            sb.append("<fills count=\"${fills.size}\">")
            for (c in fills.keys) when (c) {
                null -> sb.append("<fill><patternFill patternType=\"none\"/></fill>")
                -1L -> sb.append("<fill><patternFill patternType=\"gray125\"/></fill>")
                else -> sb.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF${hex(c)}\"/><bgColor indexed=\"64\"/></patternFill></fill>")
            }
            sb.append("</fills>")
            sb.append("<borders count=\"${borders.size}\">")
            for (b in borders.keys) {
                if (b == null) { sb.append("<border><left/><right/><top/><bottom/><diagonal/></border>"); continue }
                sb.append("<border>")
                fun edge(name: String, v: String?) {
                    if (v == null) sb.append("<$name/>")
                    else { val c = OdfBorders.renderColor(v); sb.append("<$name style=\"thin\">${c?.let { "<color rgb=\"FF${hex(it)}\"/>" } ?: ""}</$name>") }
                }
                edge("left", b.left); edge("right", b.right); edge("top", b.top); edge("bottom", b.bottom); sb.append("<diagonal/>")
                sb.append("</border>")
            }
            sb.append("</borders>")
            sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
            sb.append("<cellXfs count=\"${xfs.size}\">")
            for (xf in xfs.keys) {
                val applyAlign = xf.halign != null || xf.valign != null || xf.wrap || xf.rotation != 0
                sb.append("<xf numFmtId=\"${xf.numFmt}\" fontId=\"${xf.font}\" fillId=\"${xf.fill}\" borderId=\"${xf.border}\" xfId=\"0\"")
                sb.append(" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyNumberFormat=\"1\"")
                if (applyAlign) {
                    sb.append(" applyAlignment=\"1\"><alignment")
                    xf.halign?.let { sb.append(" horizontal=\"$it\"") }
                    xf.valign?.let { sb.append(" vertical=\"${if (it == "middle") "center" else it}\"") }
                    if (xf.wrap) sb.append(" wrapText=\"1\"")
                    if (xf.rotation != 0) sb.append(" textRotation=\"${xf.rotation}\"")
                    sb.append("/></xf>")
                } else sb.append("/>")
            }
            sb.append("</cellXfs>")
            sb.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>")
            sb.append("</styleSheet>")
            return sb.toString()
        }
    }

    // --- ODF -> Excel conversions (number formats, formulas, addresses) ---

    private fun numFmtCode(fmt: OdfNumberFormat): String {
        if (fmt.isDate || fmt.isTime) {
            if (fmt.dateTimeTokens.isNotEmpty()) return fmt.dateTimeTokens.joinToString("") { tokenCode(it) }
            return if (fmt.isTime) "hh:mm:ss" else "yyyy-mm-dd"
        }
        if (fmt.isScientific) return "0" + decimalsCode(fmt.decimals ?: 2) + "E+00"
        if (fmt.isFraction) { val q = "?".repeat(fmt.fractionDenominatorDigits.coerceAtLeast(1)); return "# $q/$q" }
        val dec = fmt.decimals ?: 0
        val intPart = if (fmt.grouping) "#,##0" else "0"
        var code = intPart + decimalsCode(dec)
        if (fmt.percent) code += "%"
        fmt.currencySymbol?.let { code = "\"$it\"$code" }
        return code
    }

    private fun decimalsCode(dec: Int): String = if (dec > 0) "." + "0".repeat(dec) else ""

    private fun tokenCode(t: OdfNumberToken): String {
        val long = t.style == "long"
        return when (t.kind) {
            "year" -> if (long) "yyyy" else "yy"
            "month" -> if (t.textual) (if (long) "mmmm" else "mmm") else (if (long) "mm" else "m")
            "day" -> if (long) "dd" else "d"
            "day-of-week" -> if (long) "dddd" else "ddd"
            "hours" -> if (long) "hh" else "h"
            "minutes" -> if (long) "mm" else "m"
            "seconds" -> if (long) "ss" else "s"
            "am-pm" -> "AM/PM"
            "text" -> t.text ?: ""
            else -> ""
        }
    }

    private fun builtinNumFmtId(code: String): Int? = when (code) {
        "0" -> 1; "0.00" -> 2; "#,##0" -> 3; "#,##0.00" -> 4
        "0%" -> 9; "0.00%" -> 10; "0.00E+00" -> 11
        "mm-dd-yy" -> 14; "h:mm:ss" -> 21; "hh:mm:ss" -> 21
        else -> null
    }

    private val ODF_REF = Regex("\\[([^\\]]*)]")

    /** Converts an ODF OpenFormula ("of:=…") to an Excel A1 formula ("=…"). */
    private fun odfFormulaToExcel(of: String): String {
        var body = of.removePrefix("of:=").removePrefix("=")
        body = ODF_REF.replace(body) { m -> convertOdfRef(m.groupValues[1]) }
        return "=" + body.replace(';', ',')
    }

    private fun convertOdfRef(inner: String): String = inner.split(":").joinToString(":") { part ->
        val p = part.trim().removePrefix("$")
        if (p.startsWith(".")) p.substring(1) else p.replaceFirst(".", "!")
    }

    /** Converts an ODF address ("Sheet1.A1:Sheet1.D20") to an Excel address ("Sheet1!A1:D20"). */
    private fun odfAddrToExcel(addr: String): String = addr.split(" ").joinToString(",") { range ->
        range.split(":").joinToString(":") { part ->
            val p = part.removePrefix("$")
            if (p.contains(".")) {
                val sheet = p.substringBefore("."); val cell = p.substringAfter(".")
                if (sheet.isEmpty()) cell else "${sheet.removePrefix("$")}!$cell"
            } else p
        }
    }

    // ==================== PPTX ====================

    private fun buildPptx(slides: List<OdfSlide>, images: Map<String, ByteArray>): ByteArray {
        val deck = slides.ifEmpty { listOf(OdfSlide("Slide 1")) }
        val pkg = Pkg()
        val media = LinkedHashMap<String, ByteArray>()
        val extraParts = LinkedHashMap<String, String>()          // charts, notes slides
        val notesParts = LinkedHashMap<Int, String>()             // slide index -> notes part path
        val hasNotes = deck.any { it.notes.isNotEmpty() }

        val slideXmls = ArrayList<String>()
        val slideRels = ArrayList<List<Rel>>()
        deck.forEachIndexed { i, slide ->
            val rels = mutableListOf<Rel>(Rel("rIdLayout", "$NS_R/slideLayout", "../slideLayouts/slideLayout1.xml"))
            slideXmls.add(pptxSlide(slide, i, images, media, extraParts, rels))
            if (slide.notes.isNotEmpty()) {
                val notesPart = "ppt/notesSlides/notesSlide${i + 1}.xml"
                notesParts[i] = notesPart
                extraParts[notesPart] = pptxNotesSlide(slide)
                rels.add(Rel("rIdNotes", "$NS_R/notesSlide", "../notesSlides/notesSlide${i + 1}.xml"))
            }
            slideRels.add(rels)
        }

        val ctypes = StringBuilder(XMLDECL)
        ctypes.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        ctypes.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ctypes.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        for (ext in media.keys.map { it.substringAfterLast('.').lowercase() }.toSortedSet()) IMAGE_CONTENT_TYPES[ext]?.let { ctypes.append("<Default Extension=\"$ext\" ContentType=\"$it\"/>") }
        ctypes.append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>")
        ctypes.append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
        ctypes.append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
        ctypes.append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
        if (hasNotes) ctypes.append("<Override PartName=\"/ppt/notesMasters/notesMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml\"/>")
        for (i in deck.indices) ctypes.append("<Override PartName=\"/ppt/slides/slide${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
        for (part in extraParts.keys) {
            val ct = if (part.contains("/charts/")) "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"
            else "application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"
            ctypes.append("<Override PartName=\"/$part\" ContentType=\"$ct\"/>")
        }
        ctypes.append("</Types>")

        val pres = StringBuilder(XMLDECL)
        pres.append("<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$NS_R\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
        pres.append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rIdMaster\"/></p:sldMasterIdLst>")
        if (hasNotes) pres.append("<p:notesMasterIdLst><p:notesMasterId r:id=\"rIdNotesMaster\"/></p:notesMasterIdLst>")
        pres.append("<p:sldIdLst>")
        deck.indices.forEach { pres.append("<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 1}\"/>") }
        pres.append("</p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"6858000\" type=\"screen4x3\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")

        val presRels = mutableListOf(Rel("rIdMaster", "$NS_R/slideMaster", "slideMasters/slideMaster1.xml"))
        deck.indices.forEach { presRels.add(Rel("rId${it + 1}", "$NS_R/slide", "slides/slide${it + 1}.xml")) }
        presRels.add(Rel("rIdTheme", "$NS_R/theme", "theme/theme1.xml"))
        if (hasNotes) presRels.add(Rel("rIdNotesMaster", "$NS_R/notesMaster", "notesMasters/notesMaster1.xml"))

        pkg.text("[Content_Types].xml", ctypes.toString())
        pkg.text("_rels/.rels", relsXml(listOf(Rel("rId1", "$NS_R/officeDocument", "ppt/presentation.xml"))))
        pkg.text("ppt/presentation.xml", pres.toString())
        pkg.text("ppt/_rels/presentation.xml.rels", relsXml(presRels))
        pkg.text("ppt/theme/theme1.xml", PPTX_THEME)
        pkg.text("ppt/slideMasters/slideMaster1.xml", PPTX_MASTER)
        pkg.text("ppt/slideMasters/_rels/slideMaster1.xml.rels", relsXml(listOf(
            Rel("rIdLayout", "$NS_R/slideLayout", "../slideLayouts/slideLayout1.xml"),
            Rel("rIdTheme", "$NS_R/theme", "../theme/theme1.xml"))))
        pkg.text("ppt/slideLayouts/slideLayout1.xml", PPTX_LAYOUT)
        pkg.text("ppt/slideLayouts/_rels/slideLayout1.xml.rels", relsXml(listOf(
            Rel("rIdMaster", "$NS_R/slideMaster", "../slideMasters/slideMaster1.xml"))))
        if (hasNotes) {
            pkg.text("ppt/notesMasters/notesMaster1.xml", PPTX_NOTES_MASTER)
            pkg.text("ppt/notesMasters/_rels/notesMaster1.xml.rels", relsXml(listOf(Rel("rIdTheme", "$NS_R/theme", "../theme/theme1.xml"))))
        }
        slideXmls.forEachIndexed { i, xml ->
            pkg.text("ppt/slides/slide${i + 1}.xml", xml)
            pkg.text("ppt/slides/_rels/slide${i + 1}.xml.rels", relsXml(slideRels[i]))
        }
        for ((idx, part) in notesParts) {
            pkg.text("ppt/notesSlides/_rels/${part.substringAfterLast('/')}.rels", relsXml(listOf(
                Rel("rIdSlide", "$NS_R/slide", "../slides/slide${idx + 1}.xml"),
                Rel("rIdNotesMaster", "$NS_R/notesMaster", "../notesMasters/notesMaster1.xml"))))
        }
        for ((name, xml) in extraParts) pkg.text(name, xml)
        for ((name, data) in media) pkg.bytes(name, data)
        return pkg.zip()
    }

    private fun pptxSlide(
        slide: OdfSlide, slideIdx: Int, images: Map<String, ByteArray>,
        media: MutableMap<String, ByteArray>, extraParts: MutableMap<String, String>, rels: MutableList<Rel>
    ): String {
        val sb = StringBuilder(XMLDECL)
        sb.append("<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$NS_R\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
        sb.append("<p:cSld${slide.name.takeIf { it.isNotBlank() }?.let { " name=\"${esc(it)}\"" } ?: ""}>")
        // Background.
        val bg = pptxBackground(slide, images, media, rels)
        if (bg != null) sb.append(bg)
        sb.append("<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>")
        var shapeId = 2
        var autoY = 300000L
        for (el in slide.elements) {
            val xml = pptxElement(el, shapeId, slideIdx, autoY, media, extraParts, rels)
            if (xml != null) { sb.append(xml.first); shapeId++; autoY = xml.second }
        }
        sb.append("</p:spTree></p:cSld>")
        // Transition.
        pptxTransition(slide)?.let { sb.append(it) }
        sb.append("<p:clrMapOvr><a:overrideClrMapping bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/></p:clrMapOvr></p:sld>")
        return sb.toString()
    }

    private fun pptxBackground(slide: OdfSlide, images: Map<String, ByteArray>, media: MutableMap<String, ByteArray>, rels: MutableList<Rel>): String? {
        if (slide.backgroundImagePath != null) {
            val bytes = images.entries.firstOrNull { it.key.endsWith(slide.backgroundImagePath.substringAfterLast('/')) }?.value
            if (bytes != null) {
                val ext = mediaExt(slide.backgroundImagePath)
                val name = "ppt/media/bg${media.size + 1}.$ext"
                media[name] = bytes
                val rid = "rIdBg${rels.size + 1}"
                rels.add(Rel(rid, REL_IMAGE, "../media/${name.substringAfterLast('/')}"))
                return "<p:bg><p:bgPr><a:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></a:blipFill><a:effectLst/></p:bgPr></p:bg>"
            }
        }
        slide.backgroundColor?.let { return "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"${hex(it)}\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>" }
        return null
    }

    private fun pptxTransition(slide: OdfSlide): String? {
        val type = slide.transitionType ?: return null
        val spd = when (slide.transitionSpeed) { "slow" -> "slow"; "fast" -> "fast"; "medium" -> "med"; else -> "med" }
        val el = when (type) {
            "fade" -> "<p:fade/>"; "wipe" -> "<p:wipe/>"; "dissolve" -> "<p:dissolve/>"; "push" -> "<p:push/>"
            "cover" -> "<p:cover/>"; "cut" -> "<p:cut/>"; "split" -> "<p:split/>"; "blinds" -> "<p:blinds/>"
            "checkerboard" -> "<p:checker/>"; "circle" -> "<p:circle/>"; "wheel" -> "<p:wheel/>"; else -> "<p:fade/>"
        }
        return "<p:transition xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" spd=\"$spd\">$el</p:transition>"
    }

    /** Emits a slide element; returns (xml, nextAutoY) or null. */
    private fun pptxElement(
        el: OdfSlideElement, id: Int, slideIdx: Int, autoY: Long,
        media: MutableMap<String, ByteArray>, extraParts: MutableMap<String, String>, rels: MutableList<Rel>
    ): Pair<String, Long>? {
        val b = el.bounds()
        val x = if (b[0] > 0f) emu(b[0]) else 457200L
        val y = if (b[1] > 0f) emu(b[1]) else autoY
        val w = if (b[2] > 0f) emu(b[2]) else 8229600L
        val h = if (b[3] > 0f) emu(b[3]) else 1000000L
        val next = y + h + 100000L
        when (el) {
            is OdfSlideElement.Frame -> {
                val f = el.frame
                when {
                    f.image != null -> {
                        val ext = mediaExt(f.image.path)
                        val name = "ppt/media/image_${slideIdx}_$id.$ext"
                        media[name] = f.image.imageData
                        val rid = "rIdImg${rels.size + 1}"
                        rels.add(Rel(rid, REL_IMAGE, "../media/${name.substringAfterLast('/')}"))
                        return pptxPic(id, rid, x, y, w, h) to next
                    }
                    f.chart != null -> {
                        val part = "ppt/charts/chart_${slideIdx}_$id.xml"
                        extraParts[part] = chartXml(f.chart)
                        val rid = "rIdChart${rels.size + 1}"
                        rels.add(Rel(rid, REL_CHART, "../charts/chart_${slideIdx}_$id.xml"))
                        return pptxChartFrame(id, rid, x, y, w, h) to next
                    }
                    else -> return pptxSpShape(id, x, y, w, h, "rect", f.fillColor, f.strokeColor, f.strokeWidth, f.fillGradient, false, 0f, f.paragraphs) to next
                }
            }
            is OdfSlideElement.Shape -> {
                val s = el.shape
                val geom = when (s) {
                    is OdfShape.Ellipse -> "ellipse"
                    is OdfShape.Rect -> if (s.cornerRadius > 0f) "roundRect" else "rect"
                    is OdfShape.Line -> "line"
                    else -> "rect"
                }
                return pptxSpShape(id, x, y, w, h, geom, s.fillColor, s.strokeColor, s.strokeWidth, s.fillGradient, s.strokeDashed, s.rotationDegrees, s.text) to next
            }
        }
    }

    private fun pptxPic(id: Int, rid: String, x: Long, y: Long, w: Long, h: Long): String =
        "<p:pic><p:nvPicPr><p:cNvPr id=\"$id\" name=\"Picture $id\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>" +
            "<p:blipFill><a:blip r:embed=\"$rid\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"

    private fun pptxChartFrame(id: Int, rid: String, x: Long, y: Long, w: Long, h: Long): String =
        "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"$id\" name=\"Chart $id\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>" +
            "<p:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></p:xfrm>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"$rid\"/></a:graphicData></a:graphic></p:graphicFrame>"

    private fun pptxSpShape(
        id: Int, x: Long, y: Long, w: Long, h: Long, geom: String,
        fill: Long?, stroke: Long?, strokeWidth: Float?, gradient: OdfGradient?, dashed: Boolean, rotation: Float,
        paras: List<OdfParagraph>
    ): String {
        val sb = StringBuilder("<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"Shape $id\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>")
        val rot = if (rotation != 0f) " rot=\"${(rotation * 60000).toInt()}\"" else ""
        sb.append("<a:xfrm$rot><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm>")
        sb.append("<a:prstGeom prst=\"$geom\"><a:avLst/></a:prstGeom>")
        when {
            gradient != null -> sb.append("<a:gradFill><a:gsLst><a:gs pos=\"0\"><a:srgbClr val=\"${hex(gradient.startColor)}\"/></a:gs><a:gs pos=\"100000\"><a:srgbClr val=\"${hex(gradient.endColor)}\"/></a:gs></a:gsLst><a:lin ang=\"${(gradient.angle * 60000).toInt()}\" scaled=\"1\"/></a:gradFill>")
            fill != null -> sb.append("<a:solidFill><a:srgbClr val=\"${hex(fill)}\"/></a:solidFill>")
            else -> sb.append("<a:noFill/>")
        }
        if (stroke != null || strokeWidth != null) {
            sb.append("<a:ln${strokeWidth?.let { " w=\"${emu(it)}\"" } ?: ""}>")
            stroke?.let { sb.append("<a:solidFill><a:srgbClr val=\"${hex(it)}\"/></a:solidFill>") }
            if (dashed) sb.append("<a:prstDash val=\"dash\"/>")
            sb.append("</a:ln>")
        }
        sb.append("</p:spPr>")
        sb.append(pptxTxBody(paras))
        sb.append("</p:sp>")
        return sb.toString()
    }

    private fun pptxTxBody(paras: List<OdfParagraph>): String {
        val sb = StringBuilder("<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/>")
        if (paras.isEmpty()) sb.append("<a:p/>")
        for (p in paras) {
            val algn = when (p.alignment) { TextAlign.Center -> " algn=\"ctr\""; TextAlign.End, TextAlign.Right -> " algn=\"r\""; TextAlign.Justify -> " algn=\"just\""; else -> "" }
            val lvl = if (p.listLevel > 0) " lvl=\"${p.listLevel}\"" else ""
            sb.append("<a:p>")
            if (algn.isNotEmpty() || lvl.isNotEmpty() || p.style == ParagraphStyle.LIST_ITEM) {
                sb.append("<a:pPr$algn$lvl>")
                when {
                    p.style != ParagraphStyle.LIST_ITEM -> {}
                    p.listType == ListType.NUMBERED -> sb.append("<a:buAutoNum type=\"${pptxAutoNum(p.listNumberFormat)}\"/>")
                    else -> sb.append("<a:buChar char=\"${esc(p.listBulletChar)}\"/>")
                }
                sb.append("</a:pPr>")
            }
            for (span in p.spans) {
                if (span.text.isEmpty()) continue
                sb.append(pptxRun(span))
            }
            sb.append("</a:p>")
        }
        sb.append("</p:txBody>")
        return sb.toString()
    }

    private fun pptxRun(span: OdfSpan): String {
        val attrs = StringBuilder(" lang=\"en-US\"")
        if (span.bold) attrs.append(" b=\"1\"")
        if (span.italic) attrs.append(" i=\"1\"")
        if (span.underline) attrs.append(" u=\"sng\"")
        if (span.strikethrough) attrs.append(" strike=\"sngStrike\"")
        span.fontSize?.let { attrs.append(" sz=\"${(it * 100).toInt()}\"") }
        if (span.superscript) attrs.append(" baseline=\"30000\"")
        if (span.subscript) attrs.append(" baseline=\"-25000\"")
        val rpr = StringBuilder("<a:rPr$attrs>")
        span.color?.let { rpr.append("<a:solidFill><a:srgbClr val=\"${hex(it)}\"/></a:solidFill>") }
        span.fontFamily?.let { rpr.append("<a:latin typeface=\"${esc(it)}\"/>") }
        span.href?.let { if (!it.startsWith("#")) rpr.append("<a:hlinkClick xmlns:r=\"$NS_R\"/>") }
        rpr.append("</a:rPr>")
        return "<a:r>$rpr<a:t>${esc(span.text)}</a:t></a:r>"
    }

    private fun pptxAutoNum(format: String): String = when (format) {
        "a" -> "alphaLcPeriod"; "A" -> "alphaUcPeriod"; "i" -> "romanLcPeriod"; "I" -> "romanUcPeriod"; else -> "arabicPeriod"
    }

    private fun pptxNotesSlide(slide: OdfSlide): String {
        val sb = StringBuilder(XMLDECL)
        sb.append("<p:notes xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$NS_R\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
        sb.append("<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>")
        sb.append("<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/>")
        sb.append(pptxTxBody(slide.notes))
        sb.append("</p:sp></p:spTree></p:cSld><p:clrMapOvr><a:overrideClrMapping bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/></p:clrMapOvr></p:notes>")
        return sb.toString()
    }

    private const val PPTX_THEME = XMLDECL +
        "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Office\">" +
        "<a:themeElements><a:clrScheme name=\"Office\">" +
        "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>" +
        "</a:clrScheme>" +
        "<a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
        "<a:fmtScheme name=\"Office\">" +
        "<a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
        "<a:lnStyleLst><a:ln w=\"6350\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln><a:ln w=\"19050\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
        "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
        "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst>" +
        "</a:fmtScheme></a:themeElements></a:theme>"

    private val PPTX_MASTER = XMLDECL +
        "<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$NS_R\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
        "<p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>" +
        "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>" +
        "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rIdLayout\"/></p:sldLayoutIdLst></p:sldMaster>"

    private val PPTX_LAYOUT = XMLDECL +
        "<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$NS_R\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" preserve=\"1\">" +
        "<p:cSld name=\"Blank\"><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld>" +
        "<p:clrMapOvr><a:overrideClrMapping bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/></p:clrMapOvr>" +
        "</p:sldLayout>"

    private val PPTX_NOTES_MASTER = XMLDECL +
        "<p:notesMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"$NS_R\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
        "<p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>" +
        "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>" +
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Notes Placeholder\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr><p:spPr/>" +
        "<p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody></p:sp>" +
        "</p:spTree></p:cSld>" +
        "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
        "</p:notesMaster>"
}
