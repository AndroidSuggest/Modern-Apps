package com.vayunmathur.office.ui

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
import androidx.compose.ui.res.stringResource
import com.vayunmathur.office.R

@Composable
fun GoToSlideDialog(total: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.go_to_slide)) },
        text = {
            TextField(value = input, onValueChange = { input = it },
                label = { Text(stringResource(R.string.slide_number_1, total)) }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = {
                val n = input.toIntOrNull()
                if (n != null && n in 1..total) { onGo(n - 1); onDismiss() }
            }) { Text(stringResource(R.string.go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}
