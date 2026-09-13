package com.vayunmathur.office

import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.odf.OdfMath
import com.vayunmathur.library.ui.odf.OdfMetadata
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.ParagraphStyle

@Composable
internal fun MetadataDialog(metadata: OdfMetadata, onSave: (OdfMetadata) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(metadata.title ?: "") }
    var author by remember { mutableStateOf(metadata.author ?: "") }
    var subject by remember { mutableStateOf(metadata.subject ?: "") }
    var description by remember { mutableStateOf(metadata.description ?: "") }
    var keywords by remember { mutableStateOf(metadata.keywords.joinToString(", ")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.document_info)) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                TextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.meta_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                TextField(value = author, onValueChange = { author = it }, label = { Text(stringResource(R.string.meta_author)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                TextField(value = subject, onValueChange = { subject = it }, label = { Text(stringResource(R.string.meta_subject)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                TextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.meta_description)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                TextField(value = keywords, onValueChange = { keywords = it }, label = { Text(stringResource(R.string.meta_keywords)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                metadata.creationDate?.let { MetadataRow(stringResource(R.string.meta_created), it) }
                metadata.modifiedDate?.let { MetadataRow(stringResource(R.string.meta_modified), it) }
                metadata.pageCount?.let { MetadataRow(stringResource(R.string.meta_pages), it.toString()) }
                metadata.wordCount?.let { MetadataRow(stringResource(R.string.meta_words), it.toString()) }
                metadata.fileSize?.let { MetadataRow("File Size", formatFileSize(it)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(metadata.copy(
                    title = title.ifBlank { null },
                    author = author.ifBlank { null },
                    subject = subject.ifBlank { null },
                    description = description.ifBlank { null },
                    keywords = keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                ))
                onDismiss()
            }) { Text(stringResource(UiR.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } })
}

@Composable private fun MetadataRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) { Text("$label: ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.bodyMedium) }
}

internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

// --- Print ---

internal fun printDocument(activity: ComponentActivity, document: OdfDocument) {
    val printManager = activity.getSystemService(android.content.Context.PRINT_SERVICE) as? PrintManager ?: return
    val html = documentToHtml(document)
    val webView = WebView(activity)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            @Suppress("DEPRECATION")
            val printAdapter = webView.createPrintDocumentAdapter(document.title)
            printManager.print(document.title, printAdapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        }
    }
    webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
}

internal fun documentToHtml(document: OdfDocument): String {
    val sb = StringBuilder()
    sb.append("""<!DOCTYPE html><html><head><meta charset="UTF-8"><style>body{font-family:sans-serif;padding:16px}table{border-collapse:collapse;width:100%;margin:8px 0}td,th{border:1px solid #ccc;padding:6px 8px}h1,h2,h3,h4{margin:8px 0}.slide{border:1px solid #ccc;padding:24px;margin:16px 0;background:#f9f9f9}</style></head><body>""")
    when (document) {
        is OdfDocument.TextDocument -> {
            for (block in document.content) when (block) {
                is OdfContentBlock.Paragraph -> {
                    val tag = when (block.paragraph.style) { ParagraphStyle.HEADING1 -> "h1"; ParagraphStyle.HEADING2 -> "h2"; ParagraphStyle.HEADING3 -> "h3"; ParagraphStyle.HEADING4 -> "h4"; ParagraphStyle.LIST_ITEM -> "li"; else -> "p" }
                    sb.append("<$tag>")
                    for (span in block.paragraph.spans) { var s = span.text.replace("&", "&amp;").replace("<", "&lt;"); if (span.bold) s = "<b>$s</b>"; if (span.italic) s = "<i>$s</i>"; if (span.underline) s = "<u>$s</u>"; if (span.strikethrough) s = "<s>$s</s>"; if (span.href != null) s = """<a href="${span.href}">$s</a>"""; sb.append(s) }
                    sb.append("</$tag>")
                }
                is OdfContentBlock.Table -> { sb.append("<table>"); for (row in block.table.rows) { sb.append("<tr>"); for (cell in row.cells) { if (cell.isCovered) continue; sb.append("<td"); if (cell.colSpan > 1) sb.append(""" colspan="${cell.colSpan}""""); if (cell.rowSpan > 1) sb.append(""" rowspan="${cell.rowSpan}""""); sb.append(">"); for (para in cell.paragraphs) for (span in para.spans) sb.append(span.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</td>") }; sb.append("</tr>") }; sb.append("</table>") }
                is OdfContentBlock.Image -> sb.append("<p>[Image]</p>")
                is OdfContentBlock.Chart -> sb.append("<p>[Chart]</p>")
                is OdfContentBlock.Formula -> sb.append("<p><i>${OdfMath.parse(block.mathml)?.let { OdfMath.toText(it) }.orEmpty().replace("&", "&amp;").replace("<", "&lt;")}</i></p>")
                is OdfContentBlock.PageBreak -> sb.append("""<hr style="page-break-after:always">""")
                is OdfContentBlock.TableOfContents -> {
                    sb.append("<h2>${block.title.replace("&", "&amp;").replace("<", "&lt;")}</h2>")
                    for (entry in block.entries) sb.append("<p>${entry.spans.joinToString("") { it.text }.replace("&", "&amp;").replace("<", "&lt;")}</p>")
                }
                is OdfContentBlock.SectionStart -> sb.append(if (block.columnCount > 1) """<div style="column-count:${block.columnCount}">""" else "<div>")
                is OdfContentBlock.SectionEnd -> sb.append("</div>")
            }
        }
        is OdfDocument.Spreadsheet -> { for (sheet in document.sheets) { sb.append("<h2>${sheet.name}</h2><table>"); for (row in sheet.rows) { sb.append("<tr>"); for (cell in row.cells) { if (cell.isCovered) continue; sb.append("<td"); if (cell.spannedColumns > 1) sb.append(""" colspan="${cell.spannedColumns}""""); sb.append(">${cell.text.replace("&", "&amp;").replace("<", "&lt;")}</td>") }; sb.append("</tr>") }; sb.append("</table>") } }
        is OdfDocument.Presentation -> { for (slide in document.slides) { sb.append("""<div class="slide"><b>${slide.name}</b>"""); for (el in slide.elements) when (el) { is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") }; is OdfSlideElement.Shape -> for (p in el.shape.text) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") } }; sb.append("</div>") } }
        is OdfDocument.Drawing -> { for (page in document.pages) { sb.append("""<div class="slide"><b>${page.name}</b>"""); for (el in page.elements) when (el) { is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") }; is OdfSlideElement.Shape -> for (p in el.shape.text) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") } }; sb.append("</div>") } }
    }
    sb.append("</body></html>")
    return sb.toString()
}
