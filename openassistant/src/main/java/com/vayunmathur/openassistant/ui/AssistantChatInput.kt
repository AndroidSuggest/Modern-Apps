package com.vayunmathur.openassistant.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.*
import com.vayunmathur.openassistant.R

/**
 * Message composer row, extracted from [AssistantChatUi.kt] to keep that file
 * under the length limit. Behavior identical — only moved.
 */
@Composable
fun ChatInput(
    modifier: Modifier,
    inputText: String,
    onTextChange: (String) -> Unit,
    selectedImageUris: List<Uri>,
    isRecording: Boolean,
    onAddImage: () -> Unit,
    onRecord: () -> Unit,
    onSend: () -> Unit,
    onCancelMedia: () -> Unit,
    onRemoveImage: (Uri) -> Unit
) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        if (selectedImageUris.isNotEmpty() || isRecording) {
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                if (selectedImageUris.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f, false)) {
                        items(selectedImageUris, key = { it.toString() }) { uri ->
                            Box(Modifier.size(80.dp)) {
                                AsyncImage(
                                    ImageRequest.Builder(context)
                                        .data(uri)
                                        .memoryCacheKey("chat-attach-$uri")
                                        .build(),
                                    null,
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton({ onRemoveImage(uri) }) { IconClose() }
                            }
                        }
                    }
                }
                if (isRecording) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.recording), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            IconButton(onCancelMedia) { IconClose() }
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp, 4.dp)) {
                IconButton(onAddImage) { IconAdd() }
                IconButton(onRecord) { Icon(painterResource(android.R.drawable.ic_btn_speak_now), "Voice") }
                TextField(
                    value = inputText,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.message_placeholder)) },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                )
                val canSend = inputText.isNotBlank() || selectedImageUris.isNotEmpty() || isRecording
                IconButton(enabled = canSend, onClick = onSend) {
                    IconSend(tint = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                }
            }
        }
    }
}
