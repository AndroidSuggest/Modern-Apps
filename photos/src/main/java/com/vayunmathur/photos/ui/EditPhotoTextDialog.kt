package com.vayunmathur.photos.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.textFontFamilies

@Composable
fun EditPhotoTextDialog(
    texts: SnapshotStateList<TextElement>,
    textToEdit: TextElement?,
    onDismiss: () -> Unit,
) {
    textToEdit?.let { textElement ->
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(8.dp)) {
                Column(modifier = Modifier.padding(16.dp).widthIn(max = 340.dp)) {
                    fun updateText(transform: (TextElement) -> TextElement) {
                        val index = texts.indexOfFirst { it.id == textElement.id }
                        if (index != -1) texts[index] = transform(texts[index])
                    }
                    var localText by remember { mutableStateOf(textElement.text) }
                    val current = texts.firstOrNull { it.id == textElement.id } ?: textElement
                    TextField(
                        value = localText,
                        onValueChange = { newText ->
                            localText = newText
                            updateText { it.copy(text = newText) }
                        },
                        textStyle = TextStyle(fontSize = 18.sp),
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.edit_text)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.font), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        textFontFamilies.forEach { fam ->
                            SelectableChip(fam, current.fontFamily == fam, { updateText { it.copy(fontFamily = fam) } })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SelectableChip("Bold", current.bold, { updateText { it.copy(bold = !it.bold) } })
                        SelectableChip("Italic", current.italic, { updateText { it.copy(italic = !it.italic) } })
                        SelectableChip("Left", current.align == 0, { updateText { it.copy(align = 0) } })
                        SelectableChip("Center", current.align == 1, { updateText { it.copy(align = 1) } })
                        SelectableChip("Right", current.align == 2, { updateText { it.copy(align = 2) } })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = onDismiss) { IconCheck() }
                    }
                }
            }
        }
    }
}
