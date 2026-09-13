package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.*
import org.xmlpull.v1.XmlPullParser

internal object OoxmlXlsxHelper {

    fun parseDrawings(
        pkg: OoxmlPackage, part: String, rels: Map<String, OoxmlPackage.Rel>, drawingRid: String?,
        theme: OoxmlTheme, colWidths: Map<Int, Float>, rowHeights: List<Float?>
    ): List<OdfSlideElement> {
        val drawingPart = drawingRid?.let { rels[it]?.target }
            ?: rels.values.firstOrNull { it.type?.endsWith("drawing") == true }?.target
            ?: return emptyList()
        val xml = pkg.entries[drawingPart] ?: return emptyList()
        val drawingRels = pkg.relsFor(drawingPart)
        val parser = OoxmlXml.newParser(xml)
        val out = mutableListOf<OdfSlideElement>()
        var fromCol = 0; var fromRow = 0; var fromColOff = 0L; var fromRowOff = 0L
        var toCol = 0; var toRow = 0
        var inFrom = false; var inTo = false
        var embed: String? = null; var chartRid: String? = null
        var extCx = 0L; var extCy = 0L
        val relsNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        var e = parser.eventType
        fun flush() {
            val x = colX(fromCol, colWidths) + OoxmlUnits.emuToPx(fromColOff)
            val y = rowY(fromRow, rowHeights) + OoxmlUnits.emuToPx(fromRowOff)
            val w = if (extCx > 0) OoxmlUnits.emuToPx(extCx) else (colX(toCol, colWidths) - colX(fromCol, colWidths)).coerceAtLeast(64f)
            val h = if (extCy > 0) OoxmlUnits.emuToPx(extCy) else (rowY(toRow, rowHeights) - rowY(fromRow, rowHeights)).coerceAtLeast(48f)
            if (chartRid != null) {
                val target = drawingRels[chartRid]?.target
                val chart = target?.let { pkg.entries[it] }?.let { OoxmlChart.parse(it, theme) }
                if (chart != null) out.add(OdfSlideElement.Frame(OdfFrame(x, y, w, h, emptyList(), chart = chart)))
            } else if (embed != null) {
                val target = drawingRels[embed]?.target
                val bytes = target?.let { pkg.mediaBytes(it) }
                if (bytes != null) {
                    val path = "media/${target.substringAfterLast('/')}"
                    out.add(OdfSlideElement.Frame(OdfFrame(x, y, w, h, emptyList(), image = OdfImage(path, bytes, w, h))))
                }
            }
            embed = null; chartRid = null; extCx = 0; extCy = 0
        }
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "from" -> inFrom = true
                "to" -> inTo = true
                "col" -> { val v = OoxmlXml.readElementText(parser, "col").trim().toIntOrNull() ?: 0; if (inFrom) fromCol = v else if (inTo) toCol = v }
                "row" -> { val v = OoxmlXml.readElementText(parser, "row").trim().toIntOrNull() ?: 0; if (inFrom) fromRow = v else if (inTo) toRow = v }
                "colOff" -> { val v = OoxmlXml.readElementText(parser, "colOff").trim().toLongOrNull() ?: 0L; if (inFrom) fromColOff = v }
                "rowOff" -> { val v = OoxmlXml.readElementText(parser, "rowOff").trim().toLongOrNull() ?: 0L; if (inFrom) fromRowOff = v }
                "ext" -> { extCx = OoxmlXml.attr(parser, "cx")?.toLongOrNull() ?: 0L; extCy = OoxmlXml.attr(parser, "cy")?.toLongOrNull() ?: 0L }
                "blip" -> embed = OoxmlXml.attrNs(parser, relsNs, "embed") ?: OoxmlXml.attr(parser, "embed")
                "chart" -> chartRid = OoxmlXml.attrNs(parser, relsNs, "id") ?: OoxmlXml.attr(parser, "id")
            } else if (e == XmlPullParser.END_TAG) when (parser.name) {
                "from" -> inFrom = false
                "to" -> inTo = false
                "oneCellAnchor", "twoCellAnchor", "absoluteAnchor" -> flush()
            }
            e = parser.next()
        }
        return out
    }

    private fun colX(col: Int, widths: Map<Int, Float>): Float {
        var x = 0f
        for (c in 0 until col) x += widths[c] ?: 64f
        return x
    }

    private fun rowY(row: Int, heights: List<Float?>): Float {
        var y = 0f
        for (r in 0 until row) y += heights.getOrNull(r) ?: 20f
        return y
    }
}
