package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R

/**
 * State of the "link" toolbar button for the current selection/cursor, shared by
 * every WYSIWYG editor (markdown notes + HTML email).
 *
 * A `null` [LinkContext] means the button is disabled. A non-null value means the
 * button is enabled: [editing] is true when the cursor sits on an existing link
 * (so the dialog should pre-fill and edit it), and false when there is a
 * selection to turn into a new link.
 */
data class LinkContext(
    val editing: Boolean,
    val text: String,
    val url: String,
)

/**
 * Shared link editor dialog. Asks for the link text and the URL to point at,
 * pre-filled from [context]. Used by both the notes and email bottom toolbars so
 * the link UX is identical everywhere.
 */
@Composable
fun LinkDialog(
    context: LinkContext,
    onConfirm: (text: String, url: String) -> Unit,
    onDismiss: () -> Unit,
    onUnlink: (() -> Unit)? = null,
) {
    var text by remember(context) { mutableStateOf(context.text) }
    var url by remember(context) { mutableStateOf(context.url) }
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (context.editing) stringResource(R.string.link_dialog_title_edit) else stringResource(R.string.link_dialog_title_insert)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.link_field_text)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.link_field_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = { onConfirm(text.ifBlank { url }.trim(), url.trim()) },
            ) { Text(if (context.editing) stringResource(R.string.link_action_save) else stringResource(R.string.link_action_add)) }
        },
        dismissButton = {
            Row {
                if (context.editing && onUnlink != null) {
                    TextButton(onClick = onUnlink) { Text(stringResource(R.string.link_action_unlink)) }
                }
                if (context.editing) {
                    TextButton(
                        enabled = url.isNotBlank(),
                        onClick = { runCatching { uriHandler.openUri(url.trim()) }; onDismiss() },
                    ) { Text(stringResource(R.string.link_action_open)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.link_action_cancel)) }
            }
        },
    )
}
