package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Expanded layout for the photo viewer: filmstrip beside the viewer.
 *
 * Phone shows the viewer full-bleed with metadata as a bottom overlay
 * ([PhotoDetailView]). On Expanded the viewer keeps most of the window and a
 * filmstrip column takes the side fraction. Both widths are fractional (weights)
 * so the split scales with the window.
 *
 * Slots only: the caller reuses the viewer pager and a filmstrip list unchanged.
 */
@Composable
fun PhotoViewerWideLayout(
    viewer: @Composable (Modifier) -> Unit,
    filmstrip: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize()) {
        Column(Modifier.weight(0.72f).fillMaxHeight()) {
            viewer(Modifier.fillMaxSize())
        }
        Column(Modifier.weight(0.28f).fillMaxHeight()) {
            filmstrip(Modifier.fillMaxSize())
        }
    }
}
