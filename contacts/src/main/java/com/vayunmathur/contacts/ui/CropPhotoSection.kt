package com.vayunmathur.contacts.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

@Composable
fun CropPhotoSection(onCancel: () -> Unit) {
    TextButton(onClick = onCancel) { Text(stringResource(UiR.string.cancel)) }
}
