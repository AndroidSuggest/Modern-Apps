package com.vayunmathur.maps.util

import android.content.Context
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.maps.R
import kotlin.time.Duration.Companion.seconds

/**
 * Converts native [OfflineRouter.RawStep]s into a [RouteService.Route], extracted from
 * [OfflineRouter] to keep that file under the length limit.
 *
 * Shared by the driving/walking `findRouteNative` path and the offline transit
 * `findTransitRouteNative` path (via [OfflineRouterTransit]).
 */
internal object OfflineRouterRouteBuilder {
    /**
     * Convert native [OfflineRouter.RawStep]s into a [RouteService.Route]: decode geometry,
     * localize maneuver text, attach transit details, and coalesce consecutive
     * same-road maneuvers. Shared by the driving/walking [findRouteNative] path
     * and the offline transit [findTransitRouteNative] path.
     */
    fun buildRoute(
            context: Context,
            rawSteps: Array<OfflineRouter.RawStep>,
            mode: RouteService.TravelMode
    ): RouteService.Route {
                val fullPolyline = mutableListOf<GeoPoint>()
                val processedSteps =
                        rawSteps.map { raw ->
                            val positions = mutableListOf<GeoPoint>()
                            for (i in raw.geometry.indices step 2) {
                                val pos = GeoPoint(raw.geometry[i], raw.geometry[i + 1])
                                positions.add(pos)
                                if (fullPolyline.isEmpty() || fullPolyline.last() != pos) {
                                    fullPolyline.add(pos)
                                }
                            }

                            val maneuver =
                                    RouteService.API.Maneuver.entries.getOrElse(raw.maneuverId) {
                                        RouteService.API.Maneuver.MANEUVER_UNSPECIFIED
                                    }
                            // Decode packed turn lanes into ordered left→right
                            // lane guidance. Each int is `dirMask * 2 + valid`,
                            // where dirMask is a bitmask of Maneuver ordinals the
                            // lane offers (real OSM turn:lanes can allow several
                            // turns, e.g. through+right) and bit0 is the active
                            // flag (lane leads onto the taken route).
                            val lanes = raw.lanePacked.map { code ->
                                val active = (code and 1) == 1
                                val mask = code ushr 1
                                val directions =
                                        RouteService.API.Maneuver.entries.filter { m ->
                                            (mask and (1 shl m.ordinal)) != 0
                                        }
                                RouteService.API.Lane(
                                        directions = directions.ifEmpty {
                                            listOf(RouteService.API.Maneuver.STRAIGHT)
                                        },
                                        active = active,
                                )
                            }
                            val hasName = raw.roadName.isNotBlank()
                            val instructionText =
                                    when (maneuver) {
                                        RouteService.API.Maneuver.DEPART ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_depart,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_depart_unnamed
                                                        )
                                        RouteService.API.Maneuver.STRAIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_straight,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_straight_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_turn_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_turn_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SLIGHT_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_slight_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_slight_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SLIGHT_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_slight_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_slight_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SHARP_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_sharp_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_sharp_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.TURN_SHARP_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_turn_sharp_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string
                                                                        .maneuver_turn_sharp_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.UTURN_LEFT,
                                        RouteService.API.Maneuver.UTURN_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_uturn,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_uturn_unnamed
                                                        )
                                        RouteService.API.Maneuver.MERGE ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_merge,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_merge_unnamed
                                                        )
                                        RouteService.API.Maneuver.RAMP_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_ramp_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_ramp_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.RAMP_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_ramp_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_ramp_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.FORK_LEFT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_fork_left,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_fork_left_unnamed
                                                        )
                                        RouteService.API.Maneuver.FORK_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_fork_right,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_fork_right_unnamed
                                                        )
                                        RouteService.API.Maneuver.ROUNDABOUT_LEFT,
                                        RouteService.API.Maneuver.ROUNDABOUT_RIGHT ->
                                                if (hasName)
                                                        context.getString(
                                                                R.string.maneuver_roundabout,
                                                                raw.roadName
                                                        )
                                                else
                                                        context.getString(
                                                                R.string.maneuver_roundabout_unnamed
                                                        )
                                        RouteService.API.Maneuver.WAIT -> {
                                            val waitSeconds = raw.duration10ms / 100
                                            val waitText = if (waitSeconds >= 60) "${waitSeconds / 60} min" else "$waitSeconds sec"
                                            if (raw.stopCode != null && raw.stopCode.isNotBlank())
                                                context.getString(R.string.maneuver_wait_at, waitText, raw.roadName, raw.stopCode)
                                            else
                                                context.getString(R.string.maneuver_wait, waitText, raw.roadName)
                                        }
                                        else ->
                                            if (raw.isTransit && raw.stopCode != null && raw.endStopCode != null)
                                                context.getString(R.string.maneuver_ride_transit, raw.roadName, raw.stopCode, raw.endStopCode, raw.stopCount)
                                            else if (mode == RouteService.TravelMode.TRANSIT)
                                                // A walk leg of an itinerary. The planner names
                                                // every one of them "Walk", which the road-name
                                                // templates below turn into "Continue onto Walk".
                                                // Phrased for real after coalescing, from the
                                                // merged duration; this placeholder only has to
                                                // compare equal between adjacent walk legs so
                                                // they still merge.
                                                WALK_LEG_PLACEHOLDER
                                            else if (hasName)
                                                context.getString(
                                                        R.string.maneuver_unspecified,
                                                        raw.roadName
                                                )
                                            else
                                                context.getString(
                                                        R.string
                                                                .maneuver_unspecified_unnamed
                                                )
                                    }

                            RouteService.Step(
                                    distanceMeters = raw.distanceMm / 1000.0,
                                    staticDuration = (raw.duration10ms / 100.0).seconds,
                                    polyline = positions,
                                    navInstruction =
                                            RouteService.API.NavInstruction(
                                                    maneuver,
                                                    instructionText
                                            ),
                                    travelMode = if (raw.isTransit) RouteService.TravelMode.TRANSIT
                                    else if (mode == RouteService.TravelMode.TRANSIT) RouteService.TravelMode.WALK
                                    else mode,
                                    speedRatio = raw.speedRatio,
                                    lanes = lanes,
                                    transitDetails = if (raw.isTransit && raw.gtfsFeed != null && raw.stopCode != null) {
                                        RouteService.API.TransitDetails(
                                            headsign = raw.headsign ?: "",
                                            stopCount = raw.stopCount,
                                            transitLine = RouteService.API.TransitLine(
                                                name = raw.roadName,
                                                // The index carries route_color for
                                                // every feed; GTFSProvider only sees
                                                // the bundled APK asset feed, so it is
                                                // just a fallback now.
                                                color = raw.routeColor
                                                    .takeIf { it != 0 }
                                                    ?.let { "#%06X".format(it and 0xFFFFFF) }
                                                    ?: GTFSProvider.getRouteColor(context, raw.gtfsFeed, raw.roadName)
                                                    ?: "#FF0000"
                                            ),
                                            stopDetails = RouteService.API.StopDetails(
                                                arrivalTime = formatServiceTime(raw.arrSecs),
                                                departureTime = formatServiceTime(raw.depSecs),
                                                arrivalStop = RouteService.API.Stop(raw.endStopCode ?: ""),
                                                departureStop = RouteService.API.Stop(raw.stopCode)
                                            ),
                                            feedName = raw.gtfsFeed
                                        )
                                    } else null
                            )
                        }

                // Coalesce consecutive maneuvers that stay on the same road.
                // The native router can emit a chain of small "slight left /
                // slight right" entries along a curving stretch of road
                // (e.g. El Camino Real bending through Palo Alto) where
                // the road name never changes — visually that's "stay on
                // the same road", not a series of turns. Merge those into
                // a single step whose polyline + distance + duration is the
                // sum of the merged steps.
                val coalescedSteps = mutableListOf<RouteService.Step>()
                for (step in processedSteps) {
                    val prev = coalescedSteps.lastOrNull()
                    // Smart-cast prev to non-null in the merge branch by
                    // gating on prev != null first.
                    if (prev != null &&
                        prev.travelMode == step.travelMode &&
                        step.travelMode != RouteService.TravelMode.TRANSIT &&
                        step.navInstruction.maneuver in NON_TURNING_MANEUVERS &&
                        // Road name unchanged (instruction text is
                        // road-name-templated, so equal strings ⇒ same road).
                        sameRoadName(prev.navInstruction.instructions, step.navInstruction.instructions)
                    ) {
                        coalescedSteps[coalescedSteps.lastIndex] = prev.copy(
                            distanceMeters = prev.distanceMeters + step.distanceMeters,
                            staticDuration = prev.staticDuration + step.staticDuration,
                            polyline = mergePolylines(prev.polyline, step.polyline),
                        )
                    } else {
                        coalescedSteps.add(step)
                    }
                }

                return RouteService.Route(
                        duration =
                                coalescedSteps.sumOf { it.staticDuration.inWholeSeconds }.seconds,
                        distanceMeters = coalescedSteps.sumOf { it.distanceMeters },
                        polyline = fullPolyline,
                        step = phraseWalkLegs(coalescedSteps, mode) { minutes ->
                            context.resources.getQuantityString(
                                    R.plurals.maneuver_walk_minutes,
                                    minutes,
                                    minutes,
                            )
                        },
                        departureTime = leaveAt(rawSteps),
                        arrivalTime = arriveAt(rawSteps),
                        elevationProfile = elevationProfile(rawSteps),
                        // The whole-route total is carried on every RawStep (0 on transit legs),
                        // so the first one is enough.
                        ascentMeters = rawSteps.firstOrNull()?.ascentM ?: 0.0,
                        descentMeters = rawSteps.firstOrNull()?.descentM ?: 0.0,
                )
    }

    /**
     * Build the route-wide elevation profile from the native [OfflineRouter.RawStep]s: walk every step's
     * per-coordinate [OfflineRouter.RawStep.elevations] (parallel to its geometry), drop the join point each step
     * shares with the previous one, and pair each with its cumulative ground distance from the
     * start. Empty when no step carries elevation (transit, or a graph with no DEM).
     */
    private fun elevationProfile(rawSteps: Array<OfflineRouter.RawStep>): List<RouteService.ElevationPoint> {
        val out = mutableListOf<RouteService.ElevationPoint>()
        var cumDist = 0.0
        var prevLon = Double.NaN
        var prevLat = Double.NaN
        for (raw in rawSteps) {
            if (raw.elevations.isEmpty()) continue
            val g = raw.geometry
            var k = 0
            while (k + 1 < g.size) {
                val lon = g[k]
                val lat = g[k + 1]
                val elev = raw.elevations.getOrNull(k / 2) ?: break
                val first = prevLon.isNaN()
                val dup = !first && lon == prevLon && lat == prevLat
                if (!dup) {
                    if (!first) cumDist += crowMeters(prevLat, prevLon, lat, lon)
                    out.add(RouteService.ElevationPoint(cumDist, elev))
                    prevLon = lon
                    prevLat = lat
                }
                k += 2
            }
        }
        return out
    }

    /** Approximate ground distance in metres between two lat/lon points (equirectangular). */
    private fun crowMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dlat = (lat2 - lat1) * 111_320.0
        val dlon = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians((lat1 + lat2) * 0.5))
        return Math.hypot(dlat, dlon)
    }

    /**
     * Maneuvers that we treat as "still on the same road" when their
     * instruction text doesn't change between steps. A SHARP turn or a
     * RAMP / FORK / MERGE / ROUNDABOUT is always a real maneuver even if
     * the road name happens to match.
     */
    private val NON_TURNING_MANEUVERS = setOf(
        RouteService.API.Maneuver.STRAIGHT,
        RouteService.API.Maneuver.TURN_SLIGHT_LEFT,
        RouteService.API.Maneuver.TURN_SLIGHT_RIGHT,
        RouteService.API.Maneuver.NAME_CHANGE,
        RouteService.API.Maneuver.MANEUVER_UNSPECIFIED,
    )

    /**
     * Two adjacent maneuvers are considered "on the same road" when the
     * instruction strings match. Instruction text is templated from the
     * road name (see the maneuver_* string templates), so identical
     * instruction strings ⇒ same road.
     */
    private fun sameRoadName(prev: String, curr: String): Boolean =
        prev.isNotBlank() && prev == curr

    /** Concatenate two step polylines, skipping the duplicate join point. */
    private fun mergePolylines(
        a: List<GeoPoint>,
        b: List<GeoPoint>,
    ): List<GeoPoint> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return if (a.last() == b.first()) a + b.drop(1) else a + b
    }
}
