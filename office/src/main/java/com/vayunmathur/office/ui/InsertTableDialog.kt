package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.office.R

@Composable
fun InsertTableDialog(onInsert: (rows: Int, cols: Int) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf("3") }
    var cols by remember { mutableStateOf("3") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insert_table)) },
        text = {
            Column {
                TextField(value = rows, onValueChange = { rows = it }, label = { Text(stringResource(R.string.rows)) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                TextField(value = cols, onValueChange = { cols = it }, label = { Text(stringResource(R.string.columns)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val r = rows.toIntOrNull()?.coerceIn(1, 50) ?: 3
                val c = cols.toIntOrNull()?.coerceIn(1, 26) ?: 3
                onInsert(r, c)
                onDismiss()
            }) { Text(stringResource(R.string.insert)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}
