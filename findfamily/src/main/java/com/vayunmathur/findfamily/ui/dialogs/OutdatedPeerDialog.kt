package com.vayunmathur.findfamily.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.findfamily.R

/**
 * Shown when the user tries to connect to a peer whose app is too old to support
 * post-quantum encryption (they registered only a classic key). FindFamily is
 * post-quantum only, so the connection can't proceed until the other person updates.
 */
@Composable
fun OutdatedPeerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.outdated_peer_title)) },
        text = { Text(stringResource(R.string.outdated_peer_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.done)) } }
    )
}
