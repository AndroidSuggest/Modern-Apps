package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.office.R

@Composable
fun SortDialog(maxCols: Int, onSort: (colIndex: Int, ascending: Boolean) -> Unit, onDismiss: () -> Unit) {
    var col by remember { mutableStateOf("0") }
    var ascending by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort_rows)) },
        text = {
            Column {
                TextField(value = col, onValueChange = { col = it },
                    label = { Text(stringResource(R.string.column_index_0, maxCols - 1)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.order), modifier = Modifier.weight(1f))
                    TextButton(onClick = { ascending = !ascending }) {
                        Text(if (ascending) "A→Z ↑" else "Z→A ↓")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = col.toIntOrNull()?.coerceIn(0, maxCols - 1) ?: 0
                onSort(c, ascending); onDismiss()
            }) { Text(stringResource(R.string.sort)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}
