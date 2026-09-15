package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.photos.R

/**
 * Panorama / motion-photo buttons pinned to the top end of the viewer.
 *
 * The still-vs-playback decision stays in [PhotoDetailView]; this is only the
 * chrome that triggers it, so the viewer body keeps one public composable.
 */
@Composable
fun BoxScope.PhotoViewerActions(
    isPanorama: Boolean,
    isSphere: Boolean,
    isMotionPhoto: Boolean,
    isMetadataVisible: Boolean,
    motionPlaying: Boolean,
    motionExtracting: Boolean,
    onOpenImmersive: () -> Unit,
    onMotionClick: () -> Unit,
) {
    if (!isPanorama && !isMotionPhoto) return
    FadeVisibility(
        visible = isMetadataVisible,
        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isPanorama) {
                FilledTonalButton(onClick = onOpenImmersive) {
                    Text(stringResource(if (isSphere) R.string.view_360 else R.string.view_panorama))
                }
            }
            if (isMotionPhoto) {
                FilledTonalButton(
                    onClick = onMotionClick,
                    enabled = !motionExtracting
                ) {
                    Text(
                        stringResource(
                            if (motionPlaying) R.string.stop_motion_photo
                            else R.string.play_motion_photo
                        )
                    )
                }
            }
        }
    }
}
