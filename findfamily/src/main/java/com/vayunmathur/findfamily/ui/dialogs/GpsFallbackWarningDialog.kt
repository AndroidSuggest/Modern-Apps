package com.vayunmathur.findfamily.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.findfamily.R

/**
 * Explains that the device has no network location provider so the app has
 * fallen back to GPS-only tracking, which can cause significant battery drain.
 * Shown when the user taps the GPS-fallback warning icon in the top bar.
 */
@Composable
fun GpsFallbackWarningDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gps_fallback_warning_title)) },
        text = { Text(stringResource(R.string.gps_fallback_warning_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.done)) } }
    )
}
