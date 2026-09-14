package com.vayunmathur.maps.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.RouteOverlay
import com.vayunmathur.library.map.RouteSegment
import com.vayunmathur.library.map.RouteStyle
import com.vayunmathur.maps.ui.theme.MapTokens
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce

/**
 * How long the camera must sit still before the rail network refetches: the
 * repo's debounce idiom (see the traffic prefetch in [MapSurface]). A pan
 * across the city would otherwise fire a pack query per frame.
 */
private const val RAIL_FETCH_DEBOUNCE_MS = 500L

/**
 * Below this zoom a viewport spans far more track than a lines overlay can
 * usefully draw (and the native query caps at 1500 lines). The pack's own
 * floor is z8; one step in keeps the fetch to the metro in view.
 */
private const val RAIL_MIN_ZOOM = 9.0

/**
 * How far the viewport centre may drift (degrees) before the network
 * refetches. A degree is ~111 km, so this is ~2 km: pans inside the covered
 * bbox reuse the last fetch rather than re-querying per nudge.
 */
private const val RAIL_RECENTER_DEGREES = 0.02

/**
 * Parse a 6-digit `RRGGBB` pack colour, or null when absent/malformed. The
 * pack stores colour without a `#`; [parseHexColor] in RouteOverlayBuilder
 * wants the hash, so it is added here.
 */
private fun packHexColor(hex: String?): Color? =
    hex?.takeIf { it.length == 6 }?.let {
        runCatching { Color(android.graphics.Color.parseColor("#$it")) }.getOrNull()
    }

/**
 * The pack-driven rail-lines network for the viewport, as a [RouteOverlay
 * ], or null when the transit layer is off, zoomed too far out, or the pack
 * carries no shapes for the area.
 *
 * Fetched from the on-device timetable pack (GTFS-shape polylines per route),
 * not the tile layer that needs an archive rebuild. Drawn in the separate
 * rail slot under the navigation route, in each line's agency colour with the
 * route fallback where the pack carries none. Re-fetched on settle when the
 * viewport outgrows the covered bbox; the native side caps the enumeration.
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberRailLines(
    camera: CameraState,
    transitEnabled: Boolean,
    tokens: MapTokens,
): RouteOverlay? {
    val context = LocalContext.current
    var overlay by remember { mutableStateOf<RouteOverlay?>(null) }
    // Last fetch footprint: reused while the camera stays inside it.
    var fetchedCentre by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var fetchedZoom by remember { mutableStateOf(Double.NaN) }

    LaunchedEffect(transitEnabled) {
        if (!transitEnabled) {
            overlay = null
            fetchedCentre = null
            fetchedZoom = Double.NaN
        }
    }

    LaunchedEffect(camera, transitEnabled) {
        if (!transitEnabled) return@LaunchedEffect
        snapshotFlow { camera.position }
            .debounce(RAIL_FETCH_DEBOUNCE_MS)
            .collectLatest {
                val bounds = camera.visibleBoundsOrWorld()
                val zoom = camera.position.zoom
                if (zoom < RAIL_MIN_ZOOM) {
                    overlay = null
                    return@collectLatest
                }
                val centre = (bounds.south + bounds.north) / 2.0 to
                    (bounds.west + bounds.east) / 2.0
                val last = fetchedCentre
                if (last != null &&
                    kotlin.math.abs(centre.first - last.first) < RAIL_RECENTER_DEGREES &&
                    kotlin.math.abs(centre.second - last.second) < RAIL_RECENTER_DEGREES &&
                    kotlin.math.abs(zoom - fetchedZoom) < 1.0
                ) {
                    return@collectLatest
                }
                // Pad the query past the viewport so small pans stay covered.
                val padLat = (bounds.north - bounds.south) * 0.25 + 0.05
                val padLon = (bounds.east - bounds.west) * 0.25 + 0.05
                val lines = try {
                    OfflineRouter.railLines(
                        context,
                        minLat = bounds.south - padLat,
                        minLon = bounds.west - padLon,
                        maxLat = bounds.north + padLat,
                        maxLon = bounds.east + padLon,
                    )
                } catch (_: Exception) {
                    emptyList()
                }
                fetchedCentre = centre
                fetchedZoom = zoom
                overlay = if (lines.isEmpty()) {
                    null
                } else {
                    RouteOverlay(
                        segments = lines.map { line ->
                            RouteSegment(
                                points = line.points,
                                color = packHexColor(line.color)
                                    ?: tokens.routeTransitFallback,
                            )
                        },
                        // Thinner than the 8 dp navigation route with no
                        // casing: a network backdrop, not a followed line.
                        style = RouteStyle(width = 4.dp, casingWidth = 0.dp),
                    )
                }
            }
    }
    return overlay
}
