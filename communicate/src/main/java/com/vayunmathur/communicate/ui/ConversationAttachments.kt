package com.vayunmathur.communicate.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.communicate.data.CommunicateAttachment
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconAttachment
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

@Composable
internal fun MessageAttachment(
    attachment: CommunicateAttachment,
    bubbleColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    shape: RoundedCornerShape,
) {
    val context = LocalContext.current
    val openAttachment = {
        ExternalIntents.launch(
            context,
            Intent(Intent.ACTION_VIEW, attachment.contentUri.toUri()),
        )
        Unit
    }
    if (attachment.mimeType.startsWith("image/")) {
        var showFallback by remember(attachment.contentUri) { mutableStateOf(false) }
        if (!showFallback) {
            Surface(
                shape = shape,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .heightIn(max = 260.dp)
                    .padding(top = 2.dp)
                    .clickable(onClick = openAttachment),
            ) {
                AsyncImage(
                    model = attachment.contentUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onState = { state -> showFallback = state is AsyncImageState.Error },
                    modifier = Modifier
                        .widthIn(min = 180.dp, max = 320.dp)
                        .heightIn(min = 120.dp, max = 260.dp),
                )
            }
        }
        if (showFallback) {
            AttachmentFallbackRow(
                attachment = attachment,
                bubbleColor = bubbleColor,
                contentColor = contentColor,
                shape = shape,
                onClick = openAttachment,
            )
        }
    } else {
        AttachmentFallbackRow(
            attachment = attachment,
            bubbleColor = bubbleColor,
            contentColor = contentColor,
            shape = shape,
            onClick = openAttachment,
        )
    }
}

@Composable
internal fun AttachmentFallbackRow(
    attachment: CommunicateAttachment,
    bubbleColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    Surface(
        color = bubbleColor,
        contentColor = contentColor,
        shape = shape,
        modifier = Modifier
            .widthIn(max = 320.dp)
            .padding(top = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAttachment()
            Spacer(Modifier.size(6.dp))
            Text(attachment.mimeType, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The picker's display name for a document, so a shared file arrives titled rather than as a blob. */
internal fun displayNameOf(context: android.content.Context, uri: android.net.Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
} catch (_: Throwable) {
    null
}
