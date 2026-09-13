package com.vayunmathur.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.pdf.R

/** Style picker: color palette + custom RGB, opacity, and line-width sliders.
 * Affects newly drawn annotations. */
@Composable
internal fun StyleDialog(
    color: Color,
    onColor: (Color) -> Unit,
    opacity: Float,
    onOpacity: (Float) -> Unit,
    strokeWidth: Float,
    onWidth: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = listOf(
        Color.Red, Color(0xFFFF9800), Color.Yellow, Color(0xFF4CAF50),
        Color.Cyan, Color.Blue, Color(0xFF3F51B5), Color(0xFF9C27B0),
        Color.Magenta, Color.Black, Color.Gray, Color.White,
    )
    var r by remember(color) { mutableFloatStateOf(color.red) }
    var g by remember(color) { mutableFloatStateOf(color.green) }
    var b by remember(color) { mutableFloatStateOf(color.blue) }
    com.vayunmathur.library.ui.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text(stringResource(UiR.string.done)) } },
        title = { Text(stringResource(R.string.style)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.color), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                    for (c in palette) {
                        Box(
                            Modifier
                                .padding(3.dp).size(28.dp).clip(CircleShape).background(c)
                                .pointerInput(c) {
                                    detectTapGestures {
                                        r = c.red; g = c.green; b = c.blue; onColor(Color(r, g, b))
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c.copy(alpha = 1f) == color.copy(alpha = 1f)) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFCCCCCC)))
                            }
                        }
                    }
                }
                Text(stringResource(R.string.red), style = MaterialTheme.typography.labelSmall)
                Slider(value = r, onValueChange = { r = it; onColor(Color(r, g, b)) })
                Text(stringResource(R.string.green), style = MaterialTheme.typography.labelSmall)
                Slider(value = g, onValueChange = { g = it; onColor(Color(r, g, b)) })
                Text(stringResource(R.string.blue), style = MaterialTheme.typography.labelSmall)
                Slider(value = b, onValueChange = { b = it; onColor(Color(r, g, b)) })
                Text(stringResource(R.string.opacity, (opacity * 100).toInt()), style = MaterialTheme.typography.labelSmall)
                Slider(value = opacity, onValueChange = onOpacity, valueRange = 0.1f..1f)
                Text(stringResource(R.string.line_width, strokeWidth.toInt()), style = MaterialTheme.typography.labelSmall)
                Slider(value = strokeWidth, onValueChange = onWidth, valueRange = 1f..20f)
            }
        },
    )
}
