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
fun InsertHyperlinkDialog(onInsert: (text: String, url: String) -> Unit, onDismiss: () -> Unit) {
    var linkText by remember { mutableStateOf("") }
    var linkUrl by remember { mutableStateOf("https://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insert_hyperlink)) },
        text = {
            Column {
                TextField(value = linkText, onValueChange = { linkText = it }, label = { Text(stringResource(R.string.display_text)) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                TextField(value = linkUrl, onValueChange = { linkUrl = it }, label = { Text(stringResource(R.string.url)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (linkText.isNotBlank() && linkUrl.isNotBlank()) { onInsert(linkText, linkUrl); onDismiss() }
            }) { Text(stringResource(R.string.insert)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}
