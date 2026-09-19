package com.vayunmathur.library.ui.odf

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports an [OdfDocument.TextDocument] to a minimal, valid EPUB 2 package: mimetype +
 * META-INF/container.xml + an OPF manifest + NCX table of contents + a single XHTML content
 * document (rendered via [HtmlOdfConverter]). Embedded images are written into the package.
 */
object EpubExporter {

    fun export(doc: OdfDocument.TextDocument): ByteArray {
        val title = doc.title.ifBlank { "Document" }
        val bodyXhtml = toXhtml(doc)
        val images = collectImages(doc)

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            // mimetype must be first and STORED (uncompressed) per the EPUB OCF spec.
            val mime = "application/epub+zip".toByteArray()
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            }
            zos.putNextEntry(mimeEntry); zos.write(mime); zos.closeEntry()
            put(zos, "META-INF/container.xml", CONTAINER)
            put(zos, "OEBPS/content.opf", contentOpf(title, images.keys))
            put(zos, "OEBPS/toc.ncx", tocNcx(title))
            put(zos, "OEBPS/content.xhtml", bodyXhtml)
            for ((name, bytes) in images) { zos.putNextEntry(ZipEntry("OEBPS/$name")); zos.write(bytes); zos.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun put(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name)); zos.write(content.toByteArray(Charsets.UTF_8)); zos.closeEntry()
    }

    private fun collectImages(doc: OdfDocument.TextDocument): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var n = 1
        for (b in doc.content) if (b is OdfContentBlock.Image && b.image.imageData.isNotEmpty()) {
            val ext = b.image.path.substringAfterLast('.', "png").lowercase()
            out["images/img$n.$ext"] = b.image.imageData; n++
        }
        return out
    }

    /** Renders the document as XHTML: HTML from [HtmlOdfConverter] with void tags self-closed. */
    private fun toXhtml(doc: OdfDocument.TextDocument): String {
        val html = HtmlOdfConverter.odfToHtml(doc)
        val bodyStart = html.indexOf("<body>")
        val bodyEnd = html.lastIndexOf("</body>")
        var body = if (bodyStart >= 0 && bodyEnd >= 0) html.substring(bodyStart + 6, bodyEnd) else html
        body = body.replace(Regex("<(br|hr|img|meta)([^>/]*)>")) { m -> "<${m.groupValues[1]}${m.groupValues[2]}/>" }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n<head>\n<title>${esc(doc.title)}</title>\n" +
            "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"/>\n</head>\n<body>\n$body\n</body>\n</html>\n"
    }

    private fun contentOpf(title: String, imageNames: Set<String>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<package xmlns=\"http://www.idpf.org/2007/opf\" unique-identifier=\"bookid\" version=\"2.0\">\n")
        sb.append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">\n")
        sb.append("<dc:title>${esc(title)}</dc:title>\n<dc:language>en</dc:language>\n")
        sb.append("<dc:identifier id=\"bookid\">urn:uuid:${title.hashCode().toUInt()}</dc:identifier>\n</metadata>\n")
        sb.append("<manifest>\n<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        sb.append("<item id=\"content\" href=\"content.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
        var n = 1
        for (img in imageNames) {
            val mt = when (img.substringAfterLast('.')) { "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; else -> "image/png" }
            sb.append("<item id=\"img${n++}\" href=\"$img\" media-type=\"$mt\"/>\n")
        }
        sb.append("</manifest>\n<spine toc=\"ncx\">\n<itemref idref=\"content\"/>\n</spine>\n</package>\n")
        return sb.toString()
    }

    private fun tocNcx(title: String): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n")
        sb.append("<head><meta name=\"dtb:uid\" content=\"urn:uuid:${title.hashCode().toUInt()}\"/></head>\n")
        sb.append("<docTitle><text>${esc(title)}</text></docTitle>\n<navMap>\n")
        sb.append("<navPoint id=\"n1\" playOrder=\"1\"><navLabel><text>${esc(title)}</text></navLabel><content src=\"content.xhtml\"/></navPoint>\n")
        sb.append("</navMap>\n</ncx>\n")
        return sb.toString()
    }

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val CONTAINER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
        "<rootfiles>\n<rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n</rootfiles>\n</container>\n"
}
