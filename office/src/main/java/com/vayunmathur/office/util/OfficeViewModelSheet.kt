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

// --- Spreadsheet editing (split from OfficeViewModel.kt for file length) ---

fun OfficeViewModel.updateCellText(sheetIndex: Int, rowIndex: Int, cellIndex: Int, newText: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val rows = sheet.rows.toMutableList()
    val row = rows.getOrNull(rowIndex) ?: return
    val cells = row.cells.toMutableList()
    if (cellIndex !in cells.indices) return
    cells[cellIndex] = if (newText.startsWith("=")) {
        // Typed formula (H49): store as OpenFormula, drop cached numeric value.
        cells[cellIndex].copy(text = newText, formula = newText, valueType = "float", numberValue = null)
    } else {
        cells[cellIndex].copy(text = newText, formula = null,
            valueType = if (newText.toDoubleOrNull() != null) "float" else "string",
            numberValue = newText.toDoubleOrNull())
    }
    rows[rowIndex] = OdfRow(cells)
    sheets[sheetIndex] = sheet.copy(rows = rows)
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.addRow(sheetIndex: Int, afterRowIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val rows = sheet.rows.toMutableList()
    val colCount = rows.getOrNull(afterRowIndex)?.cells?.size ?: 1
    rows.add(afterRowIndex + 1, OdfRow(List(colCount) { OdfCell(text = "") }))
    sheets[sheetIndex] = sheet.copy(rows = rows)
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.addColumn(sheetIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val rows = sheet.rows.map { row -> OdfRow(row.cells + OdfCell(text = "")) }
    sheets[sheetIndex] = sheet.copy(rows = rows)
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.deleteRow(sheetIndex: Int, rowIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    if (sheet.rows.size <= 1 || rowIndex !in sheet.rows.indices) return
    val rows = sheet.rows.toMutableList()
    rows.removeAt(rowIndex)
    sheets[sheetIndex] = sheet.copy(rows = rows)
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.deleteColumn(sheetIndex: Int, colIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val rows = sheet.rows.map { row ->
        val cells = row.cells.toMutableList()
        if (colIndex < cells.size && cells.size > 1) cells.removeAt(colIndex)
        OdfRow(cells)
    }
    sheets[sheetIndex] = sheet.copy(rows = rows)
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.renameSheet(sheetIndex: Int, newName: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    sheets[sheetIndex] = sheet.copy(name = newName)
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.addSheet() {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val rows = (0 until 10).map { OdfRow(List(5) { OdfCell(text = "") }) }
    sheets.add(OdfSheet("Sheet ${sheets.size + 1}", rows))
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.deleteSheet(sheetIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    if (doc.sheets.size <= 1 || sheetIndex !in doc.sheets.indices) return
    val sheets = doc.sheets.toMutableList()
    sheets.removeAt(sheetIndex)
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.setCellBold(sheetIndex: Int, rowIndex: Int, cellIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(bold = !it.bold) }
}

fun OfficeViewModel.setCellItalic(sheetIndex: Int, rowIndex: Int, cellIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(italic = !it.italic) }
}

fun OfficeViewModel.setCellColor(sheetIndex: Int, rowIndex: Int, cellIndex: Int, color: Long?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(textColor = color) }
}

fun OfficeViewModel.setCellBgColor(sheetIndex: Int, rowIndex: Int, cellIndex: Int, color: Long?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(backgroundColor = color) }
}

fun OfficeViewModel.setCellAlignment(sheetIndex: Int, rowIndex: Int, cellIndex: Int, alignment: androidx.compose.ui.text.style.TextAlign?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(alignment = alignment) }
}

/** Sets a cell border color. (C2) */
fun OfficeViewModel.setCellBorder(sheetIndex: Int, rowIndex: Int, cellIndex: Int, color: Long?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(borderColor = color) }
}

/** Sets a cell's number/date/currency/percentage display format. (C2/B6) */
fun OfficeViewModel.setCellNumberFormat(sheetIndex: Int, rowIndex: Int, cellIndex: Int, format: OdfNumberFormat?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    modifyCell(doc, sheetIndex, rowIndex, cellIndex) {
        it.copy(numberFormat = format, valueType = when {
            format == null -> it.valueType
            format.isDate -> "date"
            format.percent -> "percentage"
            format.currencySymbol != null -> "currency"
            else -> "float"
        })
    }
}

/** Sets freeze panes on a sheet: freeze the top [rows] rows and left [cols] columns. (C2) */
fun OfficeViewModel.setSheetFreeze(sheetIndex: Int, rows: Int, cols: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    sheets[sheetIndex] = sheet.copy(freezeRows = rows.coerceAtLeast(0), freezeCols = cols.coerceAtLeast(0))
    updateDocument(doc.copy(sheets = sheets))
}

/** Fills the source cell down to the last row of the sheet. (C2) */
fun OfficeViewModel.fillDownToEnd(sheetIndex: Int, srcRow: Int, col: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val last = doc.sheets.getOrNull(sheetIndex)?.rows?.lastIndex ?: return
    fillDown(sheetIndex, srcRow, col, last)
}

/** Copies the source cell's value/formula/format down to the rows below it in the same column. (C2) */
fun OfficeViewModel.fillDown(sheetIndex: Int, srcRow: Int, col: Int, toRow: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val src = sheet.rows.getOrNull(srcRow)?.cells?.getOrNull(col) ?: return
    if (toRow <= srcRow) return
    val rows = sheet.rows.toMutableList()
    for (r in (srcRow + 1)..toRow) {
        val row = rows.getOrNull(r) ?: continue
        if (col !in row.cells.indices) continue
        val cells = row.cells.toMutableList()
        cells[col] = cells[col].copy(
            text = src.text, formula = src.formula, valueType = src.valueType,
            numberValue = src.numberValue, numberFormat = src.numberFormat,
            bold = src.bold, italic = src.italic, textColor = src.textColor,
            backgroundColor = src.backgroundColor, alignment = src.alignment
        )
        rows[r] = OdfRow(cells)
    }
    sheets[sheetIndex] = sheet.copy(rows = rows)
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.mergeCells(sheetIndex: Int, startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val rows = sheet.rows.toMutableList()
    val colSpan = endCol - startCol + 1
    val rowSpan = endRow - startRow + 1
    for (r in startRow..endRow) {
        if (r >= rows.size) continue
        val cells = rows[r].cells.toMutableList()
        for (c in startCol..endCol) {
            if (c >= cells.size) continue
            cells[c] = if (r == startRow && c == startCol) {
                cells[c].copy(spannedColumns = colSpan, rowSpan = rowSpan)
            } else {
                cells[c].copy(isCovered = true)
            }
        }
        rows[r] = OdfRow(cells)
    }
    sheets[sheetIndex] = sheet.copy(rows = rows)
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.unmergeCells(sheetIndex: Int, rowIndex: Int, cellIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets[sheetIndex]
    val rows = sheet.rows.toMutableList()
    val cell = rows.getOrNull(rowIndex)?.cells?.getOrNull(cellIndex) ?: return
    if (cell.spannedColumns <= 1 && cell.rowSpan <= 1) return
    for (r in rowIndex until minOf(rowIndex + cell.rowSpan, rows.size)) {
        val cells = rows[r].cells.toMutableList()
        for (c in cellIndex until minOf(cellIndex + cell.spannedColumns, cells.size)) {
            cells[c] = if (r == rowIndex && c == cellIndex) cells[c].copy(spannedColumns = 1, rowSpan = 1) else cells[c].copy(isCovered = false)
        }
        rows[r] = OdfRow(cells)
    }
    sheets[sheetIndex] = sheet.copy(rows = rows)
    updateDocument(doc.copy(sheets = sheets))
}

fun OfficeViewModel.sortRows(sheetIndex: Int, colIndex: Int, ascending: Boolean) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val sorted = sheet.rows.sortedWith(compareBy<OdfRow> {
        val text = it.cells.getOrNull(colIndex)?.text ?: ""
        text.toDoubleOrNull() ?: Double.MAX_VALUE
    }.thenBy { it.cells.getOrNull(colIndex)?.text ?: "" })
    sheets[sheetIndex] = sheet.copy(rows = if (ascending) sorted else sorted.reversed())
    updateDocument(doc.copy(sheets = sheets))
}

internal fun OfficeViewModel.modifyCell(doc: OdfDocument.Spreadsheet, sheetIndex: Int, rowIndex: Int, cellIndex: Int, transform: (OdfCell) -> OdfCell) {
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val rows = sheet.rows.toMutableList()
    val row = rows.getOrNull(rowIndex) ?: return
    val cells = row.cells.toMutableList()
    if (cellIndex !in cells.indices) return
    cells[cellIndex] = transform(cells[cellIndex])
    rows[rowIndex] = OdfRow(cells)
    sheets[sheetIndex] = sheet.copy(rows = rows)
    updateDocument(doc.copy(sheets = sheets))
}
