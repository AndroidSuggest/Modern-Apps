package com.vayunmathur.taxi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.intents.maps.DirectionsResult
import com.vayunmathur.library.map.CameraPosition
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.RouteOverlay
import com.vayunmathur.library.map.RouteSegment
import com.vayunmathur.library.map.RouteStyle
import kotlin.math.ln

/**
 * Turn a planned [DirectionsResult] into the coloured [RouteOverlay] the map renderer draws,
 * colouring each run by maps' own red/amber/green congestion ramp so a taxi route reads the same
 * as it does in the maps app. No casing and an 8 dp stroke, matching maps' phone route style.
 */
internal fun DirectionsResult.toRouteOverlay(isDark: Boolean): RouteOverlay? {
    val runs = segments
        .filter { it.points.size >= 2 }
        .map { seg ->
            RouteSegment(
                seg.points.map { GeoPoint(it.lng, it.lat) },
                trafficColor(seg.speedRatio, isDark),
            )
        }
    if (runs.isEmpty()) return null
    return RouteOverlay(runs, RouteStyle(width = 8.dp, casingWidth = 0.dp))
}

/**
 * The congestion colour for a step's [speedRatio], on the same three-band ramp and the same
 * light/dark hues maps' `MapTokens.traffic` uses, so the two apps agree on what a jam looks like.
 */
internal fun trafficColor(speedRatio: Double, isDark: Boolean): Color = when {
    speedRatio < 0.5 -> if (isDark) Color(0xFFEF5350) else Color(0xFFF44336)
    speedRatio < 0.9 -> if (isDark) Color(0xFFFFCA28) else Color(0xFFFFC107)
    else -> if (isDark) Color(0xFF66BB6A) else Color(0xFF4CAF50)
}

/**
 * A [CameraPosition] framing all of [points]: centred on their bounding box with a zoom picked
 * from its span. Mirrors the endpoint-framing math in [RideScreen]'s quote step. Null when there
 * is nothing to frame.
 */
internal fun routeBoundsCamera(points: List<GeoPoint>): CameraPosition? {
    if (points.isEmpty()) return null
    val minLon = points.minOf { it.longitude }
    val maxLon = points.maxOf { it.longitude }
    val minLat = points.minOf { it.latitude }
    val maxLat = points.maxOf { it.latitude }
    val centre = GeoPoint((minLon + maxLon) / 2.0, (minLat + maxLat) / 2.0)
    val spread = maxOf(maxLon - minLon, maxLat - minLat)
    val zoom = if (spread <= 0.0) 14.0 else (ln(360.0 / spread) / ln(2.0) - 1.0).coerceIn(10.0, 15.0)
    return CameraPosition(centre, zoom)
}
