package com.vayunmathur.office.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.util.OfficeNative

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

internal fun columnLabel(index: Int): String {
    val sb = StringBuilder(); var n = index
    do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
    return sb.toString()
}
