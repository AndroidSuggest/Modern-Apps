package com.vayunmathur.office

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.ui.DrawingView
import com.vayunmathur.office.ui.PresentationView
import com.vayunmathur.office.ui.SpreadsheetView
import com.vayunmathur.office.ui.TextDocumentView
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.addColumn
import com.vayunmathur.office.util.addRow
import com.vayunmathur.office.util.addSheet
import com.vayunmathur.office.util.addSlide
import com.vayunmathur.office.util.addTextBoxToSlide
import com.vayunmathur.office.util.deleteBlockBefore
import com.vayunmathur.office.util.deleteColumn
import com.vayunmathur.office.util.deleteRow
import com.vayunmathur.office.util.deleteSheet
import com.vayunmathur.office.util.deleteSheetElement
import com.vayunmathur.office.util.deleteSlide
import com.vayunmathur.office.util.deleteSlideElement
import com.vayunmathur.office.util.duplicateSlide
import com.vayunmathur.office.util.handleListBackspace
import com.vayunmathur.office.util.handleListEnter
import com.vayunmathur.office.util.mergeCells
import com.vayunmathur.office.util.moveSlideDown
import com.vayunmathur.office.util.moveSlideUp
import com.vayunmathur.office.util.renameSheet
import com.vayunmathur.office.util.setCellAlignment
import com.vayunmathur.office.util.setCellBgColor
import com.vayunmathur.office.util.setCellBold
import com.vayunmathur.office.util.setCellColor
import com.vayunmathur.office.util.setCellItalic
import com.vayunmathur.office.util.setCheckboxChecked
import com.vayunmathur.office.util.setLocalLocation
import com.vayunmathur.office.util.setSheetElementBounds
import com.vayunmathur.office.util.setSheetFreeze
import com.vayunmathur.office.util.setSlideElementBounds
import com.vayunmathur.office.util.sortRows
import com.vayunmathur.office.util.unmergeCells
import com.vayunmathur.office.util.updateCellText
import com.vayunmathur.office.util.updateParagraphRun
import com.vayunmathur.office.util.updateSheetElementText
import com.vayunmathur.office.util.updateSlideElementText
import com.vayunmathur.office.util.updateTextTableCell

/**
 * Document pane shared by the single-pane body (compact) and the wide layout (Expanded).
 *
 * Hoisted verbatim from `DocumentScreen`'s local `DocumentPane` (split for file length);
 * captures became explicit parameters, behavior identical.
 */
@Composable
fun OfficeDocumentPane(
    document: OdfDocument,
    viewModel: OfficeViewModel,
    documentDarkMode: Boolean,
    nightMode: Boolean,
    searchQuery: String,
    fontSizeMultiplier: Float,
    listState: LazyListState,
    isEditMode: Boolean,
    activeSlide: Int,
    activeSlideEl: Int,
    onBack: () -> Unit,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    onChartClick: (Int) -> Unit,
    onCropImage: (Int) -> Unit,
    onCropSlide: (Int, Int) -> Unit,
    onCropSheet: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        val presence by viewModel.remotePresence.collectAsState()
        val remoteCarets = presence.mapNotNull { p ->
            p.caret?.let { com.vayunmathur.library.ui.odf.RemoteCaret(it, 0xFF000000L or (p.id.hashCode().toLong() and 0xFFFFFFL), p.name) }
        }
        if (presence.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.align(Alignment.TopCenter).zIndex(1f).fillMaxWidth()
            ) {
                val typingLabel = stringResource(R.string.typing)
                Text(
                    presence.joinToString(", ") { it.name + (it.loc?.let { l -> " · $l" } ?: "") + if (it.typing) typingLabel else "" }
                        .let { stringResource(R.string.online_1, it) },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
        OfficeLightTheme {
          Surface(
            Modifier
                .fillMaxSize()
                .then(if (documentDarkMode) Modifier.documentDarkModeInvert() else Modifier),
            color = MaterialTheme.colorScheme.background,
          ) {
        when (document) {
            is OdfDocument.TextDocument -> TextDocumentView(doc = document, searchQuery = searchQuery, fontSizeMultiplier = fontSizeMultiplier, listState = listState,
                remoteCarets = remoteCarets,
                onRunSelectionChange = { rs, re, gs, ge -> onRunSelectionChange(rs, re, gs, ge) },
                onRunTextChange = { rs, re, text -> viewModel.updateParagraphRun(rs, re, text) },
                onRunEnter = { rs, re, gPos -> viewModel.handleListEnter(rs, re, gPos) },
                onRunBackspace = { rs, re, gPos -> viewModel.handleListBackspace(rs, re, gPos) },
                onToggleCheckbox = { idx ->
                    val p = (document.content.getOrNull(idx) as? OdfContentBlock.Paragraph)?.paragraph
                    if (p != null) viewModel.setCheckboxChecked(idx, !p.listChecked)
                },
                onDeletePrevBlock = { runStart -> viewModel.deleteBlockBefore(runStart) },
                onCellTextChange = { bi, r, c, text -> viewModel.updateTextTableCell(bi, r, c, text) },
                onCellFocus = { bi, r, c -> onCellFocus(bi, r, c) },
                onChartClick = { bi -> onChartClick(bi) },
                onCropImage = { bi -> onCropImage(bi) })
            is OdfDocument.Spreadsheet -> SpreadsheetView(doc = document, searchQuery = searchQuery, fontSizeMultiplier = fontSizeMultiplier, isEditMode = isEditMode,
                onCellTextChange = { s, r, c, t -> viewModel.updateCellText(s, r, c, t) }, onAddRow = { s, r -> viewModel.addRow(s, r) }, onAddColumn = { s -> viewModel.addColumn(s) },
                onDeleteRow = { s, r -> viewModel.deleteRow(s, r) }, onDeleteColumn = { s, c -> viewModel.deleteColumn(s, c) },
                onRenameSheet = { s, n -> viewModel.renameSheet(s, n) }, onAddSheet = { viewModel.addSheet() }, onDeleteSheet = { s -> viewModel.deleteSheet(s) },
                onCellBold = { s, r, c -> viewModel.setCellBold(s, r, c) }, onCellItalic = { s, r, c -> viewModel.setCellItalic(s, r, c) },
                onCellColor = { s, r, c, clr -> viewModel.setCellColor(s, r, c, clr) }, onCellBgColor = { s, r, c, clr -> viewModel.setCellBgColor(s, r, c, clr) },
                onCellAlignment = { s, r, c, a -> viewModel.setCellAlignment(s, r, c, a) },
                onMergeCells = { s, sr, sc, er, ec -> viewModel.mergeCells(s, sr, sc, er, ec) }, onUnmergeCells = { s, r, c -> viewModel.unmergeCells(s, r, c) },
                onSort = { s, col, asc -> viewModel.sortRows(s, col, asc) },
                onCellSelected = { s, r, c -> onCellSelected(s, r, c); viewModel.setLocalLocation("Sheet ${s + 1} · ${('A' + c)}${r + 1}") },
                onFloatingBoundsChange = { s, e, x, y, w, h -> viewModel.setSheetElementBounds(s, e, x, y, w, h) },
                onFloatingTextChange = { s, e, t -> viewModel.updateSheetElementText(s, e, t) },
                onFloatingDelete = { s, e -> viewModel.deleteSheetElement(s, e) },
                onFloatingCrop = { s, e -> onCropSheet(s, e) },
                onSetFreeze = { s, r, c -> viewModel.setSheetFreeze(s, r, c) })
            is OdfDocument.Presentation -> PresentationView(doc = document, isEditMode = isEditMode,
                onAddSlide = { viewModel.addSlide(it) }, onDeleteSlide = { viewModel.deleteSlide(it) },
                onDuplicateSlide = { viewModel.duplicateSlide(it) }, onMoveSlideUp = { viewModel.moveSlideUp(it) }, onMoveSlideDown = { viewModel.moveSlideDown(it) },
                onElementTextChange = { s, e, t -> viewModel.updateSlideElementText(s, e, t) }, onAddTextBox = { viewModel.addTextBoxToSlide(it) },
                onElementBoundsChange = { s, e, x, y, w, h -> viewModel.setSlideElementBounds(s, e, x, y, w, h) },
                onDeleteElement = { s, e -> viewModel.deleteSlideElement(s, e) },
                selectedElement = activeSlideEl,
                onSlideChange = { onSlideChange(it); viewModel.setLocalLocation("Slide ${it + 1}") },
                onElementSelected = { s, e -> onSlideElementSelected(s, e) },
                onCropImage = { s, e -> onCropSlide(s, e) })
            is OdfDocument.Drawing -> DrawingView(document)
        }
          }
        }
        // Night reading mode: a view-only dimming scrim (does not modify or save the document). (C4)
        if (nightMode) Box(Modifier.matchParentSize().background(Color(0x660E1116)))
    }
}
