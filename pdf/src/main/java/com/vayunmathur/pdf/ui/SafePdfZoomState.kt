package com.vayunmathur.pdf.ui

import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * Pinch-to-zoom + pan state shared by the page list: two-finger zoom around
 * the gesture centroid plus the multi-touch flag that disables list scrolling
 * while a pinch is in progress.
 *
 * Logic is verbatim from [SafePdfViewerScreen]'s body.
 */
internal class SafePdfZoomState {
    var viewportSize by mutableStateOf(IntSize.Zero)
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    // Widest page seen so far (PDF points), used to raise the zoom cap for large
    // pages so their detail can be inspected. Monotonic across lazily-loaded pages.
    var maxPageWidthPts by mutableFloatStateOf(0f)
    // True while ≥2 fingers are down. The LazyColumn's scroll is a descendant of
    // the transformable, so it can otherwise claim a two-finger drag as a scroll
    // before the pinch is recognized; disabling scroll during multi-touch makes a
    // pinch always zoom.
    var multiTouch by mutableStateOf(false)

    fun onPageWidth(w: Float) {
        if (w > maxPageWidthPts) maxPageWidthPts = w
    }

    /** Apply a transformable delta: zoom about the centroid, then pan, clamped. */
    fun onTransform(centroid: Offset, zoomChange: Float, panChange: Offset) {
        val zoomOld = zoom
        val maxZoom = maxZoomFor(maxPageWidthPts, viewportSize.width)
        zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, maxZoom)
        // Keep the content point under the gesture centroid fixed while zooming, then
        // apply the two-finger drag. `transformable` sits above the graphicsLayer in the
        // modifier chain, so centroid/panChange already arrive in viewport pixels, and
        // the layer pivots on its top-centre.
        val origin = Offset(viewportSize.width / 2f, 0f)
        val pivoted = pan + (centroid - origin - pan) * (1f - zoom / zoomOld)
        // Below zoom 1 the content already fits the viewport exactly, so there is
        // nothing to pan to.
        pan = if (zoom > 1f) clampPan(pivoted + panChange, zoom, viewportSize) else Offset.Zero
    }

    /** Animate-free jump used by the double-tap tween in [SafePdfPageViewer]. */
    fun setZoomPan(z: Float, p: Offset) {
        zoom = z
        pan = p
    }
}

@Composable
internal fun rememberSafePdfZoomState(): Pair<SafePdfZoomState, TransformableState> {
    val state = remember { SafePdfZoomState() }
    val transform = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        state.onTransform(centroid, zoomChange, panChange)
    }
    return state to transform
}
