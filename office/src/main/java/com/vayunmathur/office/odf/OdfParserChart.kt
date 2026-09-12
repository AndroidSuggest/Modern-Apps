package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.*
import org.xmlpull.v1.XmlPullParser

// --- Embedded charts ---

internal fun OdfParser.parseChart(xml: String): OdfChart? {
    val chartStyles = parseChartStyles(xml)
    val parser = newParser(xml)
    var eventType = parser.eventType
    var chartClass: String? = null
    var stacked = false
    val seriesStyleNames = mutableListOf<String?>()
    var inTable = false
    var inCell = false
    var curRow: MutableList<Pair<String, Float?>>? = null
    var cellText = StringBuilder()
    var cellValue: Float? = null
    val rows = mutableListOf<List<Pair<String, Float?>>>()
    // Chart styling round-trip: legend, title/subtitle, axis titles. (Priority 8)
    var legend = false
    var title: String? = null; var subtitle: String? = null
    var xAxisTitle: String? = null; var yAxisTitle: String? = null
    var axisDim: String? = null
    var captureTarget: String? = null
    val titleBuf = StringBuilder()

    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "chart" -> if (chartClass == null) chartClass = getAttr(parser, "class")
                "legend" -> legend = true
                "plot-area" -> {
                    if (getAttr(parser, "stacked") == "true" || getAttr(parser, "percentage") == "true") stacked = true
                }
                "chart-properties" -> {
                    if (getAttr(parser, "stacked") == "true" || getAttr(parser, "percentage") == "true") stacked = true
                }
                "series" -> seriesStyleNames.add(getAttr(parser, "style-name"))
                "axis" -> axisDim = getAttr(parser, "dimension")
                "title" -> { captureTarget = when (axisDim) { "x" -> "x"; "y" -> "y"; else -> "main" }; titleBuf.setLength(0) }
                "subtitle" -> { captureTarget = "sub"; titleBuf.setLength(0) }
                "table" -> if (getAttr(parser, "name") == "local-table") inTable = true
                "table-row" -> if (inTable) curRow = mutableListOf()
                "table-cell" -> if (inTable && curRow != null) { inCell = true; cellText = StringBuilder(); cellValue = getAttr(parser, "value")?.toFloatOrNull() }
            }
            XmlPullParser.TEXT -> if (inCell) cellText.append(parser.text) else if (captureTarget != null) titleBuf.append(parser.text)
            XmlPullParser.END_TAG -> when (parser.name) {
                "title" -> { val t = titleBuf.toString().trim().ifEmpty { null }; when (captureTarget) { "x" -> xAxisTitle = t; "y" -> yAxisTitle = t; "main" -> title = t }; captureTarget = null }
                "subtitle" -> { subtitle = titleBuf.toString().trim().ifEmpty { null }; captureTarget = null }
                "axis" -> axisDim = null
                "table" -> if (inTable) inTable = false
                "table-cell" -> if (inCell) { curRow?.add(cellText.toString().trim() to cellValue); inCell = false }
                "table-row" -> if (curRow != null) { rows.add(curRow); curRow = null }
            }
        }
        eventType = parser.next()
    }
    if (rows.size < 2) return null
    val header = rows[0]
    val seriesCount = (header.size - 1).coerceAtLeast(0)
    if (seriesCount == 0) return null
    val seriesNames = (1..seriesCount).map { header.getOrNull(it)?.first?.ifEmpty { "Series $it" } ?: "Series $it" }
    val seriesValues = List(seriesCount) { mutableListOf<Float>() }
    val categories = mutableListOf<String>()
    for (i in 1 until rows.size) {
        val r = rows[i]
        categories.add(r.getOrNull(0)?.first ?: "")
        for (j in 0 until seriesCount) {
            val cell = r.getOrNull(j + 1)
            seriesValues[j].add(cell?.second ?: cell?.first?.toFloatOrNull() ?: 0f)
        }
    }
    val type = when {
        chartClass?.contains("line") == true -> ChartType.LINE
        chartClass?.contains("scatter") == true -> ChartType.SCATTER
        chartClass?.contains("ring") == true -> ChartType.DONUT
        chartClass?.contains("circle") == true -> ChartType.PIE
        chartClass?.contains("radar") == true -> ChartType.RADAR
        chartClass?.contains("bubble") == true -> ChartType.BUBBLE
        chartClass?.contains("area") == true -> ChartType.AREA
        stacked && chartClass?.contains("bar") == true -> ChartType.STACKED_BAR
        else -> ChartType.BAR
    }
    val series = seriesNames.mapIndexed { idx, n ->
        val styleAttrs = seriesStyleNames.getOrNull(idx)?.let { chartStyles[it] }
        OdfChartSeries(n, seriesValues[idx], color = styleAttrs?.first, dataLabels = styleAttrs?.second ?: false)
    }
    return if (categories.isEmpty()) null else OdfChart(type, categories, series, title, subtitle, legend, xAxisTitle, yAxisTitle, stacked = stacked)
}

/** Parses chart automatic styles: chart style-name -> (fill color, data-labels enabled). (Phase 5) */
internal fun OdfParser.parseChartStyles(xml: String): Map<String, Pair<Long?, Boolean>> {
    val map = mutableMapOf<String, Pair<Long?, Boolean>>()
    val parser = newParser(xml)
    var e = parser.eventType
    var curName: String? = null
    var fill: Long? = null
    var dataLabels = false
    val depthStack = ArrayDeque<Int>()
    while (e != XmlPullParser.END_DOCUMENT) {
        when (e) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "style" -> if (getAttr(parser, "family") == "chart" || getAttr(parser, "family") == null) {
                    getAttr(parser, "name")?.let { curName = it; fill = null; dataLabels = false; depthStack.addLast(parser.depth) }
                }
                "graphic-properties" -> if (curName != null) getAttr(parser, "fill-color")?.let { fill = parseColor(it) }
                "chart-properties" -> if (curName != null) {
                    val dln = getAttr(parser, "data-label-number")
                    if ((dln != null && dln != "none") || getAttr(parser, "data-label-text") == "true") dataLabels = true
                }
            }
            XmlPullParser.END_TAG -> if (parser.name == "style" && curName != null && depthStack.isNotEmpty() && parser.depth == depthStack.last()) {
                depthStack.removeLast()
                map[curName] = fill to dataLabels
                curName = null
            }
        }
        e = parser.next()
    }
    return map
}

internal fun OdfParser.parseFormulaText(xml: String): String? {
    if (!xml.contains("math")) return null
    val ann = Regex("<annotation[^>]*>(.*?)</annotation>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
    if (!ann.isNullOrBlank()) return unescapeXml(ann.trim())
    var toks = Regex("<m:(mi|mn|mo)[^>]*>(.*?)</m:(mi|mn|mo)>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[2] }.toList()
    if (toks.isEmpty()) toks = Regex("<(mi|mn|mo)[^>]*>(.*?)</(mi|mn|mo)>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[2] }.toList()
    val joined = toks.joinToString(" ").trim()
    return if (joined.isBlank()) null else unescapeXml(joined)
}

internal fun OdfParser.unescapeXml(s: String): String = s
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&apos;", "'").replace("&amp;", "&")
