package com.vayunmathur.office

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.ui.extractHeadings
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.save
import com.vayunmathur.office.util.needsSaveAs
import com.vayunmathur.office.util.exportAsPlainText
import com.vayunmathur.office.util.fieldDisplayValue
import com.vayunmathur.office.util.insertFieldInRun
import com.vayunmathur.office.util.insertHorizontalLine
import com.vayunmathur.office.util.insertPageBreak
import com.vayunmathur.office.util.insertTableOfContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Office-style menu bar (File / Insert / View) for [DocumentScreen].
 *
 * Moved verbatim (split for file length); the screen's locals became [DocumentScreenState]
 * reads/writes, behavior identical.
 */
@Composable
fun DocumentMenuBar(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    isTextDoc: Boolean,
    isSpreadsheet: Boolean,
    isPresentation: Boolean,
    isOnline: Boolean,
    onlineEnabled: Boolean,
    hasUnsavedChanges: Boolean,
    focusedPara: Int,
    saveAsName: String,
    wordCount: Int,
    charCount: Int,
    readingTime: Int,
    nightMode: Boolean,
    documentDarkMode: Boolean,
    launchers: DocumentLaunchers,
    scope: CoroutineScope,
    drawerState: DrawerState,
    listState: LazyListState,
) {
    val context = LocalContext.current
    val headings = if (document is OdfDocument.TextDocument) extractHeadings(document) else emptyList()
    val bookmarks = if (document is OdfDocument.TextDocument) document.bookmarks else emptyList()
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            // File
            Box {
                TextButton(onClick = { s.fileMenu = true }) { Text(stringResource(R.string.file)) }
                DropdownMenu(expanded = s.fileMenu, onDismissRequest = { s.fileMenu = false }) {
                    if (!isOnline) {
                        DropdownMenuItem(text = { Text(stringResource(UiR.string.save)) }, enabled = hasUnsavedChanges, leadingIcon = { IconSave() }, onClick = { s.fileMenu = false; if (viewModel.needsSaveAs()) launchers.saveAs.launch(saveAsName) else viewModel.save() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.save_as_1)) }, onClick = { s.fileMenu = false; launchers.saveAs.launch(saveAsName) })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.synced_to_cloud)) }, enabled = false, leadingIcon = { IconSave() }, onClick = {})
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.share_online_1)) }, leadingIcon = { IconShare() }, onClick = { s.fileMenu = false; if (onlineEnabled) s.showShareDialog = true else s.showEnableOnlineDialog = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.print_doc)) }, onClick = { s.fileMenu = false; printDocument(activity, document) })
                    viewModel.originalUri?.let { uri ->
                        DropdownMenuItem(text = { Text(stringResource(UiR.string.share)) }, leadingIcon = { IconShare() }, onClick = { s.fileMenu = false; context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "*/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, null)) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.export)) }, leadingIcon = { IconDownload() }, onClick = { s.fileMenu = false; s.exportMenu = true })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(stringResource(UiR.string.settings)) }, leadingIcon = { IconSettings() }, onClick = { s.fileMenu = false; s.showSettings = true })
                }
                // Export submenu (opened from File ▸ Export)
                DropdownMenu(expanded = s.exportMenu, onDismissRequest = { s.exportMenu = false }) {
                    val baseName = document.title.substringBeforeLast('.').ifBlank { "document" }
                    DropdownMenuItem(text = { Text(stringResource(R.string.export_as_text)) }, onClick = { s.exportMenu = false; val t = viewModel.exportAsPlainText(); if (t.isNotEmpty()) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t) }, null)) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.flat_odf)) }, onClick = { s.exportMenu = false; val ext = when { isTextDoc -> ".fodt"; isSpreadsheet -> ".fods"; isPresentation -> ".fodp"; else -> ".fodg" }; launchers.flatExport.launch(document.title.substringBeforeLast('.') + ext) })
                    if (isTextDoc) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.word_docx)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.ooxmlExport.launch("$baseName.docx") } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_pdf)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.pdfExport.launch("$baseName.pdf") } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.html_html)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.htmlExport.launch("$baseName.html") } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.rich_text_rtf)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.rtfExport.launch("$baseName.rtf") } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.epub_epub)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.epubExport.launch("$baseName.epub") } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.latex_tex)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.latexExport.launch("$baseName.tex") } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.markdown_md)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.markdownExport.launch("$baseName.md") } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.text_txt)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.txtExport.launch("$baseName.txt") } })
                    }
                    if (isSpreadsheet) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.excel_xlsx)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.ooxmlExport.launch("$baseName.xlsx") } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.csv)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.csvExport.launch("$baseName.csv") } })
                        DropdownMenuItem(text = { Text(stringResource(R.string.tsv)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.tsvExport.launch("$baseName.tsv") } })
                    }
                    if (isPresentation) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.powerpoint_pptx)) }, onClick = { s.exportMenu = false; s.exportWarning = { launchers.ooxmlExport.launch("$baseName.pptx") } })
                    }
                }
            }
            // Edit menu removed: Search moved to a top-bar icon; paragraph ops live in the bottom bar's ⋮ menu.
            // Insert
            if (isTextDoc) Box {
                TextButton(onClick = { s.insertMenu = true }) { Text(stringResource(R.string.insert)) }
                DropdownMenu(expanded = s.insertMenu, onDismissRequest = { s.insertMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.image_1)) }, enabled = focusedPara >= 0, onClick = { s.insertMenu = false; launchers.imagePicker.launch("image/*") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.chart)) }, enabled = focusedPara >= 0, onClick = { s.insertMenu = false; s.editingChartBlock = -1; s.showChartEditor = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.special_character_1)) }, enabled = s.activeRunStart >= 0, onClick = { s.insertMenu = false; s.showSpecialChars = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.date_field)) }, enabled = s.activeRunStart >= 0, onClick = { s.insertMenu = false; if (s.activeRunStart >= 0) viewModel.insertFieldInRun(s.activeRunStart, s.activeRunEnd, s.selStart, "date", viewModel.fieldDisplayValue("date")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.time_field)) }, enabled = s.activeRunStart >= 0, onClick = { s.insertMenu = false; if (s.activeRunStart >= 0) viewModel.insertFieldInRun(s.activeRunStart, s.activeRunEnd, s.selStart, "time", viewModel.fieldDisplayValue("time")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.page_number)) }, enabled = s.activeRunStart >= 0, onClick = { s.insertMenu = false; if (s.activeRunStart >= 0) viewModel.insertFieldInRun(s.activeRunStart, s.activeRunEnd, s.selStart, "page-number", viewModel.fieldDisplayValue("page-number")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.page_count)) }, enabled = s.activeRunStart >= 0, onClick = { s.insertMenu = false; if (s.activeRunStart >= 0) viewModel.insertFieldInRun(s.activeRunStart, s.activeRunEnd, s.selStart, "page-count", viewModel.fieldDisplayValue("page-count")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.file_name)) }, enabled = s.activeRunStart >= 0, onClick = { s.insertMenu = false; if (s.activeRunStart >= 0) viewModel.insertFieldInRun(s.activeRunStart, s.activeRunEnd, s.selStart, "file-name", viewModel.fieldDisplayValue("file-name")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.meta_author)) }, enabled = s.activeRunStart >= 0, onClick = { s.insertMenu = false; if (s.activeRunStart >= 0) viewModel.insertFieldInRun(s.activeRunStart, s.activeRunEnd, s.selStart, "author-name", viewModel.fieldDisplayValue("author-name")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.title_field)) }, enabled = s.activeRunStart >= 0, onClick = { s.insertMenu = false; if (s.activeRunStart >= 0) viewModel.insertFieldInRun(s.activeRunStart, s.activeRunEnd, s.selStart, "title", viewModel.fieldDisplayValue("title")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.bookmark)) }, enabled = focusedPara >= 0, onClick = { s.insertMenu = false; s.showAddBookmark = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.footnote)) }, enabled = focusedPara >= 0, onClick = { s.insertMenu = false; s.showFootnote = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.comment_1)) }, enabled = focusedPara >= 0, onClick = { s.insertMenu = false; s.showComment = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.table_of_contents)) }, enabled = focusedPara >= 0, onClick = { s.insertMenu = false; viewModel.insertTableOfContents(focusedPara) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.header_footer)) }, onClick = { s.insertMenu = false; s.showHeaderFooter = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.horizontal_line)) }, enabled = focusedPara >= 0, onClick = { s.insertMenu = false; viewModel.insertHorizontalLine(focusedPara) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.page_break)) }, enabled = focusedPara >= 0, onClick = { s.insertMenu = false; viewModel.insertPageBreak(focusedPara) })
                }
            }
            // Format menu removed: font size, clear formatting, and list level/restart moved to the bottom bar's ⋮ menu.
            // View
            Box {
                TextButton(onClick = { s.viewMenu = true }) { Text(stringResource(R.string.view)) }
                DropdownMenu(expanded = s.viewMenu, onDismissRequest = { s.viewMenu = false }) {
                    if (isTextDoc && (headings.isNotEmpty() || bookmarks.isNotEmpty())) DropdownMenuItem(text = { Text(stringResource(R.string.outline)) }, onClick = { s.viewMenu = false; scope.launch { drawerState.open() } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.zoom_text)) }, onClick = { s.viewMenu = false; s.showFontControl = !s.showFontControl })
                    DropdownMenuItem(text = { Text(if (nightMode) stringResource(R.string.night_reading_mode) else stringResource(R.string.night_reading_mode_1)) }, onClick = { s.viewMenu = false; viewModel.toggleNightMode() })
                    DropdownMenuItem(text = { Text(if (documentDarkMode) stringResource(R.string.document_dark_mode) else stringResource(R.string.document_dark_mode_1)) }, onClick = { s.viewMenu = false; viewModel.toggleDocumentDarkMode() })
                    if (isTextDoc) DropdownMenuItem(text = { Text(if (s.showWordBar) stringResource(R.string.word_count_bar) else stringResource(R.string.word_count_bar_1)) }, onClick = { s.viewMenu = false; s.showWordBar = !s.showWordBar })
                    if (isTextDoc) DropdownMenuItem(text = { Text(stringResource(R.string.comments)) }, onClick = { s.viewMenu = false; s.showComments = true })
                    if (isTextDoc) DropdownMenuItem(text = { Text(stringResource(R.string.track_changes)) }, onClick = { s.viewMenu = false; s.showChanges = true })
                    if (isTextDoc) DropdownMenuItem(text = { Text(stringResource(R.string.page_setup)) }, onClick = { s.viewMenu = false; s.showPageSetup = true })
                    if (isTextDoc && wordCount > 0) DropdownMenuItem(text = { Text(stringResource(R.string.words_chars_min, wordCount, charCount, readingTime)) }, enabled = false, onClick = { })
                    if (isPresentation) DropdownMenuItem(text = { Text(stringResource(R.string.presentation_timer)) }, onClick = { s.viewMenu = false; s.showTimer = !s.showTimer })
                }
            }
        }
    }
}
