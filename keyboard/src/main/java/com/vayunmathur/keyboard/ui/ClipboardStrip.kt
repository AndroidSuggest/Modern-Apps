package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.R
import com.vayunmathur.keyboard.util.ClipItem
import com.vayunmathur.keyboard.util.ClipboardStore
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconPaste
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Longest side, in pixels, that a cached clipboard image is decoded down to for previews. */
private const val THUMBNAIL_PX = 256

/**
 * The chip that appears in the strip right after a copy: tap the icon to open the full
 * clipboard, tap the body to paste, tap the X to throw the clip away.
 *
 * It takes over the strip slot rather than adding a permanent row — suggestions and a
 * fresh clip never want the space at the same moment, because the clip is offered before
 * the user has typed anything and goes away as soon as they do.
 */
@Composable
fun ClipboardStrip(
    height: Dp,
    item: ClipItem,
    onOpen: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconPaste(
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onPaste)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipPreview(item, Modifier.weight(1f), fontSize = 14.sp)
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDelete)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconClose(
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A clip rendered as one line of text or a thumbnail. Sensitive text is blurred until the
 * eye is tapped; the reveal is local to this composable and resets whenever it leaves the
 * screen, so nothing stays unblurred behind the user's back.
 */
@Composable
fun ClipPreview(
    item: ClipItem,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
) {
    var revealed by remember(item.id) { mutableStateOf(false) }
    val thumbnail = rememberClipThumbnail(item)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val hidden = item.sensitive && !revealed
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            when {
                thumbnail != null -> Image(
                    bitmap = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (hidden) Modifier.blur(10.dp) else Modifier),
                )
                item.isImage -> Text(
                    text = stringResource(R.string.image),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = fontSize,
                )
                else -> Text(
                    text = item.preview,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // RenderEffect-backed blur, which needs API 31 — the module's minSdk.
                    modifier = if (hidden) Modifier.blur(8.dp) else Modifier,
                )
            }
        }
        if (item.sensitive) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { revealed = !revealed }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                val tint = MaterialTheme.colorScheme.onSurfaceVariant
                if (revealed) {
                    IconVisibilityOff(modifier = Modifier.size(18.dp), tint = tint)
                } else {
                    IconVisible(modifier = Modifier.size(18.dp), tint = tint)
                }
            }
        }
    }
}

/** Decode an image clip's thumbnail off the main thread, once per clip. */
@Composable
private fun rememberClipThumbnail(item: ClipItem): ImageBitmap? {
    val file = item.imageFile ?: return null
    return produceState<ImageBitmap?>(initialValue = null, key1 = item.id) {
        value = withContext(Dispatchers.IO) {
            ClipboardStore.decodeThumbnail(file, THUMBNAIL_PX)?.asImageBitmap()
        }
    }.value
}
