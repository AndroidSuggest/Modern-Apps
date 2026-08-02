package com.vayunmathur.library.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The yes/no dialog, mostly for destructive actions.
 *
 * Twenty-four apps reached for [AlertDialog] directly and each made its own
 * call about button order, whether the destructive option was coloured, and
 * whether it was the confirm or the dismiss button. Delete confirmations in
 * particular were rewritten nearly every time one was needed.
 *
 * Set [destructive] for anything that loses data: it colours the confirm
 * button with the error colour, which is the one visual cue that reliably
 * makes someone read the dialog. Dialogs that collect input rather than a
 * yes/no answer should still use [AlertDialog] directly.
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    dismissLabel: String? = null,
    destructive: Boolean = false,
    // For the rare confirm label that needs its own styling; when null the
    // label is rendered from confirmLabel with the standard treatment.
    confirmContent: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = {
                // Dismiss first so the dialog cannot linger over whatever the
                // action navigates to.
                onDismiss()
                onConfirm()
            }) {
                if (confirmContent != null) confirmContent()
                else Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = dismissLabel?.let { { TextButton(onClick = onDismiss) { Text(it) } } },
    )
}
