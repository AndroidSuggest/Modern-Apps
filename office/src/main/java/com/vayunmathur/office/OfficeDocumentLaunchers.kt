package com.vayunmathur.office

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.save
import com.vayunmathur.office.util.exportAsPlainText
import com.vayunmathur.office.util.exportCsv
import com.vayunmathur.office.util.exportEpub
import com.vayunmathur.office.util.exportFlat
import com.vayunmathur.office.util.exportHtml
import com.vayunmathur.office.util.exportLatex
import com.vayunmathur.office.util.exportMarkdown
import com.vayunmathur.office.util.exportOoxml
import com.vayunmathur.office.util.exportPdf
import com.vayunmathur.office.util.exportRtf

/**
 * All activity-result launchers used by [DocumentScreen] (save/export/import/replace flows).
 *
 * Hoisted verbatim from `DocumentScreen`'s locals (split for file length); captures became
 * explicit parameters, behavior identical.
 */
class DocumentLaunchers(
    val saveAs: ManagedActivityResultLauncher<String, Uri?>,
    val csvExport: ManagedActivityResultLauncher<String, Uri?>,
    val imagePicker: ManagedActivityResultLauncher<String, Uri?>,
    val flatExport: ManagedActivityResultLauncher<String, Uri?>,
    val markdownExport: ManagedActivityResultLauncher<String, Uri?>,
    val txtExport: ManagedActivityResultLauncher<String, Uri?>,
    val tsvExport: ManagedActivityResultLauncher<String, Uri?>,
    val ooxmlExport: ManagedActivityResultLauncher<String, Uri?>,
    val htmlExport: ManagedActivityResultLauncher<String, Uri?>,
    val rtfExport: ManagedActivityResultLauncher<String, Uri?>,
    val latexExport: ManagedActivityResultLauncher<String, Uri?>,
    val epubExport: ManagedActivityResultLauncher<String, Uri?>,
    val pdfExport: ManagedActivityResultLauncher<String, Uri?>,
    val replaceImage: ManagedActivityResultLauncher<String, Uri?>,
)

@Composable
fun rememberDocumentLaunchers(
    viewModel: OfficeViewModel,
    holder: DocumentScreenState,
    onPickImageBytes: (name: String, bytes: ByteArray) -> Unit,
): DocumentLaunchers {
    val context = LocalContext.current
    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { viewModel.save(it) } }
    val csvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            val csv = viewModel.exportCsv()
            context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(csv) }
        }
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } ?: return@let
                val name = it.lastPathSegment?.substringAfterLast('/') ?: "image.png"
                onPickImageBytes(name, bytes)
            } catch (_: Exception) {}
        }
    }
    val flatExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        uri?.let {
            val xml = viewModel.exportFlat()
            if (xml.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(xml) }
        }
    }
    val markdownExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { val t = viewModel.exportMarkdown(); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val txtExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { val t = viewModel.exportAsPlainText(); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val tsvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
        uri?.let { val t = viewModel.exportCsv('\t'); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val ooxmlExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { val bytes = viewModel.exportOoxml(); if (bytes.isNotEmpty()) context.contentResolver.openOutputStream(it)?.use { o -> o.write(bytes) } }
    }
    val htmlExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        uri?.let { val t = viewModel.exportHtml(); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val rtfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/rtf")) { uri ->
        uri?.let { val t = viewModel.exportRtf(); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val latexExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tex")) { uri ->
        uri?.let { val t = viewModel.exportLatex(); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val epubExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
        uri?.let { val bytes = viewModel.exportEpub(); if (bytes.isNotEmpty()) context.contentResolver.openOutputStream(it)?.use { o -> o.write(bytes) } }
    }
    val pdfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { val bytes = viewModel.exportPdf(); if (bytes.isNotEmpty()) context.contentResolver.openOutputStream(it)?.use { o -> o.write(bytes) } }
    }
    val replaceImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val fn = holder.pendingReplace
        holder.pendingReplace = null
        uri?.let {
            try {
                val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } ?: return@let
                val name = it.lastPathSegment?.substringAfterLast('/') ?: "image.png"
                fn?.invoke(name, bytes)
            } catch (_: Exception) {}
        }
    }
    return DocumentLaunchers(
        saveAsLauncher, csvExportLauncher, imagePickerLauncher, flatExportLauncher,
        markdownExportLauncher, txtExportLauncher, tsvExportLauncher, ooxmlExportLauncher,
        htmlExportLauncher, rtfExportLauncher, latexExportLauncher, epubExportLauncher,
        pdfExportLauncher, replaceImageLauncher,
    )
}
