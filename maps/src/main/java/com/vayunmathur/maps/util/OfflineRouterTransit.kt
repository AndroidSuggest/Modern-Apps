package com.vayunmathur.maps.util

import android.content.Context
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.ConnectivityMonitor
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import com.vayunmathur.maps.data.transit.TransitousDataSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Offline transit planning over the per-region `*.transit` packs, extracted from
 * [OfflineRouter] to keep that file under the length limit.
 *
 * The JNI surface (native methods, `RawStep`/`RawDeparture`/`RawVehicle`, the traffic fetch
 * callback) stays on [OfflineRouter] — native code resolves those by class and member name, so
 * moving them would break the lookup. Everything here is pure Kotlin and reaches the native
 * layer through [OfflineRouter]'s `internal` members.
 */
internal object OfflineRouterTransit {
    /**
     * How many pages [getStopDeparturesOffline] will walk to reach its `until`. A bound rather
     * than a target: a quiet stop arrives in one page, and the busiest hub in the world does not
     * get to spin the board reader for a day's worth of stop times.
     */
    private const val MAX_DEPARTURE_PAGES = 12

    /**
     * MOTIS realtime, flattened for the JNI overlay arguments. [coords] is
     * interleaved `[lat, lon, ...]`, [times] is interleaved
     * `[schedSecs, delaySecs, cancelled, ...]`, and both are parallel to
     * [routes]. Empty means "plan against the schedule only".
     */
    private class Overlay(
            val coords: DoubleArray,
            val routes: Array<String>,
            val times: IntArray,
    ) {
        val isEmpty: Boolean get() = routes.isEmpty()

        companion object {
            val EMPTY = Overlay(DoubleArray(0), emptyArray(), IntArray(0))
        }
    }

    /**
     * Pack names under the base path, listed once.
     *
     * Every transit entry point used to `listFiles` this directory and loop over
     * the result; in practice there is exactly one pack. Cleared by [onReload], which
     * [OfflineRouter.reload] calls once a download has replaced it.
     */
    @Volatile
    private var cachedTransitFeeds: List<String>? = null

    /** Drops the cached pack list; called by [OfflineRouter.reload]. */
    fun onReload() {
        cachedTransitFeeds = null
    }

    private fun transitFeeds(base: String): List<String> {
        cachedTransitFeeds?.let { return it }
        val feeds = File(base)
                .listFiles { f -> f.isFile && f.name.endsWith(".transit") }
                ?.map { it.name.removeSuffix(".transit") }
                .orEmpty()
        // An empty result is not cached: the pack may still be downloading, and
        // remembering "none" would disable offline transit until the next restart.
        if (feeds.isNotEmpty()) cachedTransitFeeds = feeds
        return feeds
    }

    /**
     * Offline transit routing (P11d): plan a journey with the on-device RAPTOR
     * planner over any downloaded per-region `*.transit` index that covers the
     * endpoints. Returns null when no index is present/covering or no journey is
     * found — the caller then falls back to the P10 online Transitous planner.
     *
     * Runs at most **two** RAPTOR passes: a schedule-only plan, then, when the
     * device is online, a replan against MOTIS realtime for the stops that plan
     * actually touches, so a cancelled or badly delayed trip is avoided. If the
     * replan finds nothing we keep the schedule-only journey rather than
     * iterating.
     */
    suspend fun getTransitRouteOffline(
            context: Context,
            start: GeoPoint,
            end: GeoPoint
    ): RouteService.Route? = withContext(Dispatchers.Default) {
        val base = OfflineRouter.transitBase(context) ?: return@withContext null
        val feeds = transitFeeds(base)
        if (feeds.isEmpty()) return@withContext null

        for (feed in feeds) {
            // The index is world-merged, so query times must be in the feed's
            // timezone. Journeys spanning two zones use the origin's — a known
            // limitation, but far better than always using the device's.
            val clock = transitClock(
                    runCatching {
                        OfflineRouter.getFeedTimezoneNative(base, feed, start.latitude, start.longitude)
                    }.getOrNull()
            )
            val plan = { overlay: Overlay ->
                try {
                    OfflineRouter.findTransitRouteNative(
                            base, feed,
                            start.latitude, start.longitude,
                            end.latitude, end.longitude,
                            clock.depSecs, clock.weekday, clock.date,
                            clock.prevWeekday, clock.prevDate,
                            overlay.coords, overlay.routes, overlay.times
                    )
                } catch (_: Exception) {
                    null
                }
            }

            val scheduled = plan(Overlay.EMPTY)
            if (scheduled == null || scheduled.isEmpty()) continue

            val overlay = realtimeOverlay(context, journeyStops(scheduled), clock)
            val raw = if (overlay.isEmpty) scheduled else plan(overlay) ?: scheduled
            return@withContext OfflineRouterRouteBuilder.buildRoute(context, raw, RouteService.TravelMode.TRANSIT)
        }
        null
    }

    /**
     * Board and alight stops of every ride in a planned journey, as
     * `(position, MOTIS id)`.
     *
     * The id is baked into the v5 pack, which is what lets the overlay name a stop
     * to `/stoptimes` directly. A leg whose pack predates v5, or whose feed's
     * Transitous source name the build did not know, carries no id and is dropped:
     * without one there is no way to ask about it now that the `/map/stops`
     * proximity lookup is gone, and it simply stays schedule-only.
     */
    private fun journeyStops(steps: Array<OfflineRouter.RawStep>): List<Pair<GeoPoint, String>> =
            steps.filter { it.isTransit && it.geometry.size >= 4 }
                    .flatMap { s ->
                        val g = s.geometry
                        listOfNotNull(
                                s.boardStopId?.ifBlank { null }
                                        ?.let { GeoPoint(g[0], g[1]) to it },
                                s.alightStopId?.ifBlank { null }
                                        ?.let { GeoPoint(g[g.size - 2], g[g.size - 1]) to it },
                        )
                    }
                    .distinctBy { it.second }

    /**
     * Fetch MOTIS boards for [stops] concurrently and flatten them into an
     * [Overlay]. Each stop is named by its baked MOTIS id, so this is one
     * `/stoptimes` call per stop with no proximity round-trip.
     *
     * Returns [Overlay.EMPTY] when the device is offline — without that gate every
     * offline plan would pay a full HTTP timeout per stop before `runCatching`
     * swallowed it.
     */
    private suspend fun realtimeOverlay(
            context: Context,
            stops: List<Pair<GeoPoint, String>>,
            clock: TransitClock,
    ): Overlay {
        if (stops.isEmpty() || !ConnectivityMonitor.isOnline(context)) return Overlay.EMPTY
        val boards = coroutineScope {
            stops.map { (p, motisId) ->
                async(Dispatchers.IO) {
                    p to runCatching {
                        TransitousDataSource.departures(motisId)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        val coords = mutableListOf<Double>()
        val routes = mutableListOf<String>()
        val times = mutableListOf<Int>()
        for ((pos, deps) in boards) {
            for (d in deps) {
                if (d.line.isBlank()) continue
                val delaySecs = ((d.realtimeMillis - d.scheduledMillis) / 1000L).toInt()
                if (delaySecs == 0 && !d.cancelled) continue
                coords.add(pos.latitude)
                coords.add(pos.longitude)
                routes.add(d.line)
                times.add(((d.scheduledMillis - clock.midnightMillis) / 1000L).toInt())
                times.add(delaySecs)
                times.add(if (d.cancelled) 1 else 0)
            }
        }
        if (routes.isEmpty()) return Overlay.EMPTY
        return Overlay(coords.toDoubleArray(), routes.toTypedArray(), times.toIntArray())
    }

    /**
     * The stop nearest `(lat, lon)` from the baked `*.transit` packs, as a
     * [TransitStop] whose id is the MOTIS/Transitous id so the realtime board can
     * query it. Null when no pack covers the point.
     *
     * Reached from a tapped station POI, which carries no stop id of its own. This
     * is a purely local lookup — `/api/v1/map/stops`, which used to answer
     * "what stop is here", is gone.
     */
    suspend fun nearestStop(
            context: Context,
            lat: Double,
            lon: Double,
    ): TransitStop? = withContext(Dispatchers.Default) {
        val base = OfflineRouter.transitBase(context) ?: return@withContext null
        val feeds = transitFeeds(base)
        for (feed in feeds) {
            val id = runCatching {
                OfflineRouter.nearestStopMotisIdNative(base, feed, lat, lon)
            }.getOrNull()?.ifBlank { null } ?: continue
            // The board resolves its own stop from the coordinate, so the name is
            // only a label until it loads; the MOTIS id is the part that matters.
            return@withContext TransitStop(id = id, name = id, lat = lat, lon = lon)
        }
        null
    }

    /**
     * Simulated moving transit vehicles within the visible bbox (WS-F). Positions
     * are interpolated on-device from each downloaded `*.transit` pack's schedule +
     * shape by the native `activeVehiclesNative`; there is no live GPS feed. The
     * result is meant to be recomputed at ~1 Hz by a ticker (the renderer's frame
     * clock smooths motion between recomputes) and pushed to the map overlay.
     *
     * Schedule-only for now: the realtime [Overlay] would need a board fetch per
     * visible stop, which the 1 Hz ticker (task #8) assembles via [realtimeOverlay]
     * and folds in before pushing — the native call already accepts it.
     */
    suspend fun activeVehicles(
            context: Context,
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double,
    ): List<OfflineRouter.Vehicle> = withContext(Dispatchers.Default) {
        val base = OfflineRouter.transitBase(context) ?: return@withContext emptyList()
        val feeds = transitFeeds(base)
        if (feeds.isEmpty()) return@withContext emptyList()

        val out = mutableListOf<OfflineRouter.Vehicle>()
        for (feed in feeds) {
            // The pack is world-merged, so query time must be in the feed's zone;
            // resolve it at the bbox centre.
            val clock = transitClock(
                    runCatching {
                        OfflineRouter.getFeedTimezoneNative(
                                base, feed, (minLat + maxLat) / 2, (minLon + maxLon) / 2
                        )
                    }.getOrNull()
            )
            val overlay = Overlay.EMPTY
            val raw = try {
                OfflineRouter.activeVehiclesNative(
                        base, feed,
                        clock.depSecs, clock.weekday, clock.date,
                        clock.prevWeekday, clock.prevDate,
                        overlay.coords, overlay.routes, overlay.times,
                        minLat, minLon, maxLat, maxLon
                )
            } catch (_: Exception) {
                null
            } ?: continue
            for (v in raw) {
                out.add(
                        OfflineRouter.Vehicle(
                                lon = v.lon,
                                lat = v.lat,
                                bearing = v.bearing.toFloat(),
                                colour = v.colour,
                                mode = gtfsRouteTypeToMode(v.mode),
                                id = v.id,
                        )
                )
            }
        }
        out
    }

    /**
     * Departure board from the baked `*.transit` index for the stop nearest
     * `(lat,lon)`. Scheduled times come from the pack; when the device is online
     * the MOTIS board for that stop is folded in as a realtime overlay, so
     * `delayMinutes`/`realTime`/`cancelled` are live. Returns an empty list when
     * no pack covers the point.
     */
    /**
     * The schedule board for the stop nearest [lat],[lon].
     *
     * [anchor] is the instant the board starts from, defaulting to now. Passing an earlier instant
     * is how the past half of the window is fetched: the pack answers "what leaves from here,
     * onwards", so yesterday's trains are simply the same query asked from yesterday. The
     * per-feed timezone still applies — the anchor is absolute, and each feed resolves it in its
     * own zone, which matters because the pack is world-merged.
     *
     * [until] walks the board forward until it reaches that instant, and is what makes the past
     * half actually recent. "Onwards" is the only direction the pack answers in, so one query for
     * [max] events anchored a day ago returns the events *following the anchor* — at a busy
     * station those are used up within a few hours and the board stops there, leaving a hole
     * between them and now. Paging to [until] closes it, and [max] then keeps the events nearest
     * [until] rather than the ones nearest [anchor].
     */
    suspend fun getStopDeparturesOffline(
            context: Context,
            lat: Double,
            lon: Double,
            max: Int = 30,
            anchor: java.time.Instant? = null,
            until: java.time.Instant? = null,
    ): List<Departure> = withContext(Dispatchers.Default) {
        val base = OfflineRouter.transitBase(context) ?: return@withContext emptyList()
        val feeds = transitFeeds(base)
        if (feeds.isEmpty()) return@withContext emptyList()

        val all = mutableListOf<Departure>()
        for (feed in feeds) {
            // Neither the feed's zone nor its nearest stop depends on when we ask, so they are
            // resolved once even when the board below is walked forward over several pages.
            val zoneId = runCatching { OfflineRouter.getFeedTimezoneNative(base, feed, lat, lon) }.getOrNull()
            // The board is fetched before we know which stop it is for, so name the
            // stop up front from the pack. No pack id (pre-v5, or a feed whose
            // Transitous source name the build did not know) means no realtime, and
            // the board stays schedule-only.
            //
            // Only the nearest stop's board is fetched, even though the offline
            // board aggregates co-located platforms within 150 m. That is
            // deliberate: the Rust overlay matches a delay to a stop within 60 m,
            // tight enough that adjacent platforms don't collide, so a neighbouring
            // platform's realtime could not be attributed anyway without carrying
            // per-stop coordinates back out of the board.
            val motisId = runCatching {
                OfflineRouter.nearestStopMotisIdNative(base, feed, lat, lon)
            }.getOrNull()?.ifBlank { null }

            var from = anchor ?: java.time.Instant.now()
            var page = 0
            while (page < MAX_DEPARTURE_PAGES) {
                page++
                val clock = transitClock(zoneId, now = { zone -> from.atZone(zone) })
                // Rebuilt per page because the overlay's times are relative to the clock's
                // midnight, and a walk of a day either side crosses one. The MOTIS board it
                // reads is briefly cached, so only the first page pays for the fetch.
                val overlay = if (motisId == null) {
                    Overlay.EMPTY
                } else {
                    realtimeOverlay(context, listOf(GeoPoint(lon, lat) to motisId), clock)
                }
                val raw = try {
                    OfflineRouter.getStopDeparturesNative(
                            base, feed, lat, lon,
                            clock.depSecs, clock.weekday, clock.date,
                            clock.prevWeekday, clock.prevDate,
                            overlay.coords, overlay.routes, overlay.times, max
                    )
                } catch (_: Exception) {
                    null
                } ?: break
                if (raw.isEmpty()) break
                for (d in raw) {
                    val scheduled = clock.midnightMillis + d.depSecs.toLong() * 1000L
                    all.add(
                            Departure(
                                    line = d.routeName,
                                    headsign = d.headsign,
                                    scheduledMillis = scheduled,
                                    realtimeMillis = scheduled + d.delaySecs * 1000L,
                                    delayMinutes = d.delaySecs / 60,
                                    realTime = d.realTime,
                                    platform = null,
                                    mode = gtfsRouteTypeToMode(d.routeType),
                                    routeColor = if (d.routeColor == 0) null
                                                 else String.format("%06X", d.routeColor and 0xFFFFFF),
                                    cancelled = d.cancelled,
                            )
                    )
                }
                if (until == null) break
                // A short page means the feed has nothing further, not that we arrived.
                if (raw.size < max) break
                val last = clock.midnightMillis + raw.last().depSecs.toLong() * 1000L
                if (last >= until.toEpochMilli()) break
                // Past the last event returned, or the next page repeats it forever.
                from = java.time.Instant.ofEpochMilli(last + 1000L)
            }
        }
        all.sortBy { it.realtimeMillis }
        // Walking to [until] means the interesting end is the far one: the events just before
        // it, not the ones a day earlier that happen to have been read first.
        if (until != null) all.takeLast(max) else all.take(max)
    }

    /** Map a GTFS `route_type` (base + extended ranges) to a coarse mode label. */
    private fun gtfsRouteTypeToMode(t: Int): String = when (t) {
        0, 5, 900 -> "TRAM"
        1, in 400..499 -> "SUBWAY"
        2, in 100..199 -> "RAIL"
        3, in 200..299, in 700..799, 800 -> "BUS"
        4, 1000, 1200 -> "FERRY"
        6, 1300 -> "AERIAL"
        7, 1400 -> "FUNICULAR"
        11 -> "TROLLEYBUS"
        12 -> "MONORAIL"
        else -> "TRANSIT"
    }
}
