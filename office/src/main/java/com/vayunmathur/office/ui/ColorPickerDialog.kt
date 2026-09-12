package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun ColorPickerDialog(title: String, onColorSelected: (Long?) -> Unit, onDismiss: () -> Unit) {
    val colors = listOf(
        null, 0xFF000000L, 0xFFFFFFFF, 0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL,
        0xFFFFFF00L, 0xFFFF00FFL, 0xFF00FFFFL, 0xFF800000L, 0xFF008000L, 0xFF000080L,
        0xFF808000L, 0xFF800080L, 0xFF008080L, 0xFF808080L, 0xFFC0C0C0L,
        0xFFFF6600L, 0xFF6633CCL, 0xFF336699L, 0xFF993366L, 0xFF333300L,
        0xFF003300L, 0xFF003366L, 0xFF660066L, 0xFF333333L
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for (row in colors.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in row) {
                            val bgColor = c?.let { Color(it.toInt()) } ?: Color.Transparent
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(bgColor, CircleShape)
                                    .border(1.dp, Color.Gray, CircleShape)
                                    .clickable { onColorSelected(c); onDismiss() }
                            ) {
                                if (c == null) Text("∅", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}
