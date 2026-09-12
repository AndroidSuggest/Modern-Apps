package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.*
import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.zip.ZipInputStream

object OdfParser {

    fun parse(context: Context, uri: Uri, fileName: String): OdfDocument {
        val entries = extractAllEntries(context, uri)
        // Encrypted ODF detection (J67): manifest declares per-file encryption-data.
        entries.textEntries["META-INF/manifest.xml"]?.let { manifest ->
            if (manifest.contains("manifest:encryption-data") || manifest.contains("encryption-data")) {
                throw IllegalArgumentException("This document is password-protected (encrypted ODF), which is not supported.")
            }
        }
        val contentXml = entries.textEntries["content.xml"]
            ?: throw IllegalArgumentException("Not a valid ODF file: missing content.xml")
        val stylesXml = entries.textEntries["styles.xml"]
        val metaXml = entries.textEntries["meta.xml"]

        val styleMap = mutableMapOf<String, StyleInfo>()
        stylesXml?.let { styleMap.putAll(parseStyles(it)) }
        styleMap.putAll(parseStyles(contentXml))

        val listStyleMap = mutableMapOf<String, ListStyleInfo>()
        stylesXml?.let { listStyleMap.putAll(parseListStyles(it)) }
        listStyleMap.putAll(parseListStyles(contentXml))

        val numberStyleMap = mutableMapOf<String, OdfNumberFormat>()
        stylesXml?.let { numberStyleMap.putAll(parseNumberStyles(it)) }
        numberStyleMap.putAll(parseNumberStyles(contentXml))

        gradientDefs = buildMap {
            stylesXml?.let { putAll(parseGradients(it)) }
            putAll(parseGradients(contentXml))
        }

        var metadata = metaXml?.let { parseMetadata(it) } ?: OdfMetadata()

        // File size
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                metadata = metadata.copy(fileSize = fd.statSize)
            }
        } catch (_: Exception) {}

        val images = entries.binaryEntries
        val objectContents = entries.textEntries.filterKeys { it.startsWith("Object") }
        // Freeze-pane config from settings.xml, keyed by sheet name. (C2)
        val freezeMap = entries.textEntries["settings.xml"]?.let { parseSettings(it) } ?: emptyMap()

        // Parse headers/footers from styles.xml
        val headerFooter = stylesXml?.let { parseHeaderFooter(it, styleMap) }
        // Parse page geometry from styles.xml (Priority 7)
        val pageSetup = stylesXml?.let { parsePageSetup(it) }

        val type = detectType(contentXml)
        return when (type) {
            DocType.TEXT -> parseTextDocument(contentXml, styleMap, listStyleMap, fileName, metadata, images, headerFooter, objectContents, pageSetup)
            DocType.SPREADSHEET -> parseSpreadsheet(contentXml, styleMap, numberStyleMap, fileName, metadata, images, objectContents, freezeMap)
            DocType.PRESENTATION -> parsePresentation(contentXml, styleMap, fileName, metadata, images, objectContents)
            DocType.DRAWING -> parseDrawing(contentXml, styleMap, fileName, metadata, images, objectContents)
        }
    }

    /** Parses a flat-ODF/content.xml string as a text document. Test seam for the body parser. */
    internal fun parseTextXml(xml: String, fileName: String = "test"): OdfDocument.TextDocument =
        parseTextDocument(xml, parseStyles(xml), parseListStyles(xml), fileName, OdfMetadata(), emptyMap(), null)

    /** Parses settings.xml for per-sheet freeze-pane info: sheet name -> (freezeRows, freezeCols). (C2) */
    private fun parseSettings(xml: String): Map<String, Pair<Int, Int>> {
        val out = mutableMapOf<String, Pair<Int, Int>>()
        val parser = newParser(xml)
        var e = parser.eventType
        var inTables = false
        var tablesDepth = -1
        var curSheet: String? = null
        var entryDepth = -1
        var hMode = 0; var vMode = 0; var hPos = 0; var vPos = 0
        var curItem: String? = null
        val itemText = StringBuilder()
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "config-item-map-named" -> if (getAttr(parser, "name") == "Tables") { inTables = true; tablesDepth = parser.depth }
                    "config-item-map-entry" -> if (inTables && curSheet == null) { curSheet = getAttr(parser, "name"); entryDepth = parser.depth; hMode = 0; vMode = 0; hPos = 0; vPos = 0 }
                    "config-item" -> if (curSheet != null) { curItem = getAttr(parser, "name"); itemText.setLength(0) }
                }
                XmlPullParser.TEXT -> if (curItem != null) itemText.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "config-item" -> {
                        val v = itemText.toString().trim().toIntOrNull() ?: 0
                        when (curItem) {
                            "HorizontalSplitMode" -> hMode = v
                            "VerticalSplitMode" -> vMode = v
                            "HorizontalSplitPosition" -> hPos = v
                            "VerticalSplitPosition" -> vPos = v
                            "PositionRight" -> if (hPos == 0) hPos = v
                            "PositionBottom" -> if (vPos == 0) vPos = v
                        }
                        curItem = null
                    }
                    "config-item-map-entry" -> if (curSheet != null && parser.depth == entryDepth) {
                        val cols = if (hMode == 2) hPos else 0
                        val rows = if (vMode == 2) vPos else 0
                        if (rows > 0 || cols > 0) out[curSheet] = rows to cols
                        curSheet = null
                    }
                    "config-item-map-named" -> if (inTables && parser.depth == tablesDepth) inTables = false
                }
            }
            e = parser.next()
        }
        return out
    }

    // --- ZIP extraction ---

    private data class ZipEntries(
        val textEntries: Map<String, String>,
        val binaryEntries: Map<String, ByteArray>
    )

    private fun extractAllEntries(context: Context, uri: Uri): ZipEntries {
        val textEntries = mutableMapOf<String, String>()
        val binaryEntries = mutableMapOf<String, ByteArray>()
        val textFiles = setOf("content.xml", "styles.xml", "meta.xml", "settings.xml", "META-INF/manifest.xml")

        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        // Flat ODF (.fodt/.fods/.fodp) is a single XML file, not a zip. (J70)
        val isZip = raw.size >= 2 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte()
        if (!isZip) {
            val xml = String(raw, Charsets.UTF_8)
            if (xml.contains("office:document")) {
                textEntries["content.xml"] = xml
                textEntries["styles.xml"] = xml
                textEntries["meta.xml"] = xml
            }
            return ZipEntries(textEntries, binaryEntries)
        }

        ZipInputStream(raw.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name in textFiles -> textEntries[name] = zip.bufferedReader().readText()
                    name.startsWith("Object") && name.endsWith("content.xml") -> textEntries[name] = zip.bufferedReader().readText()
                    !entry.isDirectory && (
                        name.startsWith("Pictures/") || name.startsWith("media/") ||
                        name.startsWith("ObjectReplacements") || name.startsWith("Thumbnails/")
                        ) -> binaryEntries[name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return ZipEntries(textEntries, binaryEntries)
    }

    // --- Document type detection ---

    private enum class DocType { TEXT, SPREADSHEET, PRESENTATION, DRAWING }

    private fun detectType(contentXml: String): DocType = when {
        contentXml.contains("<office:text") -> DocType.TEXT
        contentXml.contains("<office:spreadsheet") -> DocType.SPREADSHEET
        contentXml.contains("<office:presentation") -> DocType.PRESENTATION
        contentXml.contains("<office:drawing") -> DocType.DRAWING
        else -> DocType.TEXT
    }

    // --- XML helpers (shared with the OdfParser*.kt extension files) ---

    internal fun newParser(xml: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        return parser
    }

    internal fun getAttr(parser: XmlPullParser, localName: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) return parser.getAttributeValue(i)
        }
        return null
    }

    internal fun skipElement(parser: XmlPullParser) {
        val depth = parser.depth
        var eventType = parser.next()
        while (!(eventType == XmlPullParser.END_TAG && parser.depth == depth)) {
            eventType = parser.next()
        }
    }

    internal fun parseDimension(value: String?): Float {
        if (value == null) return 0f
        val numeric = value.replace(Regex("[^0-9.\\-]"), "")
        val base = numeric.toFloatOrNull() ?: 0f
        return when {
            value.endsWith("cm") -> base * 37.8f
            value.endsWith("mm") -> base * 3.78f
            value.endsWith("in") -> base * 96f
            value.endsWith("pc") -> base * 16f      // pica = 12pt
            value.endsWith("pt") -> base * 1.33f
            value.endsWith("em") -> base * 16f      // relative to a 12pt default
            else -> base
        }
    }

    internal fun parseColor(hex: String): Long? {
        return try {
            var colorStr = hex.trim().removePrefix("#")
            // Expand 3-digit shorthand (#FFF -> #FFFFFF).
            if (colorStr.length == 3) colorStr = colorStr.map { "$it$it" }.joinToString("")
            if (colorStr.length != 6) return null
            0xFF000000L or colorStr.toLong(16)
        } catch (_: Exception) { null }
    }

    /** Parses an fo:clip="rect(top right bottom left)" value (percent tokens) into [left, top, right, bottom] fractions. (Phase 5) */
    internal fun parseClip(value: String?): FloatArray? {
        if (value == null) return null
        val inner = value.substringAfter("rect(", "").substringBefore(")")
        if (inner.isBlank()) return null
        val parts = inner.trim().split(Regex("[ ,]+"))
        if (parts.size < 4) return null
        fun f(s: String): Float {
            val t = s.trim()
            return when {
                t.endsWith("%") -> (t.dropLast(1).toFloatOrNull() ?: 0f) / 100f
                else -> 0f
            }.coerceIn(0f, 0.95f)
        }
        val top = f(parts[0]); val right = f(parts[1]); val bottom = f(parts[2]); val left = f(parts[3])
        if (top == 0f && right == 0f && bottom == 0f && left == 0f) return null
        return floatArrayOf(left, top, right, bottom)
    }

    /** Parses fo:clip="rect(top right bottom left)" absolute lengths into [left, top, right, bottom] fractions
     *  using the natural image size (px@96). Returns null if no crop. (A7) */
    internal fun parseClipLengths(value: String?, wPx: Float, hPx: Float): FloatArray? {
        if (value == null || wPx <= 0f || hPx <= 0f) return null
        val inner = value.substringAfter("rect(", "").substringBefore(")")
        if (inner.isBlank()) return null
        val parts = inner.trim().split(Regex("[ ,]+"))
        if (parts.size < 4) return null
        // If any token is a percentage, fall back to percent parsing.
        if (parts.any { it.trim().endsWith("%") }) return parseClip(value)
        fun frac(token: String, basePx: Float): Float = (parseDimension(token.trim()) / basePx).coerceIn(0f, 0.95f)
        val top = frac(parts[0], hPx); val right = frac(parts[1], wPx); val bottom = frac(parts[2], hPx); val left = frac(parts[3], wPx)
        if (top == 0f && right == 0f && bottom == 0f && left == 0f) return null
        return floatArrayOf(left, top, right, bottom)
    }

    /** Decodes the intrinsic pixel size of an encoded image without allocating the full bitmap. (A7) */
    internal fun decodeNaturalSize(bytes: ByteArray): Pair<Float, Float> {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outWidth.toFloat() to opts.outHeight.toFloat()
        } catch (_: Exception) { 0f to 0f }
    }

    /** Converts an ODF draw:transform rotate(theta) into degrees clockwise for Compose (E38). */
    internal fun parseRotationDegrees(transform: String?): Float {
        if (transform == null) return 0f
        val m = Regex("rotate\\(([-0-9.]+)\\)").find(transform) ?: return 0f
        val rad = m.groupValues[1].toFloatOrNull() ?: return 0f
        return -(rad * 180.0 / Math.PI).toFloat()
    }

    internal const val LINK_COLOR = 0xFF0066CCL
    // Deletion-region text keyed by change-id, populated while parsing text:tracked-changes
    // and consumed by the inline parser to render struck-through deleted text. (Priority 6)
    internal var trackedDeletionText: Map<String, String> = emptyMap()
    /** Gradient definitions (draw:gradient name -> def), set during parse(). (Round 3) */
    internal var gradientDefs: Map<String, OdfGradient> = emptyMap()
    // ODF inline text-field element local names recognized by the inline parser. (Priority 2)
    internal val FIELD_TAGS = setOf(
        "date", "time", "page-number", "page-count", "file-name", "author-name",
        "author-initials", "title", "subject", "description", "chapter", "sheet-name",
        "creation-date", "modification-date", "editing-cycles",
        // Additional field elements (Phase 2).
        "variable-set", "variable-get", "user-field-get", "sequence", "placeholder",
        "conditional-text", "hidden-text", "page-continuation", "bibliography-mark"
    )
}
