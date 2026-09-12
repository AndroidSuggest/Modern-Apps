package com.vayunmathur.email.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.email.R
import com.vayunmathur.email.ui.composer.EmailHtmlEditorController
import com.vayunmathur.library.ui.*

/**
 * Inline-image thumbnails and attachment rows for the composer, extracted from
 * [ComposerScreen] to keep that file under the length limit.
 * Behavior identical — only moved.
 */
@Composable
fun ComposerAttachmentSection(
    context: Context,
    bodyController: EmailHtmlEditorController,
    attachments: List<Uri>,
    onRemoveAttachment: (Uri) -> Unit,
) {
    // Inline images thumbnail row (text-based to avoid heavy deps; WYSIWYG preview already in editor)
    if (bodyController.inlineImages.isNotEmpty()) {
        val inlineTotal = bodyController.inlineImages.sumOf { uriSize(context, it.localUri) }
        Text(stringResource(R.string.inline_images, bodyController.inlineImages.size, android.text.format.Formatter.formatShortFileSize(context, inlineTotal)), style = MaterialTheme.typography.labelSmall)
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(bodyController.inlineImages.size) { idx ->
                val img = bodyController.inlineImages[idx]
                Card(modifier = Modifier.size(96.dp)) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            com.vayunmathur.library.ui.IconImage(modifier = Modifier.size(24.dp))
                            Text(img.fileName.take(16), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { bodyController.removeInlineImage(img.cid) }, modifier = Modifier.align(Alignment.TopEnd).size(20.dp)) {
                            com.vayunmathur.library.ui.IconClose(modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }

    if (attachments.isNotEmpty() || bodyController.inlineImages.isNotEmpty()) {
        val totalBytes = attachments.sumOf { uriSize(context, it) } + bodyController.inlineImages.sumOf { uriSize(context, it.localUri) }
        Text(stringResource(R.string.attachments_1, android.text.format.Formatter.formatShortFileSize(context, totalBytes)), style = MaterialTheme.typography.labelLarge)
        if (totalBytes > 25L * 1024 * 1024) {
            Text(
                stringResource(R.string.total_attachment_size_exceeds_25_mb_many),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (attachments.isNotEmpty()) {
        attachments.forEach { uri ->
            val attachmentLabel = remember(uri) {
                "${uriName(context, uri)} · " + android.text.format.Formatter.formatShortFileSize(context, uriSize(context, uri))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAttachment(modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    attachmentLabel,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemoveAttachment(uri) }) {
                    com.vayunmathur.library.ui.IconClose(modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
