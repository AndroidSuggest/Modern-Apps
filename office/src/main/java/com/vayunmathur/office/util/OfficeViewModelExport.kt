package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.vayunmathur.office.R

// --- Export (split from OfficeViewModel.kt for file length) ---

fun OfficeViewModel.exportCsv(delimiter: Char = ','): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return ""
    val sb = StringBuilder()
    val sheet = doc.sheets.firstOrNull() ?: return ""
    for (row in sheet.rows) {
        sb.appendLine(row.cells.joinToString(delimiter.toString()) { cell ->
            val text = cell.text
            if (text.contains(delimiter) || text.contains('"') || text.contains('\n') || text.contains('\r')) {
                "\"${text.replace("\"", "\"\"")}\""
            } else text
        })
    }
    return sb.toString()
}

/** Markdown export for text documents (empty for other types). */
fun OfficeViewModel.exportMarkdown(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
    return MarkdownOdfConverter.odfToMarkdown(doc)
}

/** Best-effort OOXML (docx/xlsx/pptx) export. */
fun OfficeViewModel.exportOoxml(): ByteArray {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return ByteArray(0)
    return OoxmlExporter.export(doc)
}

fun OfficeViewModel.ooxmlExtension(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return "docx"
    return OoxmlExporter.extensionFor(doc)
}

/** HTML export for text documents (empty for other types). */
fun OfficeViewModel.exportHtml(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
    return HtmlOdfConverter.odfToHtml(doc)
}

/** RTF export for text documents (empty for other types). */
fun OfficeViewModel.exportRtf(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
    return RtfOdfConverter.odfToRtf(doc)
}

/** LaTeX export for text documents (empty for other types). */
fun OfficeViewModel.exportLatex(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
    return LatexExporter.export(doc)
}

/** EPUB export for text documents (empty for other types). */
fun OfficeViewModel.exportEpub(): ByteArray {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ByteArray(0)
    return EpubExporter.export(doc)
}

/** PDF export for text documents (empty for other types). */
fun OfficeViewModel.exportPdf(): ByteArray {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ByteArray(0)
    return PdfExporter.export(doc)
}

/** Flat ODF (.fodt/.fods/.fodp) export (K75). */
fun OfficeViewModel.exportFlat(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return ""
    return OdfSerializer.serializeFlat(doc)
}

fun OfficeViewModel.exportAsPlainText(): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document ?: return ""
    val sb = StringBuilder()
    when (doc) {
        is OdfDocument.TextDocument -> {
            for (block in doc.content) {
                when (block) {
                    is OdfContentBlock.Paragraph -> sb.appendLine(block.paragraph.spans.joinToString("") { it.text })
                    is OdfContentBlock.Table -> {
                        for (row in block.table.rows)
                            sb.appendLine(row.cells.filterNot { it.isCovered }.joinToString("\t") { cell -> cell.paragraphs.joinToString(" ") { p -> p.spans.joinToString("") { it.text } } })
                    }
                    is OdfContentBlock.PageBreak -> sb.appendLine("---")
                    is OdfContentBlock.Image -> sb.appendLine("[Image]")
                    is OdfContentBlock.Chart -> sb.appendLine("[Chart]")
                    is OdfContentBlock.Formula -> sb.appendLine("[Formula] " + OdfMath.parse(block.mathml)?.let { OdfMath.toText(it) }.orEmpty())
                    is OdfContentBlock.TableOfContents -> {
                        sb.appendLine(block.title)
                        for (entry in block.entries) sb.appendLine(entry.spans.joinToString("") { it.text })
                    }
                    is OdfContentBlock.SectionStart, OdfContentBlock.SectionEnd -> {}
                }
            }
        }
        is OdfDocument.Spreadsheet -> {
            for (sheet in doc.sheets) {
                sb.appendLine("=== ${sheet.name} ===")
                for (row in sheet.rows) sb.appendLine(row.cells.filterNot { it.isCovered }.joinToString("\t") { it.text })
                sb.appendLine()
            }
        }
        is OdfDocument.Presentation -> {
            for (slide in doc.slides) {
                sb.appendLine("=== ${slide.name} ===")
                for (el in slide.elements) when (el) {
                    is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) sb.appendLine(p.spans.joinToString("") { it.text })
                    is OdfSlideElement.Shape -> for (p in el.shape.text) sb.appendLine(p.spans.joinToString("") { it.text })
                }
                sb.appendLine()
            }
        }
        is OdfDocument.Drawing -> {
            for (page in doc.pages) {
                sb.appendLine("=== ${page.name} ===")
                for (el in page.elements) when (el) {
                    is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) sb.appendLine(p.spans.joinToString("") { it.text })
                    is OdfSlideElement.Shape -> for (p in el.shape.text) sb.appendLine(p.spans.joinToString("") { it.text })
                }
                sb.appendLine()
            }
        }
    }
    return sb.toString()
}
