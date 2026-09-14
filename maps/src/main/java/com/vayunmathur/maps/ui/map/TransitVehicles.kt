package com.vayunmathur.maps.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.map.CameraState
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapMarker
import com.vayunmathur.library.map.MarkerIcon
import com.vayunmathur.maps.util.OfflineRouter
import com.vayunmathur.maps.util.visibleBoundsOrWorld
import kotlinx.coroutines.delay

/**
 * How often the in-service vehicle set is recomputed, mirroring the ~1 Hz cadence the departures
 * board countdown uses ([com.vayunmathur.maps.ui.DeparturesSheet]). The native
 * `activeVehicles` folds schedule + realtime delay into each recompute; between recomputes the
 * renderer holds each sprite's last pushed position. One second of drift is a few metres at transit
 * speeds — small at the zooms this draws at — so a plain re-push each tick is the whole animation,
 * with no per-frame predictor. The native `Vehicle` carries a bearing but no speed, so there is no
 * cheap on-device dead-reckoning to do, and the shared sprite path is geography-agnostic; if smooth
 * 60 fps tweening is wanted later it belongs in the renderer's overlay draw, keyed on the stable
 * per-trip [OfflineRouter.Vehicle.id].
 */
private const val VEHICLE_TICK_MS = 1_000L

/**
 * Below this zoom the visible bbox spans too many in-service trips for a 1 Hz recompute to stay
 * cheap (the plan's own note on bounding the enumeration to the viewport), and the sprites would be
 * an unreadable swarm anyway. Matches the spirit of the traffic prefetch's own zoom gate.
 */
private const val VEHICLE_MIN_ZOOM = 11.0

/**
 * Whether the simulated vehicle sprites draw at all. Currently false: the
 * sprite atlas has no dedicated vehicle pictograms, so the vehicle ids borrow
 * the ambient POI icons (`VEHICLE_BUS` draws the bus-stop pin,
 * `VEHICLE_TRAM/TRAIN` the train-station pin) at 28 Dp against 19 Dp POIs
 * with no labels. Vehicles dwell exactly on stops, so several trips at one
 * station read as duplicated, misaligned, textless station POIs - which is
 * what the transit toggle showed. Rail lines, departure boards and routing
 * are unaffected (separate paths); flip this back on when dedicated vehicle
 * sprites land in the atlas (see `marker::icon_sprite_name`).
 */
private const val VEHICLE_SPRITES_ENABLED = false

/**
 * How far the bbox centre may drift (degrees) before a recompute is forced.
 * A degree of latitude is ~111 km, so this is ~50 m: a static camera reuses
 * the last enumeration rather than paying a JNI round-trip plus a full marker
 * rebuild every second for sprites that moved a few metres.
 */
private const val VEHICLE_BBOX_TOLERANCE_DEG = 0.0005

/**
 * How many consecutive 1 Hz ticks may reuse the last enumeration while the
 * camera is static. Positions are interpolated per recompute, so an unbounded
 * skip would freeze the sprites; this bounds the staleness to a few seconds
 * (a few dozen metres at transit speeds) while still cutting the steady-state
 * JNI + recomposition rate of a stationary map.
 */
private const val VEHICLE_MAX_REUSE_TICKS = 3

/**
 * The simulated in-service transit vehicles for the visible bbox, recomputed at ~1 Hz and mapped to
 * renderer markers, or an empty list when the transit layer is off, the surface is hidden, or the
 * camera is zoomed too far out (see [VEHICLE_MIN_ZOOM]).
 *
 * Drive [com.vayunmathur.library.map.VectorMap]'s `vehicles` with the return value: it is pushed on
 * its own cadence, apart from the app pins, so a vehicle recompute never churns the pins and the
 * moving sprites stay out of the pin tap-pick. The loop samples the camera's live bounds each tick,
 * so a pan or zoom re-targets the enumeration without restarting the effect. Gated on the lifecycle
 * with the same ON_START/ON_STOP observer the renderer uses, so a backgrounded or hidden map stops
 * recomputing and clears its vehicles rather than leaving a stale swarm behind.
 */
@Composable
fun rememberTransitVehicles(
    camera: CameraState,
    transitEnabled: Boolean,
): List<MapMarker> {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(false) }
    var vehicles by remember { mutableStateOf(emptyList<MapMarker>()) }

    // The same ON_START/ON_STOP gate VulkanMapSurface stops the renderer on: a STARTED-but-not-
    // RESUMED surface (split-screen, PiP) is on screen and must keep its vehicles moving.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> started = true
                Lifecycle.Event.ON_STOP -> started = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(transitEnabled, started) {
        // See VEHICLE_SPRITES_ENABLED: the overlay draws no vehicle sprites
        // until the atlas carries dedicated vehicle art. Clears any shown.
        if (!transitEnabled || !started || !VEHICLE_SPRITES_ENABLED) {
            vehicles = emptyList()
            return@LaunchedEffect
        }
        // Last enumeration, reused while the camera is static (see below).
        var lastCentreLat = Double.NaN
        var lastCentreLon = Double.NaN
        var lastZoom = Double.NaN
        var reuseTicks = 0
        while (true) {
            val bounds = camera.visibleBoundsOrWorld()
            val zoom = camera.position.zoom
            val centreLat = (bounds.south + bounds.north) / 2.0
            val centreLon = (bounds.west + bounds.east) / 2.0
            // A static camera reuses the last enumeration: the native call
            // interpolates positions per recompute, but a second of drift is
            // metres at transit speeds while the JNI round-trip plus the
            // marker-list rebuild and re-push cost every tick. Bounded by
            // VEHICLE_MAX_REUSE_TICKS so the sprites never freeze; reusing the
            // same list instance also skips recomposition downstream.
            val cameraStatic = zoom >= VEHICLE_MIN_ZOOM &&
                kotlin.math.abs(centreLat - lastCentreLat) < VEHICLE_BBOX_TOLERANCE_DEG &&
                kotlin.math.abs(centreLon - lastCentreLon) < VEHICLE_BBOX_TOLERANCE_DEG &&
                kotlin.math.abs(zoom - lastZoom) < 0.25
            if (!cameraStatic || reuseTicks >= VEHICLE_MAX_REUSE_TICKS) {
                vehicles = if (zoom < VEHICLE_MIN_ZOOM) {
                    emptyList()
                } else {
                    OfflineRouter.activeVehicles(
                        context,
                        minLat = bounds.south,
                        minLon = bounds.west,
                        maxLat = bounds.north,
                        maxLon = bounds.east,
                    ).map { v ->
                        MapMarker(
                            id = v.id,
                            position = GeoPoint(longitude = v.lon, latitude = v.lat),
                            icon = gtfsModeToMarkerIcon(v.mode),
                        )
                    }
                }
                lastCentreLat = centreLat
                lastCentreLon = centreLon
                lastZoom = zoom
                reuseTicks = 0
            } else {
                reuseTicks++
            }
            delay(VEHICLE_TICK_MS)
        }
    }
    return vehicles
}

/**
 * The renderer marker icon for a coarse GTFS mode label (see
 * [OfflineRouter.Vehicle.mode]). Trams/monorails, the rail family (subway/rail/funicular/aerial),
 * ferries and everything else (bus/trolleybus and any unknown) each fold onto one of the four
 * reserved `VEHICLE_*` sprites, which is as fine as the shared atlas draws.
 */
internal fun gtfsModeToMarkerIcon(mode: String): Int = when (mode) {
    "TRAM", "MONORAIL" -> MarkerIcon.VEHICLE_TRAM
    "SUBWAY", "RAIL", "FUNICULAR", "AERIAL" -> MarkerIcon.VEHICLE_TRAIN
    "FERRY" -> MarkerIcon.VEHICLE_FERRY
    else -> MarkerIcon.VEHICLE_BUS
}
