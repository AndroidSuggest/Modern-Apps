package com.vayunmathur.office.odf

import android.util.Base64
import com.vayunmathur.library.ui.odf.*
import org.xmlpull.v1.XmlPullParser

// --- Presentation / Drawing ---

internal fun OdfParser.parsePresentation(
    xml: String, styles: Map<String, StyleInfo>,
    title: String, metadata: OdfMetadata, images: Map<String, ByteArray>,
    objectContents: Map<String, String> = emptyMap()
): OdfDocument.Presentation =
    OdfDocument.Presentation(title, parseSlides(xml, styles, images, objectContents), metadata, images)

internal fun OdfParser.parseDrawing(
    xml: String, styles: Map<String, StyleInfo>,
    title: String, metadata: OdfMetadata, images: Map<String, ByteArray>,
    objectContents: Map<String, String> = emptyMap()
): OdfDocument.Drawing =
    OdfDocument.Drawing(title, parseSlides(xml, styles, images, objectContents), metadata, images)

internal fun OdfParser.parseSlides(xml: String, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>, objectContents: Map<String, String> = emptyMap()): List<OdfSlide> {
    val slides = mutableListOf<OdfSlide>()
    val parser = newParser(xml)
    var eventType = parser.eventType

    while (eventType != XmlPullParser.END_DOCUMENT) {
        if (eventType == XmlPullParser.START_TAG && parser.name == "page" && parser.namespace?.contains("draw") == true) {
            val name = getAttr(parser, "name") ?: "Slide ${slides.size + 1}"
            val drawStyleName = getAttr(parser, "style-name")
            val masterName = getAttr(parser, "master-page-name")
            val resolved = resolveStyle(drawStyleName, styles)
            val result = parseSlideContent(parser, styles, images, objectContents)
            slides.add(OdfSlide(
                name = name, elements = result.elements,
                backgroundColor = resolved.drawFillColor, notes = result.notes,
                transitionType = resolved.transitionType, transitionSpeed = resolved.transitionSpeed,
                masterName = masterName
            ))
        }
        eventType = parser.next()
    }
    return slides
}

internal data class SlideParseResult(val elements: List<OdfSlideElement>, val notes: List<OdfParagraph>)

internal fun OdfParser.parseSlideContent(parser: XmlPullParser, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>, objectContents: Map<String, String> = emptyMap()): SlideParseResult {
    val elements = mutableListOf<OdfSlideElement>()
    val notes = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) when (parser.name) {
            "frame" -> elements.add(OdfSlideElement.Frame(parseSingleFrame(parser, styles, images, objectContents)))
            "rect" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "rect")))
            "ellipse" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "ellipse")))
            "line" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "line")))
            "custom-shape" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "custom-shape")))
            "polyline" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "polyline")))
            "polygon" -> elements.add(OdfSlideElement.Shape(parseShape(parser, styles, "polygon")))
            "path" -> elements.add(OdfSlideElement.Shape(parsePathShape(parser, styles)))
            "notes" -> {
                val noteDepth = parser.depth
                var noteEvent = parser.next()
                while (!(noteEvent == XmlPullParser.END_TAG && parser.depth == noteDepth)) {
                    if (noteEvent == XmlPullParser.START_TAG && parser.name == "frame") {
                        val frame = parseSingleFrame(parser, styles, images, objectContents)
                        for (para in frame.paragraphs) {
                            if (para.spans.isNotEmpty()) notes.add(para)
                        }
                    }
                    noteEvent = parser.next()
                }
            }
        }
        eventType = parser.next()
    }
    return SlideParseResult(elements, notes)
}

internal fun OdfParser.parseSingleFrame(parser: XmlPullParser, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>, objectContents: Map<String, String> = emptyMap()): OdfFrame {
    val x = parseDimension(getAttr(parser, "x"))
    val y = parseDimension(getAttr(parser, "y"))
    val w = parseDimension(getAttr(parser, "width"))
    val h = parseDimension(getAttr(parser, "height"))
    val rot = parseRotationDegrees(getAttr(parser, "transform"))
    val clip = parseClip(getAttr(parser, "clip"))
    val anchor = getAttr(parser, "anchor-type") ?: ""
    val styleName = getAttr(parser, "style-name")
    val resolved = resolveStyle(styleName, styles)

    val paragraphs = mutableListOf<OdfParagraph>()
    var image: OdfImage? = null
    var chart: OdfChart? = null
    var altTitle: String? = null
    var altDesc: String? = null
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) when (parser.name) {
            "title", "desc" -> if (parser.namespace?.contains("svg") == true) {
                val d = parser.depth; val sb = StringBuilder(); var ev = parser.next()
                while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                    if (ev == XmlPullParser.TEXT) sb.append(parser.text)
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    ev = parser.next()
                }
                val t = sb.toString().trim().ifEmpty { null }
                if (parser.name == "title") altTitle = t else altDesc = t
            }
            "text-box" -> {
                val boxDepth = parser.depth
                var boxEvent = parser.next()
                while (!(boxEvent == XmlPullParser.END_TAG && parser.depth == boxDepth)) {
                    if (boxEvent == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
                        val spans = parseInlineContent(parser, "p", styles, images)
                        if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans))
                    } else if (boxEvent == XmlPullParser.START_TAG && parser.name == "list") {
                        parseListInFrame(parser, styles, images, paragraphs)
                    }
                    boxEvent = parser.next()
                }
            }
            "object" -> {
                // Embedded chart object referenced by ./Object N. (A8)
                val href = getAttr(parser, "href")?.removePrefix("./")
                val xml = href?.let { objectContents["$it/content.xml"] }
                if (xml != null) parseChart(xml)?.let { chart = it }
            }
            "image" -> {
                val href = getAttr(parser, "href")
                if (href != null && images.containsKey(href)) {
                    val bytes = images[href]!!
                    val (nw, nh) = decodeNaturalSize(bytes)
                    image = OdfImage(path = href, imageData = bytes, width = w, height = h, anchorType = anchor, rotationDegrees = rot, naturalWidthPx = nw, naturalHeightPx = nh, opacityPercent = resolved.imageOpacity ?: 100f, colorMode = resolved.imageColorMode)
                }
                val imgDepth = parser.depth
                var imgEvent = parser.next()
                while (!(imgEvent == XmlPullParser.END_TAG && parser.depth == imgDepth)) {
                    if (imgEvent == XmlPullParser.START_TAG && parser.name == "binary-data") {
                        imgEvent = parser.next()
                        if (imgEvent == XmlPullParser.TEXT) {
                            try {
                                val bytes = Base64.decode(parser.text.trim(), Base64.DEFAULT)
                                val (nw, nh) = decodeNaturalSize(bytes)
                                image = OdfImage(path = "inline", imageData = bytes, width = w, height = h, anchorType = anchor, rotationDegrees = rot, naturalWidthPx = nw, naturalHeightPx = nh, opacityPercent = resolved.imageOpacity ?: 100f, colorMode = resolved.imageColorMode)
                            } catch (_: Exception) { }
                        }
                    }
                    imgEvent = parser.next()
                }
            }
            "p" -> if (parser.namespace?.contains("text") == true) {
                val spans = parseInlineContent(parser, "p", styles, images)
                if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans))
            }
        }
        eventType = parser.next()
    }
    if (image != null) {
        // Prefer the legacy inline percent clip; otherwise resolve absolute lengths from the graphic style. (A7)
        val frac = clip ?: parseClipLengths(resolved.clip, image.naturalWidthPx, image.naturalHeightPx)
        if (frac != null) image = image.copy(cropLeftPct = frac[0], cropTopPct = frac[1], cropRightPct = frac[2], cropBottomPct = frac[3])
        if (altTitle != null || altDesc != null) image = image.copy(altTitle = altTitle, altDesc = altDesc)
    }
    return OdfFrame(x, y, w, h, paragraphs, image, chart = chart, fillColor = resolved.drawFillColor, strokeColor = resolved.drawStrokeColor, strokeWidth = resolved.drawStrokeWidth, fillGradient = resolved.fillGradientName?.let { gradientDefs[it] })
}

internal fun OdfParser.parseListInFrame(parser: XmlPullParser, styles: Map<String, StyleInfo>, images: Map<String, ByteArray>, paragraphs: MutableList<OdfParagraph>) {
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
            val spans = parseInlineContent(parser, "p", styles, images)
            if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans, ParagraphStyle.LIST_ITEM, listLevel = 1))
        } else if (eventType == XmlPullParser.START_TAG && parser.name == "list") {
            parseListInFrame(parser, styles, images, paragraphs)
        }
        eventType = parser.next()
    }
}

// --- Shapes ---

internal fun OdfParser.parseShape(parser: XmlPullParser, styles: Map<String, StyleInfo>, shapeName: String): OdfShape {
    // draw:line uses svg:x1/y1/x2/y2; all other shapes use svg:x/y/width/height.
    val isLine = shapeName == "line"
    val x = parseDimension(getAttr(parser, if (isLine) "x1" else "x"))
    val y = parseDimension(getAttr(parser, if (isLine) "y1" else "y"))
    val x2 = if (isLine) parseDimension(getAttr(parser, "x2")) else 0f
    val y2 = if (isLine) parseDimension(getAttr(parser, "y2")) else 0f
    val w = if (isLine) kotlin.math.abs(x2 - x) else parseDimension(getAttr(parser, "width"))
    val h = if (isLine) kotlin.math.abs(y2 - y) else parseDimension(getAttr(parser, "height"))
    val polyPoints = if (shapeName == "polyline" || shapeName == "polygon") {
        val vb = getAttr(parser, "viewBox")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }
        parsePolyPoints(getAttr(parser, "points"), vb, x, y, w, h)
    } else emptyList()
    val styleName = getAttr(parser, "style-name")
    val resolved = resolveStyle(styleName, styles)
    val rot = parseRotationDegrees(getAttr(parser, "transform"))
    val grad = resolved.fillGradientName?.let { gradientDefs[it] }
    val cornerRadius = if (shapeName == "rect") parseDimension(getAttr(parser, "corner-radius")) else 0f

    val text = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
            val spans = parseInlineContent(parser, "p", styles)
            if (spans.isNotEmpty()) text.add(OdfParagraph(spans))
        }
        eventType = parser.next()
    }

    return when (shapeName) {
        "rect" -> OdfShape.Rect(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, cornerRadius = cornerRadius, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
        "ellipse" -> OdfShape.Ellipse(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
        "line" -> OdfShape.Line(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, x2, y2, rotationDegrees = rot, strokeDashed = resolved.strokeDashed, markerStart = resolved.markerStart, markerEnd = resolved.markerEnd)
        "polyline" -> OdfShape.Polyline(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, polyPoints, closed = false, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
        "polygon" -> OdfShape.Polyline(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, polyPoints, closed = true, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
        else -> OdfShape.CustomShape(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
    }
}

/** Parses a draw:path freeform shape, sampling svg:d into an OdfShape.Polyline. (Phase 2) */
internal fun OdfParser.parsePathShape(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfShape {
    val x = parseDimension(getAttr(parser, "x"))
    val y = parseDimension(getAttr(parser, "y"))
    val w = parseDimension(getAttr(parser, "width"))
    val h = parseDimension(getAttr(parser, "height"))
    val vb = getAttr(parser, "viewBox")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }
    val d = getAttr(parser, "d")
    val styleName = getAttr(parser, "style-name")
    val resolved = resolveStyle(styleName, styles)
    val rot = parseRotationDegrees(getAttr(parser, "transform"))
    val grad = resolved.fillGradientName?.let { gradientDefs[it] }
    val (points, closed) = sampleSvgPath(d, vb, x, y, w, h)

    val text = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()
    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG && parser.name == "p" && parser.namespace?.contains("text") == true) {
            val spans = parseInlineContent(parser, "p", styles)
            if (spans.isNotEmpty()) text.add(OdfParagraph(spans))
        }
        eventType = parser.next()
    }
    return OdfShape.Polyline(x, y, w, h, resolved.drawFillColor, resolved.drawStrokeColor, resolved.drawStrokeWidth, text, points, closed = closed, rotationDegrees = rot, fillGradient = grad, strokeDashed = resolved.strokeDashed)
}

/** Flattens an SVG path 'd' (viewBox space) into absolute px@96 vertices; returns (points, closed). (Phase 2) */
internal fun OdfParser.sampleSvgPath(d: String?, vb: List<Float>?, x: Float, y: Float, w: Float, h: Float): Pair<List<Pair<Float, Float>>, Boolean> {
    if (d.isNullOrBlank()) return emptyList<Pair<Float, Float>>() to false
    val vbMinX = vb?.getOrNull(0) ?: 0f; val vbMinY = vb?.getOrNull(1) ?: 0f
    val vbW = vb?.getOrNull(2)?.takeIf { it != 0f } ?: 1f; val vbH = vb?.getOrNull(3)?.takeIf { it != 0f } ?: 1f
    fun map(px: Float, py: Float) = (x + (px - vbMinX) / vbW * w) to (y + (py - vbMinY) / vbH * h)
    // Tokenize commands + numbers.
    val tokens = Regex("[MmLlHhVvCcSsQqTtAaZz]|-?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?").findAll(d).map { it.value }.toList()
    val raw = ArrayList<Pair<Float, Float>>()
    var closed = false
    var i = 0
    var cx = 0f; var cy = 0f; var startX = 0f; var startY = 0f
    var cmd = ' '
    fun num(): Float { return (tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f) }
    fun cubic(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val steps = 8
        for (s in 1..steps) {
            val t = s / steps.toFloat(); val u = 1 - t
            val px = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
            val py = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
            raw.add(px to py)
        }
    }
    fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
        val steps = 8
        for (s in 1..steps) {
            val t = s / steps.toFloat(); val u = 1 - t
            val px = u * u * x0 + 2 * u * t * x1 + t * t * x2
            val py = u * u * y0 + 2 * u * t * y1 + t * t * y2
            raw.add(px to py)
        }
    }
    while (i < tokens.size) {
        val tk = tokens[i]
        if (tk.length == 1 && tk[0].isLetter()) { cmd = tk[0]; i++ }
        val rel = cmd.isLowerCase()
        when (cmd.uppercaseChar()) {
            'M' -> { var nx = num(); var ny = num(); if (rel) { nx += cx; ny += cy }; cx = nx; cy = ny; startX = cx; startY = cy; raw.add(cx to cy); cmd = if (rel) 'l' else 'L' }
            'L' -> { var nx = num(); var ny = num(); if (rel) { nx += cx; ny += cy }; cx = nx; cy = ny; raw.add(cx to cy) }
            'H' -> { var nx = num(); if (rel) nx += cx; cx = nx; raw.add(cx to cy) }
            'V' -> { var ny = num(); if (rel) ny += cy; cy = ny; raw.add(cx to cy) }
            'C' -> { var x1 = num(); var y1 = num(); var x2 = num(); var y2 = num(); var nx = num(); var ny = num(); if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; nx += cx; ny += cy }; cubic(cx, cy, x1, y1, x2, y2, nx, ny); cx = nx; cy = ny }
            'S' -> { var x2 = num(); var y2 = num(); var nx = num(); var ny = num(); if (rel) { x2 += cx; y2 += cy; nx += cx; ny += cy }; cubic(cx, cy, cx, cy, x2, y2, nx, ny); cx = nx; cy = ny }
            'Q' -> { var x1 = num(); var y1 = num(); var nx = num(); var ny = num(); if (rel) { x1 += cx; y1 += cy; nx += cx; ny += cy }; quad(cx, cy, x1, y1, nx, ny); cx = nx; cy = ny }
            'T' -> { var nx = num(); var ny = num(); if (rel) { nx += cx; ny += cy }; raw.add(nx to ny); cx = nx; cy = ny }
            'A' -> { num(); num(); num(); num(); num(); var nx = num(); var ny = num(); if (rel) { nx += cx; ny += cy }; raw.add(nx to ny); cx = nx; cy = ny }
            'Z' -> { closed = true; cx = startX; cy = startY; raw.add(startX to startY) }
            else -> i++
        }
    }
    return raw.map { map(it.first, it.second) } to closed
}

/** Maps a draw:points string (viewBox space) into absolute px@96 vertices. (Priority 8) */
internal fun OdfParser.parsePolyPoints(raw: String?, vb: List<Float>?, x: Float, y: Float, w: Float, h: Float): List<Pair<Float, Float>> {
    if (raw.isNullOrBlank()) return emptyList()
    val vbMinX = vb?.getOrNull(0) ?: 0f; val vbMinY = vb?.getOrNull(1) ?: 0f
    val vbW = vb?.getOrNull(2)?.takeIf { it != 0f } ?: 1f; val vbH = vb?.getOrNull(3)?.takeIf { it != 0f } ?: 1f
    return raw.trim().split(Regex("\\s+")).mapNotNull { pair ->
        val xy = pair.split(","); if (xy.size != 2) return@mapNotNull null
        val vx = xy[0].toFloatOrNull() ?: return@mapNotNull null
        val vy = xy[1].toFloatOrNull() ?: return@mapNotNull null
        (x + (vx - vbMinX) / vbW * w) to (y + (vy - vbMinY) / vbH * h)
    }
}

// --- CSV parsing ---

fun OdfParser.parseCsv(text: String, fileName: String, delimiter: Char = ','): OdfDocument.Spreadsheet {
    val rows = mutableListOf<OdfRow>()
    val fields = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false

    fun endRecord() {
        fields.add(sb.toString()); sb.clear()
        // Skip truly blank lines (mirrors the old per-line isBlank() skip) so trailing
        // newlines and blank separators don't create empty rows. A row like ",," has
        // multiple (empty) fields and is kept, matching the previous behavior.
        if (!(fields.size == 1 && fields[0].isBlank())) {
            rows.add(OdfRow(fields.map { OdfCell(text = it) }))
        }
        fields.clear()
    }

    // Single pass over the whole text so a quoted field can span commas AND newlines.
    // RFC-4180 style: "" inside a quoted field is a literal quote; \r\n / \r / \n end a record.
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inQuotes -> when {
                c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> inQuotes = false
                else -> sb.append(c) // newlines/commas inside quotes are literal content
            }
            c == '"' -> inQuotes = true
            c == delimiter -> { fields.add(sb.toString()); sb.clear() }
            c == '\r' -> { endRecord(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
            c == '\n' -> endRecord()
            else -> sb.append(c)
        }
        i++
    }
    // Flush the final record when the file doesn't end with a newline.
    if (sb.isNotEmpty() || fields.isNotEmpty()) endRecord()

    return OdfDocument.Spreadsheet(fileName, listOf(OdfSheet("Sheet 1", rows)))
}

/** Parses draw:gradient definitions from a styles/content XML. (Round 3) */
internal fun OdfParser.parseGradients(xml: String): Map<String, OdfGradient> {
    val map = mutableMapOf<String, OdfGradient>()
    val parser = newParser(xml)
    var e = parser.eventType
    while (e != XmlPullParser.END_DOCUMENT) {
        if (e == XmlPullParser.START_TAG && parser.name == "gradient") {
            val nm = getAttr(parser, "name")
            val start = getAttr(parser, "start-color")?.let { parseColor(it) }
            val end = getAttr(parser, "end-color")?.let { parseColor(it) }
            if (nm != null && start != null && end != null) {
                val angle = getAttr(parser, "angle")?.let { a ->
                    // ODF gradient angle is in 1/10 degree, or with "deg" suffix in newer files.
                    a.removeSuffix("deg").toFloatOrNull()?.let { if (a.endsWith("deg")) it else it / 10f }
                } ?: 0f
                map[nm] = OdfGradient(start, end, angle, getAttr(parser, "style") ?: "linear")
            }
        }
        e = parser.next()
    }
    return map
}
