package com.vayunmathur.library.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpRect

/**
 * Packs marker lists into the three parallel bulk arrays the native side reads:
 * ids, then interleaved lon/lat, then icon ids. Packed once per push rather than
 * crossing the JNI boundary per pin.
 */
internal fun packMapMarkers(pins: List<MapMarker>): Triple<LongArray, FloatArray, IntArray> {
    val ids = LongArray(pins.size)
    val lonLat = FloatArray(pins.size * 2)
    val icons = IntArray(pins.size)
    for (i in pins.indices) {
        val pin = pins[i]
        ids[i] = pin.id
        lonLat[i * 2] = pin.position.longitude.toFloat()
        lonLat[i * 2 + 1] = pin.position.latitude.toFloat()
        icons[i] = pin.icon
    }
    return Triple(ids, lonLat, icons)
}

/**
 * Packs vehicle lists into the four parallel bulk arrays the native vehicle
 * path reads: the three marker arrays plus per-vehicle route colours packed
 * as `0xRRGGBB` (`0` draws no ring). Separate from [packMapMarkers] so the pin
 * path keeps its three-array JNI signature.
 */
internal data class PackedVehicles(
    val ids: LongArray,
    val lonLat: FloatArray,
    val icons: IntArray,
    val colors: IntArray,
)

internal fun packVehicles(vehicles: List<MapMarker>): PackedVehicles {
    val ids = LongArray(vehicles.size)
    val lonLat = FloatArray(vehicles.size * 2)
    val icons = IntArray(vehicles.size)
    val colors = IntArray(vehicles.size)
    for (i in vehicles.indices) {
        val v = vehicles[i]
        ids[i] = v.id
        lonLat[i * 2] = v.position.longitude.toFloat()
        lonLat[i * 2 + 1] = v.position.latitude.toFloat()
        icons[i] = v.icon
        colors[i] = v.color and 0x00FFFFFF
    }
    return PackedVehicles(ids, lonLat, icons, colors)
}

/**
 * The route, traffic and picking half of [SurfaceMapRenderer], as `internal`
 * extensions so SurfaceMapRenderer.kt stays under the FileLength limit. Public
 * setters stay on the renderer and delegate here.
 */

/**
 * Draw [points] as a single-colour navigation route, over the basemap and under the
 * puck. `null` or fewer than two distinct points draws nothing, which is how a route is
 * cleared. The convenience path for a host that wants one line in one colour — Android
 * Auto, which has no view hierarchy to hang a Compose overlay in.
 *
 * Main thread, like everything else here. Not free, but paid once per route rather
 * than once per frame: the native side tessellates the polyline and uploads it here,
 * and never rebuilds it — the mesh is zoom-independent, so a navigation session costs
 * one tessellation however long the drive or however much the driver zooms. Setting
 * the same route again does re-tessellate, so drive this from a state change rather
 * than from a per-frame callback.
 */
internal fun SurfaceMapRenderer.setSingleRoute(points: List<GeoPoint>?, style: RouteStyle = RouteStyle()) {
    routeSegments = points?.let { listOf(RouteSegment(it, DEFAULT_ROUTE_COLOR)) }
    routeStyle = style
    applyRoute()
    invalidate()
}

/**
 * Draw a multi-segment, per-segment-coloured route (see [RouteOverlay]), over the
 * basemap and under the puck. `null` or an all-empty overlay draws nothing, which is
 * how a route is cleared. This is what the phone pushes: the traffic-band / transit /
 * travelled-grey colouring resolved on the device into one coloured segment per run.
 *
 * Same cost model as the single-colour [setSingleRoute]: one tessellation per push, never
 * per frame or per zoom step.
 */
internal fun SurfaceMapRenderer.setOverlayRoute(overlay: RouteOverlay?) {
    routeSegments = overlay?.segments
    routeStyle = overlay?.style ?: RouteStyle()
    applyRoute()
    invalidate()
}

/**
 * Push the live-traffic colour table: [ids] holds each segment's `component_id` and
 * [argbColors] the fully-resolved ARGB to draw it, index for index. The host owns the
 * theme, so the colours are final. Replaces the whole table each call; a segment whose id
 * is absent draws nothing (the basemap road shows through). Cheap — a pure recolour, no
 * tessellation — so it is safe to drive from a camera-idle callback.
 *
 * Remembered so it survives the surface being recreated. Has no visible effect unless the
 * traffic layer is enabled through [SurfaceMapRenderer.setLayers]/[LayerOptions.traffic].
 */
internal fun SurfaceMapRenderer.pushTrafficSpeeds(ids: LongArray, argbColors: IntArray) {
    trafficSpeeds = ids to argbColors
    applyTraffic()
    invalidate()
}

/** Clear the live-traffic overlay so it draws nothing until the next [pushTrafficSpeeds]. */
internal fun SurfaceMapRenderer.dropTraffic() {
    trafficSpeeds = null
    applyTraffic()
    invalidate()
}

/**
 * The id of the pin under a tap, or `0` when the tap hit no pin. [xDp]/[yDp] are Dp from the
 * viewport top-left. Reads the renderer's id buffer (see the native `pickAt`), so it stays
 * correct under tilt and never trails the basemap on a pan, unlike a Compose CPU hit-test.
 *
 * The returned value is whatever [MapMarker.id] the host set, so it maps the tap back to its
 * own feature. `internal` because it is wired through [Projection.pickMarker] like the label
 * pick, so a caller without a live renderer gets `0` rather than a dead handle.
 */
internal fun SurfaceMapRenderer.tapMarkerAt(xDp: Float, yDp: Float): Long {
    val h = handle
    if (h == 0L) return 0L
    return try {
        MapNative.pickAt(h, xDp, yDp)
    } catch (_: Throwable) {
        0L
    }
}

/**
 * Task-17 pick: placed labels intersecting [box] (Dp), restricted to
 * [layerIds] (our flat ids), in placement order. Parses the native
 * `\u0001`-joined rows; empty when the renderer isn't up or nothing hits.
 */
internal fun SurfaceMapRenderer.tapLabelsIn(box: DpRect, layerIds: Set<String>): List<PlacedLabel> {
    val h = handle
    if (h == 0L) return emptyList()
    return try {
        MapNative.pickLabels(h, box.left.value, box.top.value, box.right.value, box.bottom.value)
            .asSequence()
            .map { row -> row.split('\u0001') }
            .filter { parts -> parts.size == 6 && (layerIds.isEmpty() || parts[0] in layerIds) }
            .map { parts ->
                PlacedLabel(
                    layerId = parts[0],
                    name = parts[1],
                    kind = parts[2],
                    position = GeoPoint(
                        longitude = parts[3].toDoubleOrNull() ?: 0.0,
                        latitude = parts[4].toDoubleOrNull() ?: 0.0,
                    ),
                    // Unsigned on the native side; ids never come close to the sign bit
                    // (an OSM id shifted left two is ~36 bits), so a Long is roomy.
                    featureId = parts[5].toLongOrNull() ?: 0L,
                )
            }
            .toList()
    } catch (_: Throwable) {
        emptyList()
    }
}

internal fun SurfaceMapRenderer.applyRoute() {
    if (handle == 0L) return
    // Drawable segments only: fewer than two points strokes nothing, and dropping them
    // here keeps the native side's per-segment ranges aligned with the colours.
    val drawable = routeSegments?.filter { it.points.size >= 2 }
    if (drawable.isNullOrEmpty()) {
        MapNative.clearRoute(handle)
        return
    }
    // Flat lon/lat pairs across every segment, plus a point count and colour per
    // segment: three bulk arrays cross JNI instead of one call per point, which for a
    // cross-city route is thousands of crossings saved. Built here rather than held,
    // because a route is set once and this is not a per-frame path.
    val flat = FloatArray(drawable.sumOf { it.points.size } * 2)
    val lengths = IntArray(drawable.size)
    val colors = IntArray(drawable.size)
    var at = 0
    drawable.forEachIndexed { i, segment ->
        lengths[i] = segment.points.size
        colors[i] = segment.color.toArgb()
        for (point in segment.points) {
            flat[at * 2] = point.longitude.toFloat()
            flat[at * 2 + 1] = point.latitude.toFloat()
            at++
        }
    }
    MapNative.setRoute(
        handle,
        flat,
        lengths,
        colors,
        routeStyle.width.value,
        routeStyle.casingWidth.value,
        routeStyle.casingColor.toArgb(),
    )
}

internal fun SurfaceMapRenderer.applyTraffic() {
    if (handle == 0L) return
    val speeds = trafficSpeeds
    if (speeds == null) {
        MapNative.clearTraffic(handle)
    } else {
        MapNative.setTrafficSpeeds(handle, speeds.first, speeds.second)
    }
}

/**
 * The fill the single-colour [setSingleRoute] convenience paints: the car's `#1A73E8`,
 * the one route colour in this app authored against a real rendering (see
 * [RouteStyle]). A per-segment route carries its own colours instead.
 */
internal val DEFAULT_ROUTE_COLOR = Color(0xFF1A73E8)
