package com.vayunmathur.passwords.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun PasswordScreenSection(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCopy) {
            IconCopy()
        }
    }
}
