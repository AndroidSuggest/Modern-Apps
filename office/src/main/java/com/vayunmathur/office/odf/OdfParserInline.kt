package com.vayunmathur.office.odf

import android.util.Base64
import com.vayunmathur.library.ui.odf.*
import org.xmlpull.v1.XmlPullParser

// --- Inline content & span creation ---

internal fun OdfParser.makeSpan(text: String, styleName: String?, styles: Map<String, StyleInfo>, href: String? = null): OdfSpan {
    val resolved = resolveStyle(styleName, styles)
    return OdfSpan(
        text = text,
        bold = resolved.bold,
        italic = resolved.italic,
        fontSize = resolved.fontSize,
        fontFamily = resolved.fontFamily,
        underline = resolved.underline || href != null,
        strikethrough = resolved.strikethrough,
        color = if (href != null && resolved.color == null) LINK_COLOR else resolved.color,
        backgroundColor = resolved.backgroundColor,
        superscript = resolved.superscript,
        subscript = resolved.subscript,
        href = href,
        underlineStyle = resolved.underlineStyle,
        underlineColor = resolved.underlineColor,
        letterSpacing = resolved.letterSpacing,
        textTransform = resolved.textTransform,
        language = resolved.language,
        country = resolved.country
    )
}

internal fun OdfParser.parseInlineContent(
    parser: XmlPullParser, endTag: String,
    styles: Map<String, StyleInfo>,
    images: Map<String, ByteArray> = emptyMap(),
    footnotes: MutableList<OdfFootnote>? = null,
    imagesOut: MutableList<OdfImage>? = null,
    objectContents: Map<String, String> = emptyMap(),
    chartsOut: MutableList<OdfChart>? = null,
    formulasOut: MutableList<String>? = null
): List<OdfSpan> {
    val spans = mutableListOf<OdfSpan>()
    val depth = parser.depth
    var eventType = parser.next()
    val textBuffer = StringBuilder()
    var currentStyleName: String? = null
    var currentHref: String? = null
    var pendingFrameW = 0f
    var pendingFrameH = 0f
    var pendingFrameClip: String? = null
    var pendingFrameStyleClip: String? = null
    // Track-change insertion ranges (changeId -> span start index) and a buffer flush helper. (Priority 6)
    val changeStack = ArrayDeque<Pair<String, Int>>()
    fun flushBuf() {
        if (textBuffer.isNotEmpty()) {
            spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
            textBuffer.clear()
        }
    }

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
        when (eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "span" -> {
                    if (textBuffer.isNotEmpty()) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    currentStyleName = getAttr(parser, "style-name")
                }
                "a" -> {
                    if (textBuffer.isNotEmpty()) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    currentHref = getAttr(parser, "href")
                }
                "tab" -> textBuffer.append("\t")
                "s" -> textBuffer.append(" ".repeat((getAttr(parser, "c")?.toIntOrNull() ?: 1)))
                "line-break" -> textBuffer.append("\n")
                "frame" -> {
                    pendingFrameW = parseDimension(getAttr(parser, "width"))
                    pendingFrameH = parseDimension(getAttr(parser, "height"))
                    pendingFrameClip = getAttr(parser, "clip")
                    pendingFrameStyleClip = resolveStyle(getAttr(parser, "style-name"), styles).clip
                }
                "image" -> {
                    val href = getAttr(parser, "href")
                    val w = pendingFrameW
                    val h = pendingFrameH
                    if (href != null && images.containsKey(href)) {
                        val bytes = images[href]!!
                        val (nw, nh) = decodeNaturalSize(bytes)
                        val clip = parseClip(pendingFrameClip) ?: parseClipLengths(pendingFrameStyleClip, nw, nh)
                        var img = OdfImage(path = href, imageData = bytes, width = w, height = h, naturalWidthPx = nw, naturalHeightPx = nh)
                        if (clip != null) img = img.copy(cropLeftPct = clip[0], cropTopPct = clip[1], cropRightPct = clip[2], cropBottomPct = clip[3])
                        imagesOut?.add(img)
                        skipElement(parser)
                    } else {
                        // Look for inline base64 binary-data. (A2/E37)
                        val imgDepth = parser.depth
                        var imgEvent = parser.next()
                        while (!(imgEvent == XmlPullParser.END_TAG && parser.depth == imgDepth)) {
                            if (imgEvent == XmlPullParser.START_TAG && parser.name == "binary-data") {
                                imgEvent = parser.next()
                                if (imgEvent == XmlPullParser.TEXT) {
                                    try {
                                        val bytes = Base64.decode(parser.text.trim(), Base64.DEFAULT)
                                        val (nw, nh) = decodeNaturalSize(bytes)
                                        val clip = parseClip(pendingFrameClip) ?: parseClipLengths(pendingFrameStyleClip, nw, nh)
                                        var img = OdfImage(path = "inline", imageData = bytes, width = w, height = h, naturalWidthPx = nw, naturalHeightPx = nh)
                                        if (clip != null) img = img.copy(cropLeftPct = clip[0], cropTopPct = clip[1], cropRightPct = clip[2], cropBottomPct = clip[3])
                                        imagesOut?.add(img)
                                    } catch (_: Exception) {}
                                }
                            }
                            imgEvent = parser.next()
                        }
                        // No real image data found (e.g. an object-replacement preview for a chart);
                        // skip rather than emitting an empty "[Image]" placeholder.
                    }
                }
                "object" -> {
                    val href = getAttr(parser, "href")?.removePrefix("./")
                    val xml = href?.let { objectContents["$it/content.xml"] }
                    if (xml != null) {
                        val chart = parseChart(xml)
                        when {
                            chart != null -> chartsOut?.add(chart)
                            xml.contains("math") -> formulasOut?.add(xml)
                            xml.contains("office:spreadsheet") -> formulasOut?.add("📊 [Embedded spreadsheet]")
                            xml.contains("office:text") -> formulasOut?.add("📄 [Embedded document]")
                            else -> formulasOut?.add("📦 [Embedded object]")
                        }
                    } else if (href != null) {
                        formulasOut?.add("📦 [Embedded object]")
                    }
                }
                "note" -> {
                    if (textBuffer.isNotEmpty()) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    val fn = parseFootnote(parser, styles)
                    if (fn != null) {
                        footnotes?.add(fn)
                        spans.add(OdfSpan(text = fn.citation, superscript = true, color = LINK_COLOR))
                    }
                }
                "annotation" -> {
                    if (textBuffer.isNotEmpty()) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    val annotation = parseAnnotation(parser, styles)
                    if (annotation != null) {
                        spans.add(OdfSpan(text = " 📝 ", annotation = annotation))
                    }
                }
                "change-start" -> {
                    flushBuf()
                    getAttr(parser, "change-id")?.let { changeStack.addLast(it to spans.size) }
                }
                "change-end" -> {
                    flushBuf()
                    val id = getAttr(parser, "change-id")
                    val idx = changeStack.indexOfLast { it.first == id }
                    if (idx >= 0) {
                        val (cid, start) = changeStack.removeAt(idx)
                        for (i in start until spans.size) spans[i] = spans[i].copy(changeKind = "insertion", changeId = cid)
                    }
                }
                "change" -> {
                    // Deletion point: pull the deleted text from the tracked-changes region. (Priority 6)
                    flushBuf()
                    getAttr(parser, "change-id")?.let { id ->
                        spans.add(OdfSpan(text = trackedDeletionText[id] ?: "", changeKind = "deletion", changeId = id))
                    }
                }
                "reference-ref", "bookmark-ref" -> {
                    // Cross-reference to a reference-mark/bookmark, carrying its cached display text. (Priority 5)
                    if (textBuffer.isNotEmpty()) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    val kind = parser.name
                    val refName = getAttr(parser, "ref-name")
                    val refFormat = getAttr(parser, "reference-format")
                    val d = parser.depth; val fb = StringBuilder(); var ev = parser.next()
                    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                        if (ev == XmlPullParser.TEXT) fb.append(parser.text)
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        ev = parser.next()
                    }
                    spans.add(makeSpan(fb.toString(), currentStyleName, styles, currentHref)
                        .copy(refKind = kind, refName = refName, refFormat = refFormat, color = LINK_COLOR))
                }
                "reference-mark", "reference-mark-start", "reference-mark-end" -> {
                    // Cross-reference target marker (zero-width). (Priority 5)
                    if (textBuffer.isNotEmpty()) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    spans.add(OdfSpan(text = "", refKind = parser.name, refName = getAttr(parser, "name")))
                }
                "bookmark", "bookmark-start", "bookmark-end" -> {
                    // Inline bookmark / bookmark range marker (zero-width). (Priority 9)
                    if (textBuffer.isNotEmpty()) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    spans.add(OdfSpan(text = "", refKind = parser.name, refName = getAttr(parser, "name")))
                }
                "title", "desc" -> {
                    // svg:title/svg:desc inside a draw:frame are accessibility alt text (attach to
                    // the frame's image), NOT text fields. text:title remains a real field. Either way
                    // consume the whole subtree so its text never leaks into the paragraph. (alt-text fix)
                    val isSvg = parser.namespace?.contains("svg") == true
                    val isField = !isSvg && parser.name in FIELD_TAGS
                    if (textBuffer.isNotEmpty() && (isSvg || isField)) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    val kind = parser.name
                    val d = parser.depth
                    val fb = StringBuilder()
                    var ev = parser.next()
                    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                        if (ev == XmlPullParser.TEXT) fb.append(parser.text)
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        ev = parser.next()
                    }
                    val t = fb.toString().trim()
                    when {
                        isSvg -> if (t.isNotEmpty() && imagesOut != null && imagesOut.isNotEmpty()) {
                            val idx = imagesOut.size - 1
                            imagesOut[idx] = if (kind == "title") imagesOut[idx].copy(altTitle = t)
                                else imagesOut[idx].copy(altDesc = t)
                        }
                        isField -> spans.add(makeSpan(fb.toString(), currentStyleName, styles, currentHref).copy(field = kind))
                    }
                }
                in FIELD_TAGS -> {
                    // ODF text field elements -> a span carrying the field kind + cached value. (Priority 2)
                    if (textBuffer.isNotEmpty()) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    val kind = parser.name
                    val d = parser.depth
                    val fb = StringBuilder()
                    var ev = parser.next()
                    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
                        if (ev == XmlPullParser.TEXT) fb.append(parser.text)
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        ev = parser.next()
                    }
                    spans.add(makeSpan(fb.toString(), currentStyleName, styles, currentHref).copy(field = kind))
                }
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "span" -> {
                    if (textBuffer.isNotEmpty()) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    currentStyleName = null
                }
                "a" -> {
                    if (textBuffer.isNotEmpty()) {
                        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
                        textBuffer.clear()
                    }
                    currentHref = null
                }
            }
            XmlPullParser.TEXT -> textBuffer.append(parser.text)
        }
        eventType = parser.next()
    }
    if (textBuffer.isNotEmpty()) {
        spans.add(makeSpan(textBuffer.toString(), currentStyleName, styles, currentHref))
    }
    return spans
}

// --- Footnotes ---

internal fun OdfParser.parseFootnote(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfFootnote? {
    val isEndnote = getAttr(parser, "note-class") == "endnote"
    var citation = ""
    val body = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        if (eventType == XmlPullParser.START_TAG) when (parser.name) {
            "note-citation" -> {
                val citDepth = parser.depth
                var citEvent = parser.next()
                val sb = StringBuilder()
                while (!(citEvent == XmlPullParser.END_TAG && parser.depth == citDepth)) {
                    if (citEvent == XmlPullParser.TEXT) sb.append(parser.text)
                    citEvent = parser.next()
                }
                citation = sb.toString().trim()
            }
            "note-body" -> {
                val bodyDepth = parser.depth
                var bodyEvent = parser.next()
                while (!(bodyEvent == XmlPullParser.END_TAG && parser.depth == bodyDepth)) {
                    if (bodyEvent == XmlPullParser.START_TAG && parser.name == "p") {
                        val spans = parseInlineContent(parser, "p", styles)
                        if (spans.isNotEmpty()) body.add(OdfParagraph(spans))
                    }
                    bodyEvent = parser.next()
                }
            }
        }
        eventType = parser.next()
    }
    return if (citation.isNotEmpty()) OdfFootnote(citation, body, isEndnote) else null
}

// --- Annotations ---

internal fun OdfParser.parseAnnotation(parser: XmlPullParser, styles: Map<String, StyleInfo>): OdfAnnotation? {
    var author: String? = null
    var date: String? = null
    val paragraphs = mutableListOf<OdfParagraph>()
    val depth = parser.depth
    var eventType = parser.next()
    var currentTag = ""

    while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
        when (eventType) {
            XmlPullParser.START_TAG -> {
                currentTag = parser.name
                if (parser.name == "p" && parser.namespace?.contains("text") == true) {
                    val spans = parseInlineContent(parser, "p", styles)
                    if (spans.isNotEmpty()) paragraphs.add(OdfParagraph(spans))
                    currentTag = ""
                }
            }
            XmlPullParser.TEXT -> {
                val text = parser.text.trim()
                if (text.isNotEmpty()) when (currentTag) {
                    "creator" -> author = text
                    "date" -> date = text
                }
            }
            XmlPullParser.END_TAG -> currentTag = ""
        }
        eventType = parser.next()
    }
    return OdfAnnotation(author, date, paragraphs)
}
