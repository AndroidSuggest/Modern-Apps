package com.vayunmathur.code.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/** A unified-diff viewer that colours added/removed/hunk lines. */
@Composable
fun GitPageSection(diff: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diff)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                val lines = if (diff.isBlank()) listOf(stringResource(R.string.no_changes)) else diff.lines()
                lines.forEach { line ->
                    val color = when {
                        line.startsWith("+") && !line.startsWith("+++") -> MaterialTheme.colorScheme.tertiary
                        line.startsWith("-") && !line.startsWith("---") -> MaterialTheme.colorScheme.error
                        line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(text = line, color = color, style = TextStyle(fontFamily = FontFamily.Monospace))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
