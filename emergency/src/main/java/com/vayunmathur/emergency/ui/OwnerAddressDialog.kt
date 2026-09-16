package com.vayunmathur.emergency.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.emergency.R
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Chooses which of a picked contact's postal addresses to save as the owner's
 * address. Shown only when the contact has several; zero or one address saves
 * without asking.
 */
@Composable
fun OwnerAddressDialog(
    name: String,
    addresses: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.owner_address_chooser_title, name)) },
        text = {
            LazyColumn {
                items(addresses, key = { it }) { address ->
                    ListItem(
                        headlineContent = { Text(address) },
                        modifier = Modifier.clickable { onConfirm(address) },
                    )
                }
            }
        },
        confirmButton = { },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
