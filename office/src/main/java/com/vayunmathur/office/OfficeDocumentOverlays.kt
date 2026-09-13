package com.vayunmathur.office

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.office.ui.AddBookmarkDialog
import com.vayunmathur.office.ui.ChartEditorDialog
import com.vayunmathur.office.ui.ColorPickerDialog
import com.vayunmathur.office.ui.CommentDialog
import com.vayunmathur.office.ui.FontSizePickerDialog
import com.vayunmathur.office.ui.FootnoteDialog
import com.vayunmathur.office.ui.HeaderFooterDialog
import com.vayunmathur.office.ui.ImageCropDialog
import com.vayunmathur.office.ui.InsertHyperlinkDialog
import com.vayunmathur.office.ui.InsertTableDialog
import com.vayunmathur.office.ui.SettingsDialog
import com.vayunmathur.office.ui.SpecialCharsDialog
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.save
import com.vayunmathur.office.util.needsSaveAs
import com.vayunmathur.office.util.acceptAllChanges
import com.vayunmathur.office.util.acceptChange
import com.vayunmathur.office.util.addBookmark
import com.vayunmathur.office.util.applyRunSpanStyle
import com.vayunmathur.office.util.cellCommentText
import com.vayunmathur.office.util.currentDocRole
import com.vayunmathur.office.util.currentOnlineDocId
import com.vayunmathur.office.util.documentMembers
import com.vayunmathur.office.util.enableOnlineSharing
import com.vayunmathur.office.util.initSync
import com.vayunmathur.office.util.insertChart
import com.vayunmathur.office.util.insertChartIntoSheet
import com.vayunmathur.office.util.insertChartIntoSlide
import com.vayunmathur.office.util.insertComment
import com.vayunmathur.office.util.insertFootnote
import com.vayunmathur.office.util.insertHyperlink
import com.vayunmathur.office.util.insertTable
import com.vayunmathur.office.util.insertTextInRun
import com.vayunmathur.office.util.rejectAllChanges
import com.vayunmathur.office.util.rejectChange
import com.vayunmathur.office.util.renameDocument
import com.vayunmathur.office.util.replaceSheetImage
import com.vayunmathur.office.util.replaceSlideImage
import com.vayunmathur.office.util.replaceTextImage
import com.vayunmathur.office.util.resolveComment
import com.vayunmathur.office.util.rotateSheetImage
import com.vayunmathur.office.util.rotateSlideImage
import com.vayunmathur.office.util.rotateTextImage
import com.vayunmathur.office.util.securityCodeWith
import com.vayunmathur.office.util.setCellBgColor
import com.vayunmathur.office.util.setCellBorder
import com.vayunmathur.office.util.setCellColor
import com.vayunmathur.office.util.setCellComment
import com.vayunmathur.office.util.setColumnWidth
import com.vayunmathur.office.util.setFooterText
import com.vayunmathur.office.util.setHeaderText
import com.vayunmathur.office.util.setImageCrop
import com.vayunmathur.office.util.setMemberRole
import com.vayunmathur.office.util.setPageSetup
import com.vayunmathur.office.util.setRowHeight
import com.vayunmathur.office.util.setSheetImageCrop
import com.vayunmathur.office.util.setSlideBackgroundColor
import com.vayunmathur.office.util.setSlideElementColor
import com.vayunmathur.office.util.setSlideElementFill
import com.vayunmathur.office.util.setSlideElementStroke
import com.vayunmathur.office.util.setSlideImageCrop
import com.vayunmathur.office.util.setSlideNotes
import com.vayunmathur.office.util.setSlideTransition
import com.vayunmathur.office.util.shareCurrentDocument
import com.vayunmathur.office.util.shareLinkFor
import com.vayunmathur.office.util.slideNotesText
import com.vayunmathur.office.util.transferOwnership
import com.vayunmathur.office.util.updateChart
import com.vayunmathur.office.util.updateMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Dialog/show flags owned by [DocumentScreen].
 *
 * Plain holder with snapshot-state delegates, kept across recompositions via
 * [rememberDocumentOverlayState]; identical semantics to the `var x by remember {}`
 * declarations it replaces (split for file length, behavior identical).
 *
 * Subclassed by [DocumentScreenState], which adds the selection and chrome state.
 */
open class DocumentOverlayState {
    var showMetadata by mutableStateOf(false)
    var showShareDialog by mutableStateOf(false)
    var showEnableOnlineDialog by mutableStateOf(false)
    val showUnsavedDialogState = mutableStateOf(false)
    var showUnsavedDialog by showUnsavedDialogState
    var showSettings by mutableStateOf(false)
    val showColorPickerState = mutableStateOf(false)
    var showColorPicker by showColorPickerState
    val showFontSizePickerState = mutableStateOf(false)
    var showFontSizePicker by showFontSizePickerState
    val showInsertTableState = mutableStateOf(false)
    var showInsertTable by showInsertTableState
    var showInsertLink by mutableStateOf(false)
    var showAddBookmark by mutableStateOf(false)
    var showSpecialChars by mutableStateOf(false)
    var showFootnote by mutableStateOf(false)
    var showComment by mutableStateOf(false)
    var showComments by mutableStateOf(false)
    var showChanges by mutableStateOf(false)
    var showPageSetup by mutableStateOf(false)
    var showHeaderFooter by mutableStateOf(false)
    val showCellTextColorState = mutableStateOf(false)
    var showCellTextColor by showCellTextColorState
    val showCellBgColorState = mutableStateOf(false)
    var showCellBgColor by showCellBgColorState
    val showCellBorderColorState = mutableStateOf(false)
    var showCellBorderColor by showCellBorderColorState
    val showSlideTextColorState = mutableStateOf(false)
    var showSlideTextColor by showSlideTextColorState
    val showSlideFillColorState = mutableStateOf(false)
    var showSlideFillColor by showSlideFillColorState
    val showSlideStrokeColorState = mutableStateOf(false)
    var showSlideStrokeColor by showSlideStrokeColorState
    val showCellCommentState = mutableStateOf(false)
    var showCellComment by showCellCommentState
    val showCellResizeState = mutableStateOf(false)
    var showCellResize by showCellResizeState
    val showSlideNotesState = mutableStateOf(false)
    var showSlideNotes by showSlideNotesState
    val showSlideBackgroundState = mutableStateOf(false)
    var showSlideBackground by showSlideBackgroundState
    val showSlideTransitionState = mutableStateOf(false)
    var showSlideTransition by showSlideTransitionState
    val showChartEditorState = mutableStateOf(false)
    var showChartEditor by showChartEditorState
    val editingChartBlockState = mutableIntStateOf(-1)
    var editingChartBlock by editingChartBlockState
    val chartForSlideState = mutableStateOf(false)
    var chartForSlide by chartForSlideState
    val chartForSheetState = mutableStateOf(false)
    var chartForSheet by chartForSheetState
    val cropImageBlockState = mutableIntStateOf(-1)
    var cropImageBlock by cropImageBlockState
    val cropSlideTargetState: MutableState<Pair<Int, Int>?> = mutableStateOf(null)
    var cropSlideTarget by cropSlideTargetState
    val cropSheetTargetState: MutableState<Pair<Int, Int>?> = mutableStateOf(null)
    var cropSheetTarget by cropSheetTargetState
    var exportWarning by mutableStateOf<(() -> Unit)?>(null)
}

@Composable
internal fun rememberDocumentOverlayState(): DocumentOverlayState = remember { DocumentOverlayState() }

/**
 * All trailing `if (showX)` dialogs of [DocumentScreen], hoisted verbatim.
 *
 * Dialog flags live in [DocumentOverlayState] (via [DocumentScreenState]); selection state
 * shared with the scaffold body arrives as [MutableState] params. Behavior identical.
 */
@Composable
fun DocumentOverlays(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isTextDoc: Boolean,
    isPresentation: Boolean,
    isOnline: Boolean,
    focusedPara: Int,
    saveAsName: String,
    activeCell: MutableState<Triple<Int, Int, Int>?>,
    activeSlide: MutableState<Int>,
    activeSlideEl: MutableState<Int>,
    activeRunStart: MutableState<Int>,
    activeRunEnd: MutableState<Int>,
    selStart: MutableState<Int>,
    selEnd: MutableState<Int>,
    pendingReplace: MutableState<((String, ByteArray) -> Unit)?>,
    listState: LazyListState,
    scope: CoroutineScope,
    launchers: DocumentLaunchers,
    onBecameOnline: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    if (state.showMetadata) MetadataDialog(metadata = document.metadata, onSave = { m -> viewModel.updateMetadata { m } }, onDismiss = { state.showMetadata = false })
    LaunchedEffect(Unit) { viewModel.initSync() }
    if (state.showEnableOnlineDialog) {
        EnableOnlineDialog(
            onEnable = { viewModel.enableOnlineSharing(); state.showEnableOnlineDialog = false; state.showShareDialog = true },
            onDismiss = { state.showEnableOnlineDialog = false }
        )
    }
    if (state.showShareDialog) {
        var members by remember { mutableStateOf<List<com.vayunmathur.office.util.OfficeMember>>(emptyList()) }
        LaunchedEffect(state.showShareDialog) { viewModel.documentMembers { members = it } }
        ShareOnlineDialog(
            deviceId = viewModel.syncDeviceId,
            isOwner = viewModel.currentDocRole() == com.vayunmathur.office.util.OfficeRoles.OWNER,
            isOnline = isOnline,
            initialName = document.title,
            myRole = viewModel.currentDocRole(),
            members = members,
            shareLink = if (isOnline) viewModel.currentOnlineDocId()?.let { viewModel.shareLinkFor(it) } else null,
            onShare = { recipientId, role, name, cb ->
                viewModel.shareCurrentDocument(recipientId, role, name) { err ->
                    if (err == null) {
                        viewModel.documentMembers { members = it } // refresh roster on success
                        viewModel.currentOnlineDocId()?.let { onBecameOnline(it) } // now a cloud doc
                    }
                    cb(err)
                }
            },
            onSetRole = { memberId, role ->
                viewModel.setMemberRole(memberId, role) {
                    viewModel.documentMembers { members = it }
                }
            },
            onRename = { name -> viewModel.renameDocument(name) },
            onTransferOwner = { memberId ->
                viewModel.transferOwnership(memberId) { viewModel.documentMembers { members = it } }
            },
            onComputeCode = { id, cb -> viewModel.securityCodeWith(id, cb) },
            onDismiss = { state.showShareDialog = false }
        )
    }
    if (state.showUnsavedDialog) AlertDialog(onDismissRequest = { state.showUnsavedDialog = false }, title = { Text(stringResource(R.string.unsaved_changes)) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = { TextButton(onClick = { state.showUnsavedDialog = false; onBack() }) { Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { Row { TextButton(onClick = { state.showUnsavedDialog = false }) { Text(stringResource(UiR.string.cancel)) }
            TextButton(onClick = { state.showUnsavedDialog = false; if (viewModel.needsSaveAs()) launchers.saveAs.launch(saveAsName) else viewModel.save() }) { Text(stringResource(UiR.string.save), fontWeight = FontWeight.Bold) } } })
    state.exportWarning?.let { action ->
        AlertDialog(onDismissRequest = { state.exportWarning = null }, title = { Text(stringResource(R.string.export_to_non_odf_format)) },
            text = { Text(stringResource(R.string.some_formatting_and_features_may_be_lost)) },
            confirmButton = { TextButton(onClick = { state.exportWarning = null; action() }) { Text(stringResource(R.string.export_1), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { state.exportWarning = null }) { Text(stringResource(UiR.string.cancel)) } })
    }
    if (state.showSettings) SettingsDialog(autoSave = viewModel.getAutoSaveEnabled(context), autoSaveInterval = viewModel.getAutoSaveInterval(context),
        defaultFontSize = viewModel.getDefaultFontSize(context), documentThemeMode = viewModel.getDocumentThemeMode(context),
        onSave = { a, i, f, m -> viewModel.saveSettings(context, a, i, f, m) }, onDismiss = { state.showSettings = false })
    if (state.showColorPicker) ColorPickerDialog("Text Color", onColorSelected = { c -> if (activeRunStart.value >= 0) viewModel.applyRunSpanStyle(activeRunStart.value, activeRunEnd.value, selStart.value, selEnd.value) { it.copy(color = c) } }, onDismiss = { state.showColorPicker = false })
    if (state.showFontSizePicker) FontSizePickerDialog(onSizeSelected = { sz -> if (activeRunStart.value >= 0) viewModel.applyRunSpanStyle(activeRunStart.value, activeRunEnd.value, selStart.value, selEnd.value) { it.copy(fontSize = sz) } }, onDismiss = { state.showFontSizePicker = false })
    if (state.showInsertTable) InsertTableDialog(onInsert = { r, c -> viewModel.insertTable(maxOf(0, focusedPara), r, c) }, onDismiss = { state.showInsertTable = false })
    if (state.showInsertLink) InsertHyperlinkDialog(onInsert = { t, u -> viewModel.insertHyperlink(maxOf(0, focusedPara), t, u) }, onDismiss = { state.showInsertLink = false })
    if (state.showAddBookmark) AddBookmarkDialog(onAdd = { viewModel.addBookmark(it, maxOf(0, focusedPara)) }, onDismiss = { state.showAddBookmark = false })
    if (state.showSpecialChars) SpecialCharsDialog(onPick = { ch -> if (activeRunStart.value >= 0) viewModel.insertTextInRun(activeRunStart.value, activeRunEnd.value, selStart.value, ch) }, onDismiss = { state.showSpecialChars = false })
    if (state.showFootnote) FootnoteDialog(onAdd = { body -> if (activeRunStart.value >= 0) viewModel.insertFootnote(activeRunStart.value, activeRunEnd.value, selStart.value, body) }, onDismiss = { state.showFootnote = false })
    if (state.showComment) CommentDialog(onAdd = { author, text -> if (focusedPara >= 0) viewModel.insertComment(focusedPara, author, text) }, onDismiss = { state.showComment = false })
    if (state.showComments) {
        val td = document as? OdfDocument.TextDocument
        val comments = remember(document) {
            buildList {
                td?.content?.forEachIndexed { bi, block ->
                    if (block is OdfContentBlock.Paragraph) block.paragraph.spans.forEachIndexed { si, span ->
                        span.annotation?.let { add(Triple(bi, si, it)) }
                    }
                }
            }
        }
        AlertDialog(onDismissRequest = { state.showComments = false }, title = { Text(stringResource(R.string.comments_1, comments.size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (comments.isEmpty()) Text(stringResource(R.string.no_comments_yet_use_insert_comment_to_ad))
                    comments.forEach { (bi, si, ann) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(stringResource(R.string.annotation_author_date, ann.author ?: stringResource(R.string.anonymous), ann.date?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(ann.paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { scope.launch { listState.animateScrollToItem(bi.coerceIn(0, (td?.content?.size ?: 1) - 1)) } }) { Text(stringResource(R.string.go_to)) }
                                TextButton(onClick = { viewModel.resolveComment(bi, si) }) { Text(stringResource(R.string.resolve)) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { state.showComments = false }) { Text(stringResource(R.string.close_search)) } })
    }
    if (state.showChanges) {
        val td = document as? OdfDocument.TextDocument
        val changes = td?.changes ?: emptyList()
        AlertDialog(onDismissRequest = { state.showChanges = false }, title = { Text(stringResource(R.string.tracked_changes, changes.size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (changes.isEmpty()) Text(stringResource(R.string.no_tracked_changes_in_this_document))
                    changes.forEach { ch ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(stringResource(R.string.tracked_change_author_date, ch.type.replaceFirstChar { it.uppercase() }, ch.author ?: stringResource(R.string.unknown), ch.date?.let { " · ${it.take(10)}" } ?: ""),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Row {
                                TextButton(onClick = { viewModel.acceptChange(ch.id) }) { Text(stringResource(R.string.accept)) }
                                TextButton(onClick = { viewModel.rejectChange(ch.id) }) { Text(stringResource(R.string.reject)) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    if (changes.isNotEmpty()) {
                        TextButton(onClick = { viewModel.acceptAllChanges() }) { Text(stringResource(R.string.accept_all)) }
                        TextButton(onClick = { viewModel.rejectAllChanges() }) { Text(stringResource(R.string.reject_all)) }
                    }
                    TextButton(onClick = { state.showChanges = false }) { Text(stringResource(R.string.close_search)) }
                }
            })
    }
    if (state.showPageSetup) {
        val cur = (document as? OdfDocument.TextDocument)?.pageSetup ?: OdfPageSetup()
        data class Paper(val name: String, val wCm: Float, val hCm: Float)
        val papers = listOf(Paper("A4", 21f, 29.7f), Paper("Letter", 21.59f, 27.94f), Paper("Legal", 21.59f, 35.56f))
        var landscape by remember(state.showPageSetup) { mutableStateOf(cur.isLandscape) }
        var paper by remember(state.showPageSetup) {
            val wcm = minOf(cur.widthPx, cur.heightPx) / 37.795f
            mutableStateOf(papers.minByOrNull { kotlin.math.abs(it.wCm - wcm) }?.name ?: "A4")
        }
        var marginCm by remember(state.showPageSetup) { mutableStateOf(cur.marginLeftPx / 37.795f) }
        AlertDialog(onDismissRequest = { state.showPageSetup = false }, title = { Text(stringResource(R.string.page_setup_1)) },
            text = {
                Column {
                    Text(stringResource(R.string.paper_size), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row { papers.forEach { p -> TextButton(onClick = { paper = p.name }) { Text(if (paper == p.name) "● ${p.name}" else p.name) } } }
                    Text(stringResource(R.string.orientation), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row {
                        TextButton(onClick = { landscape = false }) { Text(if (!landscape) stringResource(R.string.portrait) else stringResource(R.string.portrait_1)) }
                        TextButton(onClick = { landscape = true }) { Text(if (landscape) stringResource(R.string.landscape) else stringResource(R.string.landscape_1)) }
                    }
                    Text(stringResource(R.string.margins), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row {
                        TextButton(onClick = { marginCm = 1.27f }) { Text(if (marginCm < 1.5f) stringResource(R.string.narrow) else stringResource(R.string.narrow_1)) }
                        TextButton(onClick = { marginCm = 2f }) { Text(if (marginCm in 1.5f..2.3f) stringResource(R.string.normal_1) else stringResource(R.string.normal)) }
                        TextButton(onClick = { marginCm = 2.54f }) { Text(if (marginCm > 2.3f) stringResource(R.string.wide) else stringResource(R.string.wide_1)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = papers.first { it.name == paper }
                    val wPx = (if (landscape) p.hCm else p.wCm) * 37.795f
                    val hPx = (if (landscape) p.wCm else p.hCm) * 37.795f
                    val m = marginCm * 37.795f
                    viewModel.setPageSetup(OdfPageSetup(wPx, hPx, m, m, m, m))
                    state.showPageSetup = false
                }) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = { TextButton(onClick = { state.showPageSetup = false }) { Text(stringResource(UiR.string.cancel)) } })
    }
    if (state.showHeaderFooter) {
        val td = document as? OdfDocument.TextDocument
        HeaderFooterDialog(
            initialHeader = td?.headerParagraphs?.joinToString("\n") { p -> p.spans.joinToString("") { it.text } } ?: "",
            initialFooter = td?.footerParagraphs?.joinToString("\n") { p -> p.spans.joinToString("") { it.text } } ?: "",
            onSave = { h, f -> viewModel.setHeaderText(h); viewModel.setFooterText(f) },
            onDismiss = { state.showHeaderFooter = false }
        )
    }
    if (state.showCellTextColor && (activeCell.value?.second ?: -1) >= 0) {
        val (s, r, c) = activeCell.value!!
        ColorPickerDialog("Text Color", onColorSelected = { viewModel.setCellColor(s, r, c, it) }, onDismiss = { state.showCellTextColor = false })
    }
    if (state.showCellBgColor && (activeCell.value?.second ?: -1) >= 0) {
        val (s, r, c) = activeCell.value!!
        ColorPickerDialog("Background Color", onColorSelected = { viewModel.setCellBgColor(s, r, c, it) }, onDismiss = { state.showCellBgColor = false })
    }
    if (state.showCellBorderColor && (activeCell.value?.second ?: -1) >= 0) {
        val (s, r, c) = activeCell.value!!
        ColorPickerDialog("Border Color", onColorSelected = { viewModel.setCellBorder(s, r, c, it) }, onDismiss = { state.showCellBorderColor = false })
    }
    if (state.showSlideTextColor && activeSlideEl.value >= 0) {
        ColorPickerDialog("Text Color", onColorSelected = { viewModel.setSlideElementColor(activeSlide.value, activeSlideEl.value, it) }, onDismiss = { state.showSlideTextColor = false })
    }
    if (state.showSlideFillColor && activeSlideEl.value >= 0) {
        ColorPickerDialog("Fill Color", onColorSelected = { viewModel.setSlideElementFill(activeSlide.value, activeSlideEl.value, it) }, onDismiss = { state.showSlideFillColor = false })
    }
    if (state.showSlideStrokeColor && activeSlideEl.value >= 0) {
        ColorPickerDialog("Border Color", onColorSelected = { viewModel.setSlideElementStroke(activeSlide.value, activeSlideEl.value, it) }, onDismiss = { state.showSlideStrokeColor = false })
    }
    if (state.showCellComment && (activeCell.value?.second ?: -1) >= 0) {
        val (s, r, c) = activeCell.value!!
        var text by remember(state.showCellComment) { mutableStateOf(viewModel.cellCommentText(s, r, c)) }
        AlertDialog(
            onDismissRequest = { state.showCellComment = false },
            title = { Text(stringResource(R.string.cell_comment)) },
            text = { TextField(value = text, onValueChange = { text = it }, placeholder = { Text(stringResource(R.string.comment)) }, modifier = Modifier.height(120.dp)) },
            confirmButton = { TextButton(onClick = { viewModel.setCellComment(s, r, c, "", text); state.showCellComment = false }) { Text(stringResource(UiR.string.save)) } },
            dismissButton = { TextButton(onClick = { state.showCellComment = false }) { Text(stringResource(UiR.string.cancel)) } }
        )
    }
    if (state.showCellResize && (activeCell.value?.second ?: -1) >= 0) {
        val (s, r, c) = activeCell.value!!
        var w by remember(state.showCellResize) { mutableStateOf("") }
        var h by remember(state.showCellResize) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { state.showCellResize = false },
            title = { Text(stringResource(R.string.row_column_size_1)) },
            text = {
                Column {
                    TextField(value = w, onValueChange = { w = it }, label = { Text(stringResource(R.string.column_width_px)) })
                    Spacer(Modifier.height(8.dp))
                    TextField(value = h, onValueChange = { h = it }, label = { Text(stringResource(R.string.row_height_px)) })
                }
            },
            confirmButton = { TextButton(onClick = {
                w.toFloatOrNull()?.let { viewModel.setColumnWidth(s, c, it) }
                h.toFloatOrNull()?.let { viewModel.setRowHeight(s, r, it) }
                state.showCellResize = false
            }) { Text(stringResource(R.string.apply)) } },
            dismissButton = { TextButton(onClick = { state.showCellResize = false }) { Text(stringResource(UiR.string.cancel)) } }
        )
    }
    if (state.showSlideNotes && isPresentation) {
        var text by remember(state.showSlideNotes) { mutableStateOf(viewModel.slideNotesText(activeSlide.value)) }
        AlertDialog(
            onDismissRequest = { state.showSlideNotes = false },
            title = { Text(stringResource(R.string.speaker_notes)) },
            text = { TextField(value = text, onValueChange = { text = it }, placeholder = { Text(stringResource(R.string.notes_for_this_slide)) }, modifier = Modifier.height(160.dp)) },
            confirmButton = { TextButton(onClick = { viewModel.setSlideNotes(activeSlide.value, text); state.showSlideNotes = false }) { Text(stringResource(UiR.string.save)) } },
            dismissButton = { TextButton(onClick = { state.showSlideNotes = false }) { Text(stringResource(UiR.string.cancel)) } }
        )
    }
    if (state.showSlideBackground && isPresentation) {
        ColorPickerDialog("Slide background", onColorSelected = { viewModel.setSlideBackgroundColor(activeSlide.value, it) }, onDismiss = { state.showSlideBackground = false })
    }
    if (state.showSlideTransition && isPresentation) {
        val types = listOf("none", "fade", "wipe", "dissolve", "push", "cover", "split", "blinds", "checkerboard", "circle", "wheel")
        var type by remember(state.showSlideTransition) { mutableStateOf((document as? OdfDocument.Presentation)?.slides?.getOrNull(activeSlide.value)?.transitionType ?: "none") }
        var expanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { state.showSlideTransition = false },
            title = { Text(stringResource(R.string.slide_transition_1)) },
            text = {
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { expanded = true }) { Text(type.replaceFirstChar { it.uppercase() }) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        types.forEach { t -> DropdownMenuItem(text = { Text(t.replaceFirstChar { ch -> ch.uppercase() }) }, onClick = { type = t; expanded = false }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.setSlideTransition(activeSlide.value, type.takeIf { it != "none" }, "medium"); state.showSlideTransition = false }) { Text(stringResource(R.string.apply)) } },
            dismissButton = { TextButton(onClick = { state.showSlideTransition = false }) { Text(stringResource(UiR.string.cancel)) } }
        )
    }
    if (state.cropImageBlock >= 0) {
        val img = ((document as? OdfDocument.TextDocument)?.content?.getOrNull(state.cropImageBlock) as? OdfContentBlock.Image)?.image
        if (img != null) ImageCropDialog(image = img, onApply = { l, t, r, b -> viewModel.setImageCrop(state.cropImageBlock, l, t, r, b) }, onDismiss = { state.cropImageBlock = -1 },
            onRotate = { viewModel.rotateTextImage(state.cropImageBlock, 90f) },
            onReplace = { val bi = state.cropImageBlock; pendingReplace.value = { n, b -> viewModel.replaceTextImage(bi, n, b) }; launchers.replaceImage.launch("image/*") })
        else state.cropImageBlock = -1
    }
    state.cropSlideTarget?.let { (s, e) ->
        val img = ((document as? OdfDocument.Presentation)?.slides?.getOrNull(s)?.elements?.getOrNull(e) as? OdfSlideElement.Frame)?.frame?.image
        if (img != null) ImageCropDialog(image = img, onApply = { l, t, r, b -> viewModel.setSlideImageCrop(s, e, l, t, r, b) }, onDismiss = { state.cropSlideTarget = null },
            onRotate = { viewModel.rotateSlideImage(s, e, 90f) },
            onReplace = { pendingReplace.value = { n, b -> viewModel.replaceSlideImage(s, e, n, b) }; launchers.replaceImage.launch("image/*") })
        else state.cropSlideTarget = null
    }
    state.cropSheetTarget?.let { (s, e) ->
        val img = ((document as? OdfDocument.Spreadsheet)?.sheets?.getOrNull(s)?.floating?.getOrNull(e) as? OdfSlideElement.Frame)?.frame?.image
        if (img != null) ImageCropDialog(image = img, onApply = { l, t, r, b -> viewModel.setSheetImageCrop(s, e, l, t, r, b) }, onDismiss = { state.cropSheetTarget = null },
            onRotate = { viewModel.rotateSheetImage(s, e, 90f) },
            onReplace = { pendingReplace.value = { n, b -> viewModel.replaceSheetImage(s, e, n, b) }; launchers.replaceImage.launch("image/*") })
        else state.cropSheetTarget = null
    }
    if (state.showChartEditor) {
        val existing = if (!state.chartForSlide && state.editingChartBlock >= 0) ((document as? OdfDocument.TextDocument)?.content?.getOrNull(state.editingChartBlock) as? OdfContentBlock.Chart)?.chart else null
        ChartEditorDialog(
            initial = existing,
            onConfirm = { ch ->
                when {
                    state.chartForSlide -> viewModel.insertChartIntoSlide(activeSlide.value, ch)
                    state.chartForSheet -> viewModel.insertChartIntoSheet(activeCell.value?.first ?: 0, ch)
                    state.editingChartBlock >= 0 -> viewModel.updateChart(state.editingChartBlock, ch)
                    else -> if (focusedPara >= 0) viewModel.insertChart(focusedPara, ch)
                }
            },
            onDismiss = { state.showChartEditor = false; state.editingChartBlock = -1; state.chartForSlide = false; state.chartForSheet = false }
        )
    }
}
