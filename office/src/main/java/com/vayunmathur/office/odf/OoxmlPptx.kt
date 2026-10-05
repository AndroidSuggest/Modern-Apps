package com.vayunmathur.office.odf

import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfGradient
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.ui.odf.setElementBounds
import org.xmlpull.v1.XmlPullParser

/**
 * PPTX importer (Groups 9-11, phases P1-P6). Extracts per-shape text with rich run/paragraph
 * formatting, preset-geometry shapes with fills/strokes/gradients/rotation, images, groups,
 * connectors, slide background/size/transition/name, notes, tables, and charts. Best-effort onto
 * the ODF slide model.
 */
internal object OoxmlPptx {

    private const val RELS_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val MIN_FRAME_HEIGHT = 20f
    private const val CORNER_RADIUS_FRACTION = 0.15f
    private const val OOXML_THOUSANDTHS = 100000f
    private const val AUTO_Y_GAP = 12f
    private const val PERCENT_DIVISOR = 100f
    private const val BOUNDS_X = 0
    private const val BOUNDS_Y = 1
    private const val BOUNDS_W = 2
    private const val BOUNDS_H = 3
    private const val DEFAULT_PLACEHOLDER_X = 36f
    private const val DEFAULT_PLACEHOLDER_W = 640f
    private const val DEFAULT_PLACEHOLDER_H = 80f

    /** EMU attribute as px, or null when absent/unparseable. */
    private fun emuAttr(parser: XmlPullParser, name: String): Float? =
        OoxmlXml.attr(parser, name)?.toLongOrNull()?.let { OoxmlUnits.emuToPx(it) }

    /** OOXML horizontal alignment code ("ctr"/"r"/"just"/"l") or null. */
    private fun textAlignOf(code: String?): TextAlign? = when (code) {
        "ctr" -> TextAlign.Center
        "r" -> TextAlign.End
        "just" -> TextAlign.Justify
        "l" -> TextAlign.Start
        else -> null
    }

    fun import(pkg: OoxmlPackage, fileName: String): OdfDocument.Presentation {
        val theme = OoxmlTheme.parse(firstThemeXml(pkg))
        val order = slideOrder(pkg)
        val slides = order.mapIndexed { i, part ->
            parseSlide(pkg, part, theme, "Slide ${i + 1}")
        }
        val images = LinkedHashMap<String, ByteArray>()
        for (s in slides) for (el in s.elements) collectImage(el, images)
        return OdfDocument.Presentation(
            title = fileName,
            slides = slides.ifEmpty { listOf(OdfSlide("Slide 1")) },
            metadata = OoxmlMetadata.parse(pkg),
            images = images
        )
    }

    private fun collectImage(el: OdfSlideElement, out: MutableMap<String, ByteArray>) {
        if (el is OdfSlideElement.Frame) el.frame.image?.let { out[it.path] = it.imageData }
    }

    private fun firstThemeXml(pkg: OoxmlPackage): String? =
        pkg.entries.keys.firstOrNull { it.matches(Regex("ppt/theme/theme\\d+\\.xml")) }?.let { pkg.entries[it] }

    /** Slide part paths in presentation order (falls back to filename order). */
    private fun slideOrder(pkg: OoxmlPackage): List<String> {
        val pres = pkg.entries["ppt/presentation.xml"]
        val rels = pkg.relsFor("ppt/presentation.xml")
        if (pres != null) {
            val ids = mutableListOf<String>()
            val parser = OoxmlXml.newParser(pres)
            var e = parser.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && parser.name == "sldId") {
                    val rId = OoxmlXml.attrNs(parser, RELS_NS, "id") ?: OoxmlXml.attr(parser, "id")
                    rels[rId]?.target?.let { ids.add(it) }
                }
                e = parser.next()
            }
            if (ids.isNotEmpty()) return ids
        }
        return pkg.entries.keys.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfterLast("slide").substringBefore(".xml").toIntOrNull() ?: 0 }
    }

    private class SlideCtx(
        val pkg: OoxmlPackage,
        val part: String,
        val theme: OoxmlTheme,
        val rels: Map<String, OoxmlPackage.Rel>,
        val placeholders: PlaceholderMap = PlaceholderMap(emptyList(), emptyList()),
        var autoY: Float = 36f
    )

    private fun parseSlide(pkg: OoxmlPackage, part: String, theme: OoxmlTheme, defaultName: String): OdfSlide {
        val xml = pkg.entries[part] ?: return OdfSlide(defaultName)
        // Resolve the slide -> layout -> master chain for placeholder geometry + color map inheritance.
        val layoutPart = pkg.relsFor(part).values.firstOrNull { it.type?.endsWith("slideLayout") == true }?.target
        val masterPart = layoutPart?.let { layout ->
            pkg.relsFor(layout).values.firstOrNull { r -> r.type?.endsWith("slideMaster") == true }?.target
        }
        val slideTheme = theme.withClrMap(parseClrMap(masterPart?.let { pkg.entries[it] }))
        val placeholders = PlaceholderMap(
            parsePlaceholderGeoms(pkg, layoutPart),
            parsePlaceholderGeoms(pkg, masterPart))
        val ctx = SlideCtx(pkg, part, slideTheme, pkg.relsFor(part), placeholders)
        val slide = SlideAcc(defaultName)
        val parser = OoxmlXml.newParser(xml)
        var event = parser.eventType
        var treeDepth = -1
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                treeDepth = applySlideTag(parser, ctx, slide, treeDepth)
            }
            event = parser.next()
        }
        val notes = parseNotes(pkg, ctx.rels)
        return OdfSlide(
            name = slide.slideName?.ifBlank { null } ?: defaultName,
            elements = slide.elements,
            backgroundColor = slide.bgColor,
            backgroundImagePath = slide.bgImage,
            notes = notes,
            transitionType = slide.transitionType,
            transitionSpeed = transitionSpeedOf(slide.transitionSpeed),
        )
    }

    /** Slide accumulation state. */
    private class SlideAcc(
        var slideName: String? = null,
        val elements: MutableList<OdfSlideElement> = mutableListOf(),
        var bgColor: Long? = null,
        var bgImage: String? = null,
        var transitionType: String? = null,
        var transitionSpeed: String? = null,
    )

    /** Apply one slide-level tag; returns the (possibly updated) tree depth. */
    private fun applySlideTag(
        parser: XmlPullParser,
        ctx: SlideCtx,
        slide: SlideAcc,
        treeDepth: Int,
    ): Int {
        var depth = applySlideCoreTag(parser, ctx, slide, treeDepth)
        applySlideElementTag(parser, ctx, slide, depth)
        return depth
    }

    private fun applySlideCoreTag(
        parser: XmlPullParser,
        ctx: SlideCtx,
        slide: SlideAcc,
        treeDepth: Int,
    ): Int {
        var depth = treeDepth
        when (parser.name) {
            "cSld" -> slide.slideName = OoxmlXml.attr(parser, "name")
            "bg" -> { val b = parseBackground(parser, ctx); slide.bgColor = b.first; slide.bgImage = b.second }
            "spTree" -> depth = parser.depth
            "transition" -> applySlideTransitionTag(parser, slide)
        }
        return depth
    }

    private fun applySlideTransitionTag(parser: XmlPullParser, slide: SlideAcc) {
        slide.transitionSpeed = OoxmlXml.attr(parser, "spd")
        slide.transitionType = readTransitionType(parser)
    }

    private fun applySlideElementTag(
        parser: XmlPullParser,
        ctx: SlideCtx,
        slide: SlideAcc,
        depth: Int,
    ) {
        if (parser.depth != depth + 1) return
        if (applySlideShapeElement(parser, ctx, slide)) return
        applySlideComplexElement(parser, ctx, slide)
    }

    private fun applySlideShapeElement(
        parser: XmlPullParser,
        ctx: SlideCtx,
        slide: SlideAcc,
    ): Boolean {
        when (parser.name) {
            "sp" -> parseShape(parser, ctx)?.let { slide.elements.add(it) }
            "pic" -> parsePic(parser, ctx)?.let { slide.elements.add(it) }
            else -> return false
        }
        return true
    }

    private fun applySlideComplexElement(
        parser: XmlPullParser,
        ctx: SlideCtx,
        slide: SlideAcc,
    ) {
        when (parser.name) {
            "grpSp" -> parseGroup(parser, ctx, null).let { slide.elements.addAll(it) }
            "cxnSp" -> parseConnector(parser, ctx)?.let { slide.elements.add(it) }
            "graphicFrame" -> parseGraphicFrame(parser, ctx)?.let { slide.elements.add(it) }
        }
    }

    /** Normalized transition speed. */
    private fun transitionSpeedOf(speed: String?): String? = when (speed) {
        "slow" -> "slow"
        "fast" -> "fast"
        "med" -> "medium"
        else -> speed
    }

    // ---- Background / transition ----

    private fun parseBackground(parser: XmlPullParser, ctx: SlideCtx): Pair<Long?, String?> {
        val depth = parser.depth
        var color: Long? = null; var image: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "bg")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr" -> if (color == null) color = OoxmlColor.parse(
                    parser,
                    ctx.theme)
                "blip" -> {
                    val embed = OoxmlXml.attrNs(parser, RELS_NS, "embed") ?: OoxmlXml.attr(parser, "embed")
                    val target = ctx.rels[embed]?.target
                    if (target != null && ctx.pkg.mediaBytes(target) != null) image =
                        "media/${target.substringAfterLast('/')}"
                }
            }
            e = parser.next()
        }
        return color to image
    }

    private fun readTransitionType(parser: XmlPullParser): String? {
        val depth = parser.depth
        var type: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "transition")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && type == null) type = transitionFor(parser.name)
            e = parser.next()
        }
        return type
    }

    /** Transition type name, or null for unmapped tags. */
    private fun transitionFor(tag: String): String? = when (tag) {
        "fade" -> "fade"; "wipe" -> "wipe"; "dissolve" -> "dissolve"; "push" -> "push"
        "cover" -> "cover"; "cut" -> "cut"; "split" -> "split"; "blinds" -> "blinds"
        "checker" -> "checkerboard"; "circle" -> "circle"; "wheel" -> "wheel"; else -> null
    }

    // ---- Shapes ----

    private class SpProps(
        var x: Float = 0f, var y: Float = 0f, var w: Float = 0f, var h: Float = 0f, var hasXfrm: Boolean = false,
        var rot: Float = 0f, var flipH: Boolean = false, var flipV: Boolean = false,
        var geom: String? = null, var fill: Long? = null, var gradient: OdfGradient? = null,
        var stroke: Long? = null, var strokeWidth: Float? = null, var strokeDashed: Boolean = false,
        var cornerRadius: Float = 0f,
        // Placeholder identity (from <p:ph>), for inheriting geometry from the layout/master.
        var isPlaceholder: Boolean = false, var phType: String? = null, var phIdx: String? = null
    )

    private fun parseShape(parser: XmlPullParser, ctx: SlideCtx): OdfSlideElement? {
        val depth = parser.depth
        val sp = SpProps()
        val paras = mutableListOf<OdfParagraph>()
        var hasText = false
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "sp")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "ph" -> { sp.isPlaceholder = true; sp.phType = OoxmlXml.attr(
                    parser,
                    "type"); sp.phIdx = OoxmlXml.attr(parser, "idx") }
                "spPr" -> parseSpPr(parser, ctx, sp)
                "txBody" -> { parseTxBody(
                    parser,
                    ctx,
                    paras); if (paras.any { p -> p.spans.any { it.text.isNotBlank() } }) hasText = true }
            }
            e = parser.next()
        }
        applyAutoGeometry(sp, ctx)
        return buildShapeElement(sp, paras, hasText)
    }

    private fun buildShapeElement(sp: SpProps, paras: List<OdfParagraph>, hasText: Boolean): OdfSlideElement {
        val x = sp.x
        val y = sp.y
        val w = sp.w.coerceAtLeast(1f)
        val h = sp.h.coerceAtLeast(1f)
        val geom = sp.geom
        // Text boxes and plain rectangles -> Frame (renders text + fill).
        val isPlainShape = geom == null || geom == "rect" || geom == "textBox"
        if (hasText || isPlainShape) {
            return textFrame(sp, paras, x, y, w, h)
        }
        return OdfSlideElement.Shape(shapeFor(sp, paras, x, y, w, h, geom))
    }

    /** Frame for text/plain shapes. */
    private fun textFrame(
        sp: SpProps,
        paras: List<OdfParagraph>,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ): OdfSlideElement.Frame {
        val body = paras.ifEmpty { listOf(OdfParagraph(listOf(OdfSpan("")))) }
        return OdfSlideElement.Frame(OdfFrame(
            x = x,
            y = y,
            width = w,
            height = h.coerceAtLeast(MIN_FRAME_HEIGHT),
            paragraphs = body,
            fillColor = sp.fill, strokeColor = sp.stroke, strokeWidth = sp.strokeWidth, fillGradient = sp.gradient,
            rotationDegrees = sp.rot
        ))
    }

    /** Shape for a geometry name. */
    private fun shapeFor(
        sp: SpProps,
        paras: List<OdfParagraph>,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        geom: String?,
    ): OdfShape {
        return when (geom) {
            "ellipse", "circle" -> OdfShape.Ellipse(
                x,
                y,
                w,
                h,
                sp.fill,
                sp.stroke,
                sp.strokeWidth,
                paras,
                sp.rot,
                sp.gradient,
                sp.strokeDashed)
            "roundRect" -> OdfShape.Rect(
                x,
                y,
                w,
                h,
                sp.fill,
                sp.stroke,
                sp.strokeWidth,
                paras,
                cornerRadius = if (sp.cornerRadius > 0) sp.cornerRadius else minOf(w, h) * CORNER_RADIUS_FRACTION,
                rotationDegrees = sp.rot,
                fillGradient = sp.gradient,
                strokeDashed = sp.strokeDashed)
            "line", "straightConnector1" -> lineShape(sp, paras, x, y, w, h)
            else -> OdfShape.CustomShape(
                x,
                y,
                w,
                h,
                sp.fill,
                sp.stroke,
                sp.strokeWidth,
                paras,
                sp.rot,
                sp.gradient,
                sp.strokeDashed)
        }
    }

    /** Line shape honoring flipH/flipV. */
    private fun lineShape(
        sp: SpProps,
        paras: List<OdfParagraph>,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ): OdfShape.Line {
        // Honor flipH/flipV so a flipped diagonal points the right way.
        val x1 = if (sp.flipH) x + w else x; val ex = if (sp.flipH) x else x + w
        val y1 = if (sp.flipV) y + h else y; val ey = if (sp.flipV) y else y + h
        return OdfShape.Line(
            x1,
            y1,
            w,
            h,
            sp.fill,
            sp.stroke,
            sp.strokeWidth,
            paras,
            x2 = ex,
            y2 = ey,
            rotationDegrees = sp.rot,
            strokeDashed = sp.strokeDashed)
    }

    private fun applyAutoGeometry(sp: SpProps, ctx: SlideCtx) {
        // A placeholder without its own xfrm inherits geometry from the layout/master.
        if (!sp.hasXfrm && sp.isPlaceholder) {
            ctx.placeholders.geom(sp.phType, sp.phIdx)?.let { g ->
                val (gx, gy, gw, gh) = g
                sp.x = gx; sp.y = gy; sp.w = gw; sp.h = gh; sp.hasXfrm = true
            }
        }
        if (!sp.hasXfrm) {
            sp.x = DEFAULT_PLACEHOLDER_X
            sp.w = DEFAULT_PLACEHOLDER_W
            sp.y = ctx.autoY
            if (sp.h <= 0f) sp.h = DEFAULT_PLACEHOLDER_H
        }
        ctx.autoY = sp.y + sp.h + AUTO_Y_GAP
    }

    // ---- Placeholder inheritance (layout / master) ----

    private class PlaceholderInfo(
        val type: String?,
        val idx: String?,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float)

    private class PlaceholderMap(private val layout: List<PlaceholderInfo>, private val master: List<PlaceholderInfo>) {
        /** Geometry [x,y,w,h] for a placeholder: idx match first, then type; layout overrides master. */
        fun geom(type: String?, idx: String?): FloatArray? =
            match(layout, type, idx) ?: match(master, type, idx)

        private fun match(list: List<PlaceholderInfo>, type: String?, idx: String?): FloatArray? {
            if (idx != null) list.firstOrNull { it.idx == idx }?.let { return floatArrayOf(it.x, it.y, it.w, it.h) }
            val t = normType(type)
            list.firstOrNull { normType(it.type) == t }?.let { return floatArrayOf(it.x, it.y, it.w, it.h) }
            return null
        }

        private fun normType(t: String?): String = when (t) { null -> "body"; "ctrTitle" -> "title"; else -> t }
    }

    /** Extracts placeholder geometries (with an explicit xfrm) from a layout/master part's spTree. */
    private fun parsePlaceholderGeoms(pkg: OoxmlPackage, part: String?): List<PlaceholderInfo> {
        val xml = part?.let { pkg.entries[it] } ?: return emptyList()
        val parser = OoxmlXml.newParser(xml)
        val acc = PlaceholderAcc()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) applyPlaceholderStart(parser, acc)
            else if (e == XmlPullParser.END_TAG) applyPlaceholderEnd(parser, acc)
            e = parser.next()
        }
        return acc.list
    }

    private class PlaceholderAcc(
        val list: MutableList<PlaceholderInfo> = mutableListOf(),
        var spDepth: Int = -1,
        var phType: String? = null,
        var phIdx: String? = null,
        var x: Float = 0f,
        var y: Float = 0f,
        var w: Float = 0f,
        var h: Float = 0f,
        var hasXfrm: Boolean = false,
        var inXfrm: Boolean = false,
    ) {
        fun resetShape(depth: Int) {
            spDepth = depth
            phType = null
            phIdx = null
            x = 0f
            y = 0f
            w = 0f
            h = 0f
            hasXfrm = false
        }

        fun flush() {
            if (hasXfrm && (phType != null || phIdx != null)) list.add(PlaceholderInfo(
                phType,
                phIdx,
                x,
                y,
                w,
                h))
            spDepth = -1
        }
    }

    private fun applyPlaceholderStart(parser: XmlPullParser, acc: PlaceholderAcc) {
        when (parser.name) {
            "sp" -> acc.resetShape(parser.depth)
            "ph" -> applyPlaceholderPhTag(parser, acc)
            "xfrm" -> if (acc.spDepth >= 0) acc.inXfrm = true
            "off" -> applyPlaceholderOffTag(parser, acc)
            "ext" -> applyPlaceholderExtTag(parser, acc)
        }
    }

    private fun applyPlaceholderPhTag(parser: XmlPullParser, acc: PlaceholderAcc) {
        if (acc.spDepth < 0) return
        acc.phType = OoxmlXml.attr(parser, "type")
        acc.phIdx = OoxmlXml.attr(parser, "idx")
    }

    private fun applyPlaceholderOffTag(parser: XmlPullParser, acc: PlaceholderAcc) {
        if (!acc.inXfrm) return
        emuAttr(parser, "x")?.let { acc.x = it }
        emuAttr(parser, "y")?.let { acc.y = it }
        acc.hasXfrm = true
    }

    private fun applyPlaceholderExtTag(parser: XmlPullParser, acc: PlaceholderAcc) {
        if (!acc.inXfrm) return
        emuAttr(parser, "cx")?.let { acc.w = it }
        emuAttr(parser, "cy")?.let { acc.h = it }
    }

    private fun applyPlaceholderEnd(parser: XmlPullParser, acc: PlaceholderAcc) {
        when (parser.name) {
            "xfrm" -> acc.inXfrm = false
            "sp" -> if (parser.depth == acc.spDepth) acc.flush()
        }
    }

    /** Parses a slide master's <p:clrMap> (bg1/tx1/... -> theme slot) attributes. */
    private fun parseClrMap(masterXml: String?): Map<String, String> {
        if (masterXml == null) return emptyMap()
        val parser = OoxmlXml.newParser(masterXml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "clrMap") {
                val m = HashMap<String, String>()
                for (k in listOf(
                    "bg1",
                    "tx1",
                    "bg2",
                    "tx2",
                    "accent1",
                    "accent2",
                    "accent3",
                    "accent4",
                    "accent5",
                    "accent6",
                    "hlink",
                    "folHlink")) {
                    OoxmlXml.attr(parser, k)?.let { m[k.lowercase()] = it.lowercase() }
                }
                return m
            }
            e = parser.next()
        }
        return emptyMap()
    }

    private fun parseSpPr(parser: XmlPullParser, ctx: SlideCtx, sp: SpProps) {
        val depth = parser.depth
        val state = SpPrAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "spPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applySpPrTag(parser, ctx, sp, state)
            else if (e == XmlPullParser.END_TAG) when (parser.name) {
                "ln" -> state.inLn = false
                "effectLst", "effectDag" -> state.inEffect = false
            }
            e = parser.next()
        }
    }

    /** spPr parse state. */
    private class SpPrAcc(
        var inLn: Boolean = false,
        var inEffect: Boolean = false,
    )

    /** Apply one spPr child tag. */
    private fun applySpPrTag(parser: XmlPullParser, ctx: SlideCtx, sp: SpProps, state: SpPrAcc) {
        if (applySpPrFillTag(parser, ctx, sp, state)) return
        applySpPrGeomTag(parser, sp, state)
    }

    private fun applySpPrFillTag(parser: XmlPullParser, ctx: SlideCtx, sp: SpProps, state: SpPrAcc): Boolean {
        when (parser.name) {
            "effectLst", "effectDag" -> state.inEffect = true
            "ln" -> applyLn(parser, sp, state)
            "prstDash" -> applyPrstDashTag(parser, sp, state)
            "gradFill" -> applyGradFillTag(parser, ctx, sp, state)
            "srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr" -> applySpPrColor(parser, ctx, sp, state)
            "noFill" -> if (!state.inLn) sp.fill = null
            else -> return false
        }
        return true
    }

    private fun applyPrstDashTag(parser: XmlPullParser, sp: SpProps, state: SpPrAcc) {
        if (state.inLn) {
            sp.strokeDashed = OoxmlXml.attr(parser, "val")?.contains("dash", true) == true
        }
    }

    private fun applyGradFillTag(parser: XmlPullParser, ctx: SlideCtx, sp: SpProps, state: SpPrAcc) {
        val g = parseGradient(parser, ctx.theme)
        if (!state.inLn && !state.inEffect) sp.gradient = g
    }

    private fun applySpPrGeomTag(parser: XmlPullParser, sp: SpProps, state: SpPrAcc) {
        @Suppress("UNUSED_PARAMETER") val unused = state
        when (parser.name) {
            "xfrm" -> applyXfrm(parser, sp)
            "off" -> {
                emuAttr(parser, "x")?.let { sp.x = it }
                emuAttr(parser, "y")?.let { sp.y = it }
            }
            "ext" -> {
                emuAttr(parser, "cx")?.let { sp.w = it }
                emuAttr(parser, "cy")?.let { sp.h = it }
            }
            "prstGeom" -> sp.geom = OoxmlXml.attr(parser, "prst")
        }
    }

    /** Transform attributes. */
    private fun applyXfrm(parser: XmlPullParser, sp: SpProps) {
        sp.hasXfrm = true
        OoxmlXml.attr(parser, "rot")?.toIntOrNull()?.let { sp.rot = OoxmlUnits.angle60000ToDeg(it) }
        sp.flipH = OoxmlXml.attr(parser, "flipH") == "1"
        sp.flipV = OoxmlXml.attr(parser, "flipV") == "1"
    }

    /** Line width + state. */
    private fun applyLn(parser: XmlPullParser, sp: SpProps, state: SpPrAcc) {
        state.inLn = true
        OoxmlXml.attr(
            parser,
            "w")?.toLongOrNull()?.let { sp.strokeWidth = OoxmlUnits.emuToPx(it) }
    }

    /** Fill/stroke color (skipping effect colors). */
    private fun applySpPrColor(parser: XmlPullParser, ctx: SlideCtx, sp: SpProps, state: SpPrAcc) {
        val c = OoxmlColor.parse(parser, ctx.theme)
        // Skip colors inside <a:effectLst> (e.g. shadow color) so they don't become the fill.
        if (state.inLn) { if (sp.stroke == null) sp.stroke = c } else if (!state.inEffect && sp.fill == null) sp.fill =
            c
    }

    private fun parseGradient(parser: XmlPullParser, theme: OoxmlTheme): OdfGradient? {
        val depth = parser.depth
        val stops = mutableListOf<Pair<Int, Long>>()
        var angle = 0f
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "gradFill")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "gs" -> parseGradientStop(parser, theme)?.let { stops.add(it) }
                    "lin" -> angle = OoxmlXml.attr(
                        parser,
                        "ang")?.toIntOrNull()?.let { OoxmlUnits.angle60000ToDeg(it) } ?: 0f
                }
            }
            e = parser.next()
        }
        if (stops.size < 2) return null
        val sorted = stops.sortedBy { it.first }
        return OdfGradient(startColor = sorted.first().second, endColor = sorted.last().second, angle = angle)
    }

    /** One gradient stop (position + first color child). */
    private fun parseGradientStop(parser: XmlPullParser, theme: OoxmlTheme): Pair<Int, Long>? {
        val pos = OoxmlXml.attr(parser, "pos")?.toIntOrNull() ?: 0
        // descend to color child
        val d = parser.depth
        var ev = parser.next(); var color: Long? = null
        while (!(ev == XmlPullParser.END_TAG && parser.depth == d && parser.name == "gs")) {
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG && parser.name in COLOR_TAGS && color == null) color =
                OoxmlColor.parse(parser, theme)
            ev = parser.next()
        }
        return color?.let { pos to it }
    }

    private val COLOR_TAGS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr")

    // ---- Pictures ----

    private fun parsePic(parser: XmlPullParser, ctx: SlideCtx): OdfSlideElement? {
        val depth = parser.depth
        val sp = SpProps()
        val pic = PicAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "pic")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyPicTag(parser, ctx, sp, pic)
            e = parser.next()
        }
        val target = ctx.rels[pic.embed]?.target ?: return null
        val bytes = ctx.pkg.mediaBytes(target) ?: return null
        applyAutoGeometry(sp, ctx)
        val path = "media/${target.substringAfterLast('/')}"
        return OdfSlideElement.Frame(OdfFrame(
            x = sp.x, y = sp.y, width = sp.w.coerceAtLeast(1f), height = sp.h.coerceAtLeast(1f),
            paragraphs = emptyList(),
            image = OdfImage(path, bytes, sp.w, sp.h, rotationDegrees = sp.rot,
                cropLeftPct = pic.cropL, cropTopPct = pic.cropT, cropRightPct = pic.cropR,
                cropBottomPct = pic.cropB, altDesc = pic.desc)
        ))
    }

    /** Picture accumulation state. */
    private class PicAcc(
        var embed: String? = null,
        var desc: String? = null,
        var cropL: Float = 0f,
        var cropT: Float = 0f,
        var cropR: Float = 0f,
        var cropB: Float = 0f,
    )

    /** Apply one pic child tag. */
    private fun applyPicTag(parser: XmlPullParser, ctx: SlideCtx, sp: SpProps, pic: PicAcc) {
        when (parser.name) {
            "ph" -> { sp.isPlaceholder = true; sp.phType = OoxmlXml.attr(
                parser,
                "type"); sp.phIdx = OoxmlXml.attr(parser, "idx") }
            "spPr" -> parseSpPr(parser, ctx, sp)
            "cNvPr" -> pic.desc = OoxmlXml.attr(parser, "descr") ?: OoxmlXml.attr(parser, "name")
            "blip" -> pic.embed = OoxmlXml.attrNs(parser, RELS_NS, "embed") ?: OoxmlXml.attr(parser, "embed")
            "srcRect" -> applySrcRect(parser, pic)
        }
    }

    /** Source rectangle (crop) percentages. */
    private fun applySrcRect(parser: XmlPullParser, pic: PicAcc) {
        pic.cropL = (OoxmlXml.attr(parser, "l")?.toIntOrNull() ?: 0) / OOXML_THOUSANDTHS
        pic.cropT = (OoxmlXml.attr(parser, "t")?.toIntOrNull() ?: 0) / OOXML_THOUSANDTHS
        pic.cropR = (OoxmlXml.attr(parser, "r")?.toIntOrNull() ?: 0) / OOXML_THOUSANDTHS
        pic.cropB = (OoxmlXml.attr(parser, "b")?.toIntOrNull() ?: 0) / OOXML_THOUSANDTHS
    }

    // ---- Groups / connectors ----

    /** Affine map (per-axis scale + offset) from a group's child coordinate space to screen px@96. */
    private class GroupTf(val ax: Float, val bx: Float, val ay: Float, val by: Float) {
        fun apply(x: Float, y: Float, w: Float, h: Float) = floatArrayOf(ax * x + bx, ay * y + by, w * ax, h * ay)
        companion object {
            /** parent ∘ child: apply child first (its space -> parent's child space), then parent. */
            fun compose(p: GroupTf, c: GroupTf) =
                GroupTf(p.ax * c.ax, p.ax * c.bx + p.bx, p.ay * c.ay, p.ay * c.by + p.by)
        }
    }

    private fun parseGroup(parser: XmlPullParser, ctx: SlideCtx, parentTf: GroupTf?): List<OdfSlideElement> {
        val depth = parser.depth
        val out = mutableListOf<OdfSlideElement>()
        var tf = parentTf
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "grpSp")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) tf = applyGroupTag(parser, ctx, tf, parentTf, out)
            e = parser.next()
        }
        return out
    }

    private fun applyGroupTag(
        parser: XmlPullParser, ctx: SlideCtx, tf: GroupTf?, parentTf: GroupTf?,
        out: MutableList<OdfSlideElement>,
    ): GroupTf? {
        var cur = tf
        when (parser.name) {
            // grpSpPr precedes the children; fold the group's own off/ext/chOff/chExt into the transform.
            "grpSpPr" -> parseGroupXfrm(parser)?.let { own -> cur = parentTf?.let { GroupTf.compose(
                it,
                own) } ?: own }
            "sp" -> parseShape(parser, ctx)?.let { out.add(applyTf(it, cur)) }
            "pic" -> parsePic(parser, ctx)?.let { out.add(applyTf(it, cur)) }
            "cxnSp" -> parseConnector(parser, ctx)?.let { out.add(applyTf(it, cur)) }
            "grpSp" -> out.addAll(parseGroup(parser, ctx, cur))
        }
        return cur
    }

    private class GroupXfrmAcc(
        var offX: Float = 0f,
        var offY: Float = 0f,
        var extX: Float = 0f,
        var extY: Float = 0f,
        var chOffX: Float = 0f,
        var chOffY: Float = 0f,
        var chExtX: Float = 0f,
        var chExtY: Float = 0f,
        var inXfrm: Boolean = false,
        var seen: Boolean = false,
    ) {
        fun toTf(): GroupTf {
            val ax = if (chExtX != 0f) extX / chExtX else 1f
            val ay = if (chExtY != 0f) extY / chExtY else 1f
            return GroupTf(ax, offX - chOffX * ax, ay, offY - chOffY * ay)
        }
    }

    /** Reads a group's <a:xfrm> (off/ext/chOff/chExt) into a child-space -> parent-space transform. */
    private fun parseGroupXfrm(parser: XmlPullParser): GroupTf? {
        val depth = parser.depth
        val acc = GroupXfrmAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "grpSpPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyGroupXfrmTag(parser, acc)
            else if (e == XmlPullParser.END_TAG && parser.name == "xfrm") acc.inXfrm = false
            e = parser.next()
        }
        if (!acc.seen) return null
        return acc.toTf()
    }

    private fun applyGroupXfrmTag(parser: XmlPullParser, acc: GroupXfrmAcc) {
        when (parser.name) {
            "xfrm" -> { acc.inXfrm = true; acc.seen = true }
            "off" -> applyGroupOffTag(parser, acc)
            "ext" -> applyGroupExtTag(parser, acc)
            "chOff" -> applyGroupChOffTag(parser, acc)
            "chExt" -> applyGroupChExtTag(parser, acc)
        }
    }

    private fun groupEmu(parser: XmlPullParser, name: String): Float =
        OoxmlXml.attr(parser, name)?.toLongOrNull()?.let { OoxmlUnits.emuToPx(it) } ?: 0f

    private fun applyGroupOffTag(parser: XmlPullParser, acc: GroupXfrmAcc) {
        if (!acc.inXfrm) return
        acc.offX = groupEmu(parser, "x")
        acc.offY = groupEmu(parser, "y")
    }

    private fun applyGroupExtTag(parser: XmlPullParser, acc: GroupXfrmAcc) {
        if (!acc.inXfrm) return
        acc.extX = groupEmu(parser, "cx")
        acc.extY = groupEmu(parser, "cy")
    }

    private fun applyGroupChOffTag(parser: XmlPullParser, acc: GroupXfrmAcc) {
        if (!acc.inXfrm) return
        acc.chOffX = groupEmu(parser, "x")
        acc.chOffY = groupEmu(parser, "y")
    }

    private fun applyGroupChExtTag(parser: XmlPullParser, acc: GroupXfrmAcc) {
        if (!acc.inXfrm) return
        acc.chExtX = groupEmu(parser, "cx")
        acc.chExtY = groupEmu(parser, "cy")
    }

    private fun applyTf(el: OdfSlideElement, tf: GroupTf?): OdfSlideElement {
        if (tf == null) return el
        val b = el.bounds()
        val n = tf.apply(b[BOUNDS_X], b[BOUNDS_Y], b[BOUNDS_W], b[BOUNDS_H])
        return setElementBounds(el, n[BOUNDS_X], n[BOUNDS_Y], n[BOUNDS_W], n[BOUNDS_H])
    }

    private fun parseConnector(parser: XmlPullParser, ctx: SlideCtx): OdfSlideElement? {
        val depth = parser.depth
        val sp = SpProps()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "cxnSp")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "spPr") parseSpPr(parser, ctx, sp)
            e = parser.next()
        }
        if (!sp.hasXfrm) return null
        return OdfSlideElement.Shape(OdfShape.Line(
            sp.x, sp.y, sp.w, sp.h, null, sp.stroke, sp.strokeWidth, emptyList(),
            x2 = sp.x + sp.w, y2 = sp.y + sp.h, rotationDegrees = sp.rot, strokeDashed = sp.strokeDashed
        ))
    }

    // ---- Graphic frames (tables / charts) ----

    private fun parseGraphicFrame(parser: XmlPullParser, ctx: SlideCtx): OdfSlideElement? {
        val depth = parser.depth
        val frame = GraphicFrameAcc(y = ctx.autoY)
        val tableParas = mutableListOf<OdfParagraph>()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "graphicFrame")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyGraphicFrameTag(parser, frame, ctx, tableParas)
            e = parser.next()
        }
        ctx.autoY = frame.y + frame.h + AUTO_Y_GAP
        chartFrame(ctx, frame)?.let { return it }
        diagramFrame(ctx, frame)?.let { return it }
        if (tableParas.isNotEmpty()) {
            return OdfSlideElement.Frame(OdfFrame(
                frame.x, frame.y, frame.w, frame.h.coerceAtLeast(MIN_FRAME_HEIGHT), tableParas))
        }
        return null
    }

    /** Graphic-frame accumulation state. */
    private class GraphicFrameAcc(
        var x: Float = 36f,
        var y: Float,
        var w: Float = 640f,
        var h: Float = 200f,
        var hasXfrm: Boolean = false,
        var chartRid: String? = null,
        var dmRid: String? = null,
    )

    /** Apply one graphicFrame child tag. */
    private fun applyGraphicFrameTag(
        parser: XmlPullParser,
        frame: GraphicFrameAcc,
        ctx: SlideCtx,
        tableParas: MutableList<OdfParagraph>,
    ) {
        when (parser.name) {
            "off" -> {
                emuAttr(parser, "x")?.let { frame.x = it; frame.hasXfrm = true }
                emuAttr(parser, "y")?.let { frame.y = it }
            }
            "ext" -> {
                emuAttr(parser, "cx")?.let { frame.w = it }
                emuAttr(parser, "cy")?.let { frame.h = it }
            }
            "chart" -> frame.chartRid = OoxmlXml.attrNs(parser, RELS_NS, "id") ?: OoxmlXml.attr(parser, "id")
            "relIds" -> frame.dmRid = OoxmlXml.attrNs(parser, RELS_NS, "dm")
            "tbl" -> parseSlideTable(parser, ctx, tableParas)
        }
    }

    /** Chart frame, when a chart part resolved. */
    private fun chartFrame(ctx: SlideCtx, frame: GraphicFrameAcc): OdfSlideElement? {
        val chartRid = frame.chartRid ?: return null
        val target = ctx.rels[chartRid]?.target
        val chart = target?.let { ctx.pkg.entries[it] }?.let { OoxmlChart.parse(it, ctx.theme) }
        return chart?.let {
            OdfSlideElement.Frame(OdfFrame(frame.x, frame.y, frame.w, frame.h, emptyList(), chart = it))
        }
    }

    /** Diagram frame, when SmartArt text extracted. */
    private fun diagramFrame(ctx: SlideCtx, frame: GraphicFrameAcc): OdfSlideElement? {
        val dmRid = frame.dmRid ?: return null
        val lines = OoxmlDiagram.extractText(ctx.pkg, ctx.rels[dmRid]?.target)
        if (lines.isEmpty()) return null
        return OdfSlideElement.Frame(OdfFrame(
            frame.x,
            frame.y,
            frame.w,
            frame.h.coerceAtLeast(MIN_FRAME_HEIGHT),
            lines.map { OdfParagraph(listOf(OdfSpan(it))) }))
    }

    /** Flattens an a:tbl into one paragraph per row, cells separated by tabs (best-effort). */
    private fun parseSlideTable(parser: XmlPullParser, ctx: SlideCtx, out: MutableList<OdfParagraph>) {
        val depth = parser.depth
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "tbl")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "tr") {
                out.add(OdfParagraph(parseSlideRow(parser, ctx)))
            }
            e = parser.next()
        }
    }

    /** One slide-table row → spans (cells separated by tabs). */
    private fun parseSlideRow(parser: XmlPullParser, ctx: SlideCtx): List<OdfSpan> {
        val spans = mutableListOf<OdfSpan>()
        val rd = parser.depth
        var ev = parser.next()
        var firstCell = true
        while (!(ev == XmlPullParser.END_TAG && parser.depth == rd && parser.name == "tr")) {
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG && parser.name == "tc") {
                if (!firstCell) spans.add(OdfSpan("\t"))
                firstCell = false
                val cellParas = mutableListOf<OdfParagraph>()
                parseTxBody(parser, ctx, cellParas)
                for (p in cellParas) spans.addAll(p.spans)
            }
            ev = parser.next()
        }
        return spans.ifEmpty { listOf(OdfSpan("")) }
    }

    // ---- Text ----

    private fun parseTxBody(parser: XmlPullParser, ctx: SlideCtx, out: MutableList<OdfParagraph>) {
        val start = out.size
        val depth = parser.depth
        val endTag = parser.name  // txBody or txbx
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG && parser.name == "p") parseDrawingParagraph(
                parser,
                ctx)?.let { out.add(it) }
            e = parser.next()
        }
        // Assign running 1/2/3 numbering to contiguous numbered (buAutoNum) items per level.
        val counters = HashMap<Int, Int>()
        for (i in start until out.size) {
            val p = out[i]
            if (p.listType == ListType.NUMBERED) {
                val n = (counters[p.listLevel] ?: 0) + 1
                counters[p.listLevel] = n
                counters.keys.filter { it > p.listLevel }.toList().forEach { counters.remove(it) }
                out[i] = p.copy(listItemIndex = n)
            } else {
                counters.keys.filter { it >= p.listLevel }.toList().forEach { counters.remove(it) }
            }
        }
    }

    private fun parseDrawingParagraph(parser: XmlPullParser, ctx: SlideCtx): OdfParagraph? {
        val depth = parser.depth
        val para = DrawingParaAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "p")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyDrawingParaTag(parser, ctx, para)
            e = parser.next()
        }
        if (para.spans.isEmpty()) return null
        return OdfParagraph(
            spans = para.spans,
            alignment = para.align,
            listLevel = para.level,
            listType = para.listType ?: ListType.BULLET,
            style = if (para.listType != null) ParagraphStyle.LIST_ITEM else ParagraphStyle.BODY,
            listBulletChar = para.bulletChar,
            listNumberFormat = para.numFmt
        )
    }

    /** Drawing-paragraph accumulation state. */
    private class DrawingParaAcc(
        val spans: MutableList<OdfSpan> = mutableListOf(),
        var align: TextAlign? = null,
        var level: Int = 0,
        var listType: ListType? = null,
        var bulletChar: String = "\u2022",
        var numFmt: String = "1",
    )

    /** Apply one drawing-paragraph child tag. */
    private fun applyDrawingParaTag(parser: XmlPullParser, ctx: SlideCtx, para: DrawingParaAcc) {
        when (parser.name) {
            "pPr" -> {
                para.align = textAlignOf(OoxmlXml.attr(parser, "algn"))
                para.level = OoxmlXml.attr(parser, "lvl")?.toIntOrNull() ?: 0
                val bul = parseBullet(parser)
                para.listType = bul.first
                para.bulletChar = bul.second ?: para.bulletChar
                para.numFmt = bul.third ?: para.numFmt
            }
            "r" -> parseDrawingRun(parser, ctx)?.let { para.spans.add(it) }
            "br" -> para.spans.add(OdfSpan("\n"))
            "fld" -> parseDrawingRun(parser, ctx)?.let { para.spans.add(it) }
        }
    }

    /** Returns (listType or null, bulletChar, numberFormat) from an a:pPr's bullet children. */
    private fun parseBullet(parser: XmlPullParser): Triple<ListType?, String?, String?> {
        val depth = parser.depth
        var type: ListType? = null; var char: String? = null; var numFmt: String? = null
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "pPr")) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "buChar" -> { type = ListType.BULLET; char = OoxmlXml.attr(parser, "char") }
                "buAutoNum" -> { type = ListType.NUMBERED; numFmt = mapAutoNum(OoxmlXml.attr(parser, "type")) }
                "buNone" -> type = null
            }
            e = parser.next()
        }
        return Triple(type, char, numFmt)
    }

    private fun mapAutoNum(type: String?): String = when {
        type == null -> "1"
        type.startsWith("alphaLc") -> "a"
        type.startsWith("alphaUc") -> "A"
        type.startsWith("romanLc") -> "i"
        type.startsWith("romanUc") -> "I"
        else -> "1"
    }

    private fun parseDrawingRun(parser: XmlPullParser, ctx: SlideCtx): OdfSpan? {
        val endTag = parser.name  // r or fld
        val depth = parser.depth
        val run = DrawingRunAcc()
        var e = parser.next()
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name == endTag)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyDrawingRunTag(parser, ctx, run)
            e = parser.next()
        }
        if (run.sb.isEmpty()) return null
        return OdfSpan(
            text = run.sb.toString(), bold = run.bold, italic = run.italic, underline = run.underline,
            strikethrough = run.strike,
            fontSize = run.size, fontFamily = run.font, color = run.color, superscript = run.superscript,
            subscript = run.subscript,
            href = run.href, letterSpacing = run.letterSpacing,
            textTransform = if (run.caps) "uppercase" else null
        )
    }

    /** Drawing-run accumulation state. */
    private class DrawingRunAcc(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strike: Boolean = false,
        var color: Long? = null,
        var size: Float? = null,
        var font: String? = null,
        var superscript: Boolean = false,
        var subscript: Boolean = false,
        var href: String? = null,
        var letterSpacing: Float? = null,
        var caps: Boolean = false,
        val sb: StringBuilder = StringBuilder(),
    )

    /** Apply one drawing-run child tag. */
    private fun applyDrawingRunTag(parser: XmlPullParser, ctx: SlideCtx, run: DrawingRunAcc) {
        when (parser.name) {
            "rPr", "defRPr", "endParaRPr" -> applyDrawingRPr(parser, ctx, run)
            "t" -> run.sb.append(OoxmlXml.readElementText(parser, "t"))
        }
    }

    /** Run properties from an a:rPr tag. */
    private fun applyDrawingRPr(parser: XmlPullParser, ctx: SlideCtx, run: DrawingRunAcc) {
        applyRPrEmphasisTag(parser, run)
        applyRPrMetricsTag(parser, run)
        val r = parseRunColorFontLink(parser, ctx)
        run.color = run.color ?: r.first
        run.font = run.font ?: r.second
        run.href = run.href ?: r.third
    }

    private fun applyRPrEmphasisTag(parser: XmlPullParser, run: DrawingRunAcc) {
        if (OoxmlXml.boolAttr(OoxmlXml.attr(parser, "b")) && OoxmlXml.attr(parser, "b") != null) run.bold = true
        if (OoxmlXml.attr(parser, "b") == "1") run.bold = true
        if (OoxmlXml.attr(parser, "i") == "1") run.italic = true
        if (OoxmlXml.attr(parser, "u")?.let { it != "none" } == true) run.underline = true
        if (OoxmlXml.attr(parser, "strike")?.let { it != "noStrike" } == true) run.strike = true
    }

    private fun applyRPrMetricsTag(parser: XmlPullParser, run: DrawingRunAcc) {
        OoxmlXml.attr(parser, "sz")?.toFloatOrNull()?.let { run.size = it / PERCENT_DIVISOR }
        OoxmlXml.attr(parser, "spc")?.toIntOrNull()?.let { run.letterSpacing = OoxmlUnits.hundredthPtToPt(it) }
        when (OoxmlXml.attr(parser, "cap")) { "all", "small" -> run.caps = true }
        OoxmlXml.attr(
            parser,
            "baseline")
        ?.toIntOrNull()?.let { if (it > 0) run.superscript = true else if (it < 0) run.subscript = true }
    }

    private class RunLinkAcc(
        var color: Long? = null,
        var font: String? = null,
        var href: String? = null,
        var inFill: Boolean = false,
    )

    /** Parses solidFill color, latin font, and hlinkClick target from within an a:rPr. */
    private fun parseRunColorFontLink(parser: XmlPullParser, ctx: SlideCtx): Triple<Long?, String?, String?> {
        val depth = parser.depth
        val acc = RunLinkAcc()
        var e = parser.next()
        val endTags = setOf("rPr", "defRPr", "endParaRPr")
        while (!(e == XmlPullParser.END_TAG && parser.depth == depth && parser.name in endTags)) {
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.START_TAG) applyRunLinkTag(parser, ctx, acc)
            else if (e == XmlPullParser.END_TAG && parser.name == "solidFill") acc.inFill = false
            e = parser.next()
        }
        return Triple(acc.color, acc.font, acc.href)
    }

    private fun applyRunLinkTag(parser: XmlPullParser, ctx: SlideCtx, acc: RunLinkAcc) {
        when (parser.name) {
            "solidFill" -> acc.inFill = true
            "latin" -> acc.font = OoxmlXml.attr(parser, "typeface")
            "hlinkClick" -> applyRunLinkHrefTag(parser, ctx, acc)
            in COLOR_TAGS -> applyRunLinkColorTag(parser, ctx, acc)
        }
    }

    private fun applyRunLinkHrefTag(parser: XmlPullParser, ctx: SlideCtx, acc: RunLinkAcc) {
        val rId = OoxmlXml.attrNs(
            parser,
            RELS_NS,
            "id") ?: OoxmlXml.attr(parser, "id")
        acc.href = ctx.rels[rId]?.target
    }

    private fun applyRunLinkColorTag(parser: XmlPullParser, ctx: SlideCtx, acc: RunLinkAcc) {
        if (acc.inFill && acc.color == null) acc.color = OoxmlColor.parse(parser, ctx.theme)
    }

    // ---- Notes ----

    private fun parseNotes(pkg: OoxmlPackage, rels: Map<String, OoxmlPackage.Rel>): List<OdfParagraph> {
        val notesPart =
            rels.values.firstOrNull { it.type?.endsWith("notesSlide") == true }?.target ?: return emptyList()
        val xml = pkg.entries[notesPart] ?: return emptyList()
        val ctx = SlideCtx(pkg, notesPart, OoxmlTheme.DEFAULT, pkg.relsFor(notesPart))
        val parser = OoxmlXml.newParser(xml)
        val paras = mutableListOf<OdfParagraph>()
        var e = parser.eventType
        var inBody = false
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "txBody") { parseTxBody(
                parser,
                ctx,
                paras); inBody = true }
            e = parser.next()
        }
        @Suppress("UNUSED_VALUE") run { inBody = inBody }
        return paras
    }
}
