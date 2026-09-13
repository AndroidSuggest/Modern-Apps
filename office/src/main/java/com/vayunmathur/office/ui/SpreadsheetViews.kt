package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.PrimaryScrollableTabRow
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.vayunmathur.office.odf.*
import com.vayunmathur.office.util.OfficeNative
import com.vayunmathur.library.ui.odf.*
import androidx.compose.ui.res.stringResource
import com.vayunmathur.office.R

/**
 * The evaluated text of each cell, i.e. what a formula resolves to. Behind the real
 * spreadsheet this is the native formula engine; the indirection exists so the view can
 * also be rendered from literal values (a `@Preview`, where the engine cannot be loaded).
 */
interface SpreadsheetValues {
    fun display(sheet: Int, row: Int, col: Int): String
    fun isNumeric(sheet: Int, row: Int, col: Int): Boolean
}

@Composable
fun rememberNativeSpreadsheetValues(doc: OdfDocument.Spreadsheet): SpreadsheetValues {
    val handle = remember(doc) { OfficeNative.createWorkbook(doc.sheets, System.currentTimeMillis()) }
    DisposableEffect(doc) { onDispose { OfficeNative.nativeFree(handle) } }
    return remember(handle) {
        object : SpreadsheetValues {
            override fun display(sheet: Int, row: Int, col: Int): String =
                OfficeNative.nativeDisplayValue(handle, sheet, row, col) ?: ""

            override fun isNumeric(sheet: Int, row: Int, col: Int): Boolean =
                OfficeNative.nativeIsNumeric(handle, sheet, row, col)
        }
    }
}

@Composable
fun SpreadsheetView(
    doc: OdfDocument.Spreadsheet, searchQuery: String = "", fontSizeMultiplier: Float = 1f,
    isEditMode: Boolean = false,
    onCellTextChange: (Int, Int, Int, String) -> Unit = { _, _, _, _ -> },
    onAddRow: (Int, Int) -> Unit = { _, _ -> }, onAddColumn: (Int) -> Unit = {},
    onDeleteRow: (Int, Int) -> Unit = { _, _ -> }, onDeleteColumn: (Int, Int) -> Unit = { _, _ -> },
    onRenameSheet: (Int, String) -> Unit = { _, _ -> }, onAddSheet: () -> Unit = {}, onDeleteSheet: (Int) -> Unit = {},
    onCellBold: (Int, Int, Int) -> Unit = { _, _, _ -> }, onCellItalic: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onCellColor: (Int, Int, Int, Long?) -> Unit = { _, _, _, _ -> }, onCellBgColor: (Int, Int, Int, Long?) -> Unit = { _, _, _, _ -> },
    onCellAlignment: (Int, Int, Int, TextAlign?) -> Unit = { _, _, _, _ -> },
    onMergeCells: (Int, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onUnmergeCells: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onSort: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    onCellSelected: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onFloatingBoundsChange: (Int, Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _, _ -> },
    onFloatingTextChange: (Int, Int, String) -> Unit = { _, _, _ -> },
    onFloatingDelete: (Int, Int) -> Unit = { _, _ -> },
    onFloatingCrop: (Int, Int) -> Unit = { _, _ -> },
    onSetFreeze: (Int, Int, Int) -> Unit = { _, _, _ -> },
    /**
     * Where evaluated cell text comes from. Defaults to the native formula engine, which is
     * what the app wants; a `@Preview` passes a literal source instead, because Layoutlib
     * cannot load `office_engine` and merely touching [OfficeNative] would try to.
     */
    values: SpreadsheetValues = rememberNativeSpreadsheetValues(doc)
) {
    if (doc.sheets.isEmpty()) { Text(stringResource(R.string.empty_spreadsheet), modifier = Modifier.padding(16.dp)); return }

    var selectedSheet by remember { mutableIntStateOf(0) }
    var selectedFloating by remember { mutableIntStateOf(-1) }
    var editingCell by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var dropdownCell by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    val validationsByName = remember(doc) { doc.validations.associateBy { it.name } }
    var editText by remember { mutableStateOf(TextFieldValue("")) }
    var showRenameSheet by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showSortDialog by remember { mutableStateOf(false) }

    // A3: keep the selected sheet/floating indices valid after undo/redo shrinks the lists.
    selectedSheet = selectedSheet.coerceIn(0, doc.sheets.size - 1)
    if (selectedFloating >= doc.sheets[selectedSheet].floating.size) selectedFloating = -1

    Column(modifier = Modifier.fillMaxSize()) {
        if (doc.sheets.size > 1 || isEditMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryScrollableTabRow(selectedTabIndex = selectedSheet, modifier = Modifier.weight(1f)) {
                    doc.sheets.forEachIndexed { index, sheet ->
                        Tab(selected = selectedSheet == index, onClick = { selectedSheet = index; editingCell = null; selectedFloating = -1; onCellSelected(index, -1, -1) },
                            text = { if (isEditMode) Text(sheet.name, Modifier.clickable { renameText = sheet.name; showRenameSheet = true }) else Text(sheet.name) })
                    }
                }
                if (isEditMode) TextButton(onClick = { onAddSheet() }) { Text("+") }
            }
        } else {
            Text(doc.sheets[0].name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp))
        }

        if (isEditMode && editingCell != null) {
            val (_, ri, ci) = editingCell!!
            val focusRequester = remember { FocusRequester() }
            val rowCount = doc.sheets[selectedSheet].rows.size
            LaunchedEffect(editingCell) { try { focusRequester.requestFocus() } catch (_: Exception) {} }
            fun commitAndAdvance() {
                val (si, r, c) = editingCell!!
                onCellTextChange(si, r, c, editText.text)
                if (r + 1 < rowCount) {
                    editingCell = Triple(si, r + 1, c)
                    onCellSelected(si, r + 1, c)
                    val nextText = doc.sheets[si].rows.getOrNull(r + 1)?.cells?.getOrNull(c)?.let { it.formula ?: it.text } ?: ""
                    editText = TextFieldValue(nextText, TextRange(0, nextText.length))
                } else { editingCell = null; onCellSelected(si, -1, -1) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${columnLabel(ci)}${ri + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                TextField(value = editText, onValueChange = { editText = it }, singleLine = true,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { commitAndAdvance() }, onDone = { commitAndAdvance() }),
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
                TextButton(onClick = { val (si, r, c) = editingCell!!; onCellTextChange(si, r, c, editText.text); editingCell = null; onCellSelected(si, -1, -1) }) { Text(stringResource(UiR.string.done)) }
            }
        }

        if (isEditMode) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { val ri = editingCell?.second ?: (doc.sheets[selectedSheet].rows.size - 1); onAddRow(selectedSheet, ri) }) { Text(stringResource(R.string.row_1)) }
                TextButton(onClick = { onAddColumn(selectedSheet) }) { Text(stringResource(R.string.col_1)) }
                if (editingCell != null) {
                    TextButton(onClick = { onDeleteRow(selectedSheet, editingCell!!.second); editingCell = null }) { Text(stringResource(R.string.row)) }
                    TextButton(onClick = { onDeleteColumn(selectedSheet, editingCell!!.third); editingCell = null }) { Text(stringResource(R.string.col)) }
                }
                TextButton(onClick = { showSortDialog = true }) { Text(stringResource(R.string.sort)) }
                run {
                    val sheet0 = doc.sheets[selectedSheet]
                    val frozen = sheet0.freezeRows > 0 || sheet0.freezeCols > 0
                    if (frozen) {
                        TextButton(onClick = { onSetFreeze(selectedSheet, 0, 0) }) { Text(stringResource(R.string.unfreeze)) }
                    } else {
                        TextButton(onClick = {
                            // Freeze rows above and columns left of the active/editing cell (default: header row).
                            val r = editingCell?.second ?: 1
                            val c = editingCell?.third ?: 0
                            onSetFreeze(selectedSheet, r, c)
                        }) { Text(stringResource(R.string.freeze)) }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (doc.sheets.size > 1) TextButton(onClick = { onDeleteSheet(selectedSheet); if (selectedSheet >= doc.sheets.size - 1) selectedSheet = maxOf(0, doc.sheets.size - 2) }) { Text(stringResource(R.string.sheet_1), color = MaterialTheme.colorScheme.error) }
            }
        }

        val sheet = doc.sheets[selectedSheet]
        val maxCols = sheet.rows.maxOfOrNull { it.cells.count { c -> !c.isCovered } } ?: 0
        fun colWidthDp(start: Int, span: Int): androidx.compose.ui.unit.Dp {
            var w = 0f
            for (k in start until start + span) {
                val px = sheet.columnWidths.getOrNull(k)
                w += if (px != null && px > 0f) (px * (160f / 96f)).coerceIn(40f, 400f) else 84f
            }
            return w.dp
        }

        // A1: anchor floating objects inside the scrolled grid content (rides both scroll axes).
        // Map model px@96 -> grid content dp by sizing the overlay to the content and passing the
        // content's dp dimensions as the reference space.
        val hScroll = rememberScrollState()
        var contentWidthDp = 40f
        for (col in 0 until maxCols) contentWidthDp += colWidthDp(col, 1).value
        val contentHeightDp = 26f + sheet.rows.size * 33f

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.horizontalScroll(hScroll).padding(horizontal = 4.dp)) {
                    Box {
                        Column {
                            Row {
                                Box(Modifier.defaultMinSize(minWidth = 40.dp).background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) { Text("") }
                                for (col in 0 until maxCols) Box(Modifier.width(colWidthDp(col, 1)).background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) {
                                    Text(columnLabel(col), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                            for ((rowIdx, row) in sheet.rows.withIndex()) {
                                Row(Modifier.height(IntrinsicSize.Min)) {
                                    Box(Modifier.defaultMinSize(minWidth = 40.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant).border(0.5.dp, MaterialTheme.colorScheme.outline).padding(4.dp), contentAlignment = Alignment.Center) {
                                        Text("${rowIdx + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                    var colspanSkip = 0
                                    for ((cellIdx, cell) in row.cells.withIndex()) {
                                        if (cell.isCovered) {
                                            // Covered by a horizontal merge to its left: consume silently.
                                            if (colspanSkip > 0) { colspanSkip--; continue }
                                            // Covered by a vertical (row) merge from above: render an empty
                                            // aligned placeholder so columns don't shift. (Tier 0 bugfix)
                                            Box(Modifier.width(colWidthDp(cellIdx, 1)).fillMaxHeight()
                                                .border(0.5.dp, MaterialTheme.colorScheme.outline)) {}
                                            continue
                                        }
                                        colspanSkip = if (cell.spannedColumns > 1) cell.spannedColumns - 1 else 0
                                        val isEditing = editingCell?.let { it.first == selectedSheet && it.second == rowIdx && it.third == cellIdx } == true
                                        val isMatch = searchQuery.isNotEmpty() && cell.text.contains(searchQuery, ignoreCase = true)
                                        val displayText = values.display(selectedSheet, rowIdx, cellIdx)
                                        val cf = if (cell.condFormats.isEmpty()) null else evalCondFormat(cell.condFormats, cell.numberValue ?: displayText.toDoubleOrNull(), displayText)
                                        val effBg = cf?.backgroundColor ?: cell.backgroundColor
                                        Box(
                                            Modifier.width(colWidthDp(cellIdx, cell.spannedColumns))
                                                .fillMaxHeight()
                                                .border(if (isEditing) 2.dp else 0.5.dp, if (isEditing) MaterialTheme.colorScheme.primary else (cell.borderColor?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.outline))
                                                .then(if (!isEditing && cell.borders?.isEmpty() == false) Modifier.drawBehind {
                                                    val sw = 1.5.dp.toPx()
                                                    OdfBorders.renderColor(cell.borders!!.top)?.let { drawLine(Color(it.toInt()), Offset(0f, 0f), Offset(size.width, 0f), sw) }
                                                    OdfBorders.renderColor(cell.borders!!.bottom)?.let { drawLine(Color(it.toInt()), Offset(0f, size.height), Offset(size.width, size.height), sw) }
                                                    OdfBorders.renderColor(cell.borders!!.left)?.let { drawLine(Color(it.toInt()), Offset(0f, 0f), Offset(0f, size.height), sw) }
                                                    OdfBorders.renderColor(cell.borders!!.right)?.let { drawLine(Color(it.toInt()), Offset(size.width, 0f), Offset(size.width, size.height), sw) }
                                                } else Modifier)
                                                .then(if (isMatch) Modifier.background(Color(0xFFFFEB3B).copy(alpha = 0.3f)) else effBg?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
                                                .then(if (isEditMode) Modifier.clickable { editingCell = Triple(selectedSheet, rowIdx, cellIdx); selectedFloating = -1; onCellSelected(selectedSheet, rowIdx, cellIdx); val t = cell.formula ?: cell.text; editText = TextFieldValue(t, TextRange(0, t.length)) } else Modifier)
                                                .then(if (cell.annotation != null) Modifier.drawBehind {
                                                    // Red corner triangle marks a cell comment. (Round 3)
                                                    val s = 7.dp.toPx()
                                                    drawPath(androidx.compose.ui.graphics.Path().apply {
                                                        moveTo(size.width - s, 0f); lineTo(size.width, 0f); lineTo(size.width, s); close()
                                                    }, Color(0xFFD32F2F))
                                                } else Modifier)
                                                .padding(8.dp, 4.dp)
                                        ) {
                                            val cellAlign = cell.alignment ?: if (values.isNumeric(selectedSheet, rowIdx, cellIdx)) TextAlign.End else null
                                            Text(displayText,
                                                style = MaterialTheme.typography.bodyMedium.let { if (fontSizeMultiplier != 1f && it.fontSize != TextUnit.Unspecified) it.copy(fontSize = it.fontSize * fontSizeMultiplier) else it },
                                                fontWeight = if (cell.bold) FontWeight.Bold else null,
                                                fontStyle = if (cell.italic) FontStyle.Italic else null,
                                                color = (cf?.textColor ?: cell.textColor)?.let { Color(it.toInt()) } ?: Color.Unspecified,
                                                textAlign = cellAlign, maxLines = if (cell.wrap) Int.MAX_VALUE else 3)
                                            // Data-validation list dropdown (Round 3).
                                            val listVals = cell.validationName?.let { validationsByName[it]?.listValues() }
                                            if (listVals != null && isEditMode) {
                                                val here = Triple(selectedSheet, rowIdx, cellIdx)
                                                Box(Modifier.align(Alignment.CenterEnd)) {
                                                    Text("▾", Modifier.clickable { dropdownCell = here }, fontWeight = FontWeight.Bold)
                                                    DropdownMenu(expanded = dropdownCell == here, onDismissRequest = { if (dropdownCell == here) dropdownCell = null }) {
                                                        for (opt in listVals) DropdownMenuItem(text = { Text(opt) }, onClick = { onCellTextChange(selectedSheet, rowIdx, cellIdx, opt); dropdownCell = null })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (isEditMode || sheet.floating.isNotEmpty()) {
                            FloatingElementLayer(
                                elements = sheet.floating, refW = contentWidthDp, refH = contentHeightDp,
                                modifier = Modifier.matchParentSize(),
                                editMode = isEditMode, selectedIndex = selectedFloating,
                                keyPrefix = "sheet$selectedSheet", interactiveBackground = false,
                                onSelect = { selectedFloating = it },
                                onElementTextChange = { ei, t -> onFloatingTextChange(selectedSheet, ei, t) },
                                onBoundsChange = { ei, x, y, w, h -> onFloatingBoundsChange(selectedSheet, ei, x, y, w, h) },
                                onDelete = { ei -> onFloatingDelete(selectedSheet, ei); selectedFloating = -1 },
                                onCropImage = { ei -> onFloatingCrop(selectedSheet, ei) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRenameSheet) {
        AlertDialog(onDismissRequest = { showRenameSheet = false }, title = { Text(stringResource(R.string.rename_sheet)) },
            text = { TextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRenameSheet(selectedSheet, renameText); showRenameSheet = false }) { Text(stringResource(UiR.string.ok)) } },
            dismissButton = { TextButton(onClick = { showRenameSheet = false }) { Text(stringResource(UiR.string.cancel)) } })
    }
    if (showSortDialog) {
        val maxC = doc.sheets[selectedSheet].rows.maxOfOrNull { it.cells.size } ?: 1
        SortDialog(maxC, onSort = { col, asc -> onSort(selectedSheet, col, asc) }, onDismiss = { showSortDialog = false })
    }
}

private fun columnLabel(index: Int): String {
    val sb = StringBuilder(); var n = index
    do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
    return sb.toString()
}
