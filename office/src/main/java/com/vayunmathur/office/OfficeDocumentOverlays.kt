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

internal const val MARGIN_NARROW_CM = 1.27f
internal const val MARGIN_NARROW_MAX = 1.5f
internal const val MARGIN_NORMAL_CM = 2f
internal const val MARGIN_WIDE_MIN = 2.3f
internal const val MARGIN_WIDE_CM = 2.54f
private val CommentFieldHeight = 120.dp
private val ChartPickerHeight = 160.dp
private val DialogSpacing = 8.dp
private const val DATE_PREFIX_LENGTH = 10

internal const val SHARED_DATE_PREFIX_LENGTH = 10
private const val ROTATE_QUARTER_TURNS = 90f

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
    if (state.showMetadata) MetadataDialog(
        metadata = document.metadata,
        onSave = { m -> viewModel.updateMetadata { m } },
        onDismiss = { state.showMetadata = false })
    LaunchedEffect(Unit) { viewModel.initSync() }
    ShareOverlays(
        state, document, viewModel, isOnline, saveAsName, launchers, onBecameOnline, onBack)
    SettingsOverlay(state, viewModel, context)
    RunStyleOverlays(state, viewModel, activeRunStart, activeRunEnd, selStart, selEnd)
    InsertOverlays(state, viewModel, focusedPara, activeRunStart, activeRunEnd, selStart)
    CommentOverlaysHost(state, document, viewModel, focusedPara, listState, scope)
    CellOverlays(state, viewModel, activeCell, activeSlide, activeSlideEl)
    SlideOverlays(
        state, document, viewModel, isPresentation, activeCell, activeSlide,
        pendingReplace, launchers, focusedPara)
    SlideCropOverlay(state, document, viewModel, pendingReplace, launchers)
    SheetCropOverlay(state, document, viewModel, pendingReplace, launchers)
}

/** Slide floating-image crop dialog. */
@Composable
private fun SlideCropOverlay(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    pendingReplace: MutableState<((String, ByteArray) -> Unit)?>,
    launchers: DocumentLaunchers,
) {
    val (s, e) = state.cropSlideTarget ?: return
    val el = (document as? OdfDocument.Presentation)?.slides?.getOrNull(s)?.elements?.getOrNull(e)
    val img = (el as? OdfSlideElement.Frame)?.frame?.image
    if (img == null) {
        state.cropSlideTarget = null
        return
    }
    ImageCropDialog(
        image = img,
        onApply = { l, t, r, b -> viewModel.setSlideImageCrop(s, e, l, t, r, b) },
        onDismiss = { state.cropSlideTarget = null },
        onRotate = { viewModel.rotateSlideImage(s, e, ROTATE_QUARTER_TURNS) },
        onReplace = {
            pendingReplace.value = { n, b -> viewModel.replaceSlideImage(s, e, n, b) }
            launchers.replaceImage.launch("image/*")
        },
    )
}

/** Sheet floating-image crop dialog. */
@Composable
private fun SheetCropOverlay(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    pendingReplace: MutableState<((String, ByteArray) -> Unit)?>,
    launchers: DocumentLaunchers,
) {
    val (s, e) = state.cropSheetTarget ?: return
    val el = (document as? OdfDocument.Spreadsheet)?.sheets?.getOrNull(s)?.floating?.getOrNull(e)
    val img = (el as? OdfSlideElement.Frame)?.frame?.image
    if (img == null) {
        state.cropSheetTarget = null
        return
    }
    ImageCropDialog(
        image = img,
        onApply = { l, t, r, b -> viewModel.setSheetImageCrop(s, e, l, t, r, b) },
        onDismiss = { state.cropSheetTarget = null },
        onRotate = { viewModel.rotateSheetImage(s, e, ROTATE_QUARTER_TURNS) },
        onReplace = {
            pendingReplace.value = { n, b -> viewModel.replaceSheetImage(s, e, n, b) }
            launchers.replaceImage.launch("image/*")
        },
    )
}

/** Share / unsaved-changes / export-warning overlays. */
@Composable
private fun ShareOverlays(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isOnline: Boolean,
    saveAsName: String,
    launchers: DocumentLaunchers,
    onBecameOnline: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (state.showEnableOnlineDialog) {
        EnableOnlineDialog(
            onEnable =
                { viewModel.enableOnlineSharing(); state.showEnableOnlineDialog = false; state.showShareDialog = true },
            onDismiss = { state.showEnableOnlineDialog = false }
        )
    }
    if (state.showShareDialog) {
        ShareDialogBody(state, document, viewModel, isOnline, onBecameOnline)
    }
    if (state.showUnsavedDialog) AlertDialog(
        onDismissRequest = { state.showUnsavedDialog = false },
        title = { Text(stringResource(R.string.unsaved_changes)) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = { TextButton(onClick = { state.showUnsavedDialog = false; onBack() }) { Text(
            stringResource(R.string.discard),
            color = MaterialTheme.colorScheme.error) } },
        dismissButton = { Row {
            TextButton(onClick = { state.showUnsavedDialog = false }) { Text(stringResource(UiR.string.cancel)) }
            TextButton(onClick = {
                state.showUnsavedDialog = false
                if (viewModel.needsSaveAs()) launchers.saveAs.launch(saveAsName) else viewModel.save()
            }) { Text(stringResource(UiR.string.save), fontWeight = FontWeight.Bold) } } })
    state.exportWarning?.let { action ->
        AlertDialog(
            onDismissRequest = { state.exportWarning = null },
            title = { Text(stringResource(R.string.export_to_non_odf_format)) },
            text = { Text(stringResource(R.string.some_formatting_and_features_may_be_lost)) },
            confirmButton = { TextButton(onClick = { state.exportWarning = null; action() }) { Text(
                stringResource(R.string.export_1),
                fontWeight = FontWeight.Bold) } },
            dismissButton =
                { TextButton(onClick = { state.exportWarning = null }) { Text(stringResource(UiR.string.cancel)) } })
    }
}

/** Share dialog with live roster. */
@Composable
private fun ShareDialogBody(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isOnline: Boolean,
    onBecameOnline: (String) -> Unit,
) {
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

/** Settings dialog overlay. */
@Composable
private fun SettingsOverlay(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    context: android.content.Context,
) {
    if (!state.showSettings) return
    SettingsDialog(
        autoSave = viewModel.getAutoSaveEnabled(context),
        autoSaveInterval = viewModel.getAutoSaveInterval(context),
        defaultFontSize =
            viewModel.getDefaultFontSize(context),
        documentThemeMode = viewModel.getDocumentThemeMode(context),
        onSave = { a, i, f, m -> viewModel.saveSettings(context, a, i, f, m) },
        onDismiss = { state.showSettings = false })
}

/** Run text-color / font-size overlays. */
@Composable
private fun RunStyleOverlays(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    activeRunStart: MutableState<Int>,
    activeRunEnd: MutableState<Int>,
    selStart: MutableState<Int>,
    selEnd: MutableState<Int>,
) {
    if (state.showColorPicker) ColorPickerDialog(
        "Text Color",
        onColorSelected = { c ->
            if (activeRunStart.value >= 0) {
                viewModel.applyRunSpanStyle(
                    activeRunStart.value,
                    activeRunEnd.value,
                    selStart.value,
                    selEnd.value,
                ) { it.copy(color = c) }
            }
        },
        onDismiss = { state.showColorPicker = false },
    )
    if (state.showFontSizePicker) FontSizePickerDialog(
        onSizeSelected = { sz ->
            if (activeRunStart.value >= 0) {
                viewModel.applyRunSpanStyle(
                    activeRunStart.value,
                    activeRunEnd.value,
                    selStart.value,
                    selEnd.value,
                ) { it.copy(fontSize = sz) }
            }
        },
        onDismiss = { state.showFontSizePicker = false },
    )
}

/** Table / link / bookmark / chars / footnote insert overlays. */
@Composable
private fun InsertOverlays(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    focusedPara: Int,
    activeRunStart: MutableState<Int>,
    activeRunEnd: MutableState<Int>,
    selStart: MutableState<Int>,
) {
    if (state.showInsertTable) InsertTableDialog(
        onInsert = { r, c -> viewModel.insertTable(maxOf(0, focusedPara), r, c) },
        onDismiss = { state.showInsertTable = false })
    if (state.showInsertLink) InsertHyperlinkDialog(
        onInsert = { t, u -> viewModel.insertHyperlink(maxOf(0, focusedPara), t, u) },
        onDismiss = { state.showInsertLink = false })
    if (state.showAddBookmark) AddBookmarkDialog(
        onAdd = { viewModel.addBookmark(it, maxOf(0, focusedPara)) },
        onDismiss = { state.showAddBookmark = false })
    CharFootnoteOverlays(state, viewModel, activeRunStart, activeRunEnd, selStart)
}

/** Special-chars + footnote overlays. */
@Composable
private fun CharFootnoteOverlays(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    activeRunStart: MutableState<Int>,
    activeRunEnd: MutableState<Int>,
    selStart: MutableState<Int>,
) {
    if (state.showSpecialChars) SpecialCharsDialog(
        onPick = { ch ->
            if (activeRunStart.value >= 0) {
                viewModel.insertTextInRun(activeRunStart.value, activeRunEnd.value, selStart.value, ch)
            }
        },
        onDismiss = { state.showSpecialChars = false },
    )
    if (state.showFootnote) FootnoteDialog(
        onAdd = { body ->
            if (activeRunStart.value >= 0) {
                viewModel.insertFootnote(activeRunStart.value, activeRunEnd.value, selStart.value, body)
            }
        },
        onDismiss = { state.showFootnote = false },
    )
}
