package com.vayunmathur.library.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.log2

/**
 * North-up camera position. Bearing/tilt are intentionally unsupported (the
 * three migrated apps only ever used a north-up basemap), so the axis-aligned
 * overlays and image quad stay correct.
 */
data class CameraPosition(
    val target: GeoPoint = GeoPoint(0.0, 0.0),
    val zoom: Double = 0.0,
)

/**
 * Holds the live [CameraPosition] (as Compose state so gestures/animations
 * drive recomposition) and derives a [Projection] once the viewport is
 * measured. Mirrors maplibre-compose's `CameraState` surface used by the apps:
 * [position], [projection], [awaitProjection], [animateTo].
 */
class CameraState(initial: CameraPosition = CameraPosition()) {
    var position: CameraPosition by mutableStateOf(initial)

    /** Viewport size in logical (dp) units; null until first layout. */
    internal var viewportDp: Size? by mutableStateOf(null)

    /**
     * Sets the measured viewport and enforces the minimum "fill" zoom so the map
     * always covers the viewport (no blank margins when zoomed all the way out),
     * matching maplibre's behavior.
     */
    internal fun setViewport(size: Size) {
        viewportDp = size
        val floor = fillZoom(size)
        if (position.zoom < floor) position = position.copy(zoom = floor)
    }

    /** Smallest zoom at which the world fills [vp]'s larger dimension. */
    private fun fillZoom(vp: Size): Double {
        val dim = maxOf(vp.width, vp.height).toDouble()
        if (dim <= 0.0) return 0.0
        return log2(dim / Mercator.TILE_SIZE).coerceAtLeast(0.0)
    }

    /** Current projection, or null before the viewport has been measured. */
    val projection: Projection?
        get() = viewportDp?.let { vp ->
            Projection(position.target, position.zoom, vp.width, vp.height)
        }

    /** Suspends until the viewport is measured, then returns the projection. */
    suspend fun awaitProjection(): Projection =
        snapshotFlow { projection }.filterNotNull().first()

    /** Linearly interpolate center + zoom to [target] over [durationMs]. */
    suspend fun animateTo(target: CameraPosition, durationMs: Int = 500) {
        val start = position
        Animatable(0f).animateTo(1f, tween(durationMs)) {
            val t = value.toDouble()
            position = CameraPosition(
                target = GeoPoint(
                    lerp(start.target.longitude, target.target.longitude, t),
                    lerp(start.target.latitude, target.target.latitude, t),
                ),
                zoom = lerp(start.zoom, target.zoom, t),
            )
        }
    }

    /**
     * Applies a transform gesture, anchoring the geographic point under
     * [centroidDp] so pinch-zoom keeps that point fixed. Deltas are in logical
     * (dp) units.
     */
    internal fun onGesture(
        centroidDp: Offset,
        panDp: Offset,
        zoomChange: Float,
        minZoom: Double,
        maxZoom: Double,
        scrollEnabled: Boolean,
        zoomEnabled: Boolean,
    ) {
        val vp = viewportDp ?: return
        val halfW = vp.width / 2.0
        val halfH = vp.height / 2.0
        // Never zoom out past a full-screen world.
        val effectiveMinZoom = maxOf(minZoom, fillZoom(vp))
        var zoom = position.zoom

        // Pan: dragging content one way shifts the world the same way, so the
        // center moves opposite to the pan.
        val cWorld = Mercator.project(position.target.longitude, position.target.latitude, zoom)
        var cx = cWorld.x
        var cy = cWorld.y
        if (scrollEnabled) {
            cx -= panDp.x
            cy -= panDp.y
        }
        var center = Mercator.unproject(cx, cy, zoom)

        // Zoom about the centroid: keep the geo point under the fingers fixed.
        if (zoomEnabled && zoomChange != 1f && zoomChange > 0f) {
            val newZoom = (zoom + log2(zoomChange.toDouble())).coerceIn(effectiveMinZoom, maxZoom)
            if (newZoom != zoom) {
                val cw = Mercator.project(center.longitude, center.latitude, zoom)
                val underX = cw.x + (centroidDp.x - halfW)
                val underY = cw.y + (centroidDp.y - halfH)
                val geo = Mercator.unproject(underX, underY, zoom)
                val gw = Mercator.project(geo.longitude, geo.latitude, newZoom)
                center = Mercator.unproject(gw.x - (centroidDp.x - halfW), gw.y - (centroidDp.y - halfH), newZoom)
                zoom = newZoom
            }
        }

        position = CameraPosition(center, zoom)
    }
}

private fun lerp(start: Double, end: Double, t: Double): Double = start + (end - start) * t

/** Remembers a [CameraState] seeded with [initial]. */
@Composable
fun rememberCameraState(initial: CameraPosition = CameraPosition()): CameraState =
    remember { CameraState(initial) }
