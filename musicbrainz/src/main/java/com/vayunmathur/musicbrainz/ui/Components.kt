package com.vayunmathur.musicbrainz.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.musicbrainz.R
import com.vayunmathur.musicbrainz.download.DownloadItem
import com.vayunmathur.musicbrainz.download.DownloadState

/**
 * Cover art with a placeholder behind it.
 *
 * The Cover Art Archive has no image for a good share of releases, and the request that
 * finds that out is a redirect chain to archive.org, so the placeholder is what most
 * lists actually show while scrolling.
 */
@Composable
fun CoverArtImage(url: String?, modifier: Modifier = Modifier, size: Int = 56) {
    Surface(
        modifier = modifier.size(size.dp).clip(RoundedCornerShape(6.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            IconAlbum(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            // Previews render without a network, and the loader would just log failures.
            if (url != null && !LocalInspectionMode.current) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.size(size.dp),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

/**
 * The trailing control on a track row: a tick when it is already owned, a progress
 * spinner while it is being fetched, otherwise a download button.
 */
@Composable
fun TrackTrailing(
    onDevice: Boolean,
    download: DownloadItem?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    when {
        onDevice -> IconCheckCircle(tint = MaterialTheme.colorScheme.primary)
        download == null || download.state == DownloadState.Failed ->
            IconButton(onDownload) { IconDownload() }
        download.state == DownloadState.Done -> IconCheckCircle(tint = MaterialTheme.colorScheme.primary)
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (download.state == DownloadState.Downloading && download.progress > 0f) {
                CircularProgressIndicator({ download.progress }, modifier = Modifier.size(20.dp))
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            IconButton(onCancel) { IconClose() }
        }
    }
}

/** Formats a track length as `m:ss`, or blank when MusicBrainz has no duration on file. */
@Composable
fun durationLabel(durationMs: Int?): String {
    if (durationMs == null || durationMs <= 0) return ""
    val totalSeconds = durationMs / 1000
    return stringResource(
        R.string.duration_format,
        totalSeconds / 60,
        "%02d".format(totalSeconds % 60),
    )
}

@Composable
fun SecondaryText(text: String?, modifier: Modifier = Modifier) {
    if (!text.isNullOrBlank()) {
        Text(
            text,
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}
