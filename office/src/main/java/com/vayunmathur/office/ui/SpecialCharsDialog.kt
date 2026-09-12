package com.vayunmathur.office.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.office.R

@Composable
fun SpecialCharsDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val chars = listOf(
        "©", "®", "™", "°", "±", "×", "÷", "≈", "≠", "≤", "≥", "∞",
        "•", "·", "—", "–", "…", "§", "¶", "†", "‡", "★", "☆", "✓",
        "→", "←", "↑", "↓", "⇒", "⇔", "€", "£", "¥", "¢", "α", "β",
        "γ", "δ", "π", "Σ", "Ω", "µ", "½", "¼", "¾", "²", "³", "“"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.special_character)) },
        text = {
            Column {
                for (row in chars.chunked(6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (ch in row) {
                            Surface(
                                modifier = Modifier.size(40.dp).clickable { onPick(ch); onDismiss() },
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) { Box(contentAlignment = Alignment.Center) { Text(ch, style = MaterialTheme.typography.titleMedium) } }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}
