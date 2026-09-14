package com.vayunmathur.maps.util

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.transit.Departure
import com.vayunmathur.maps.data.transit.TransitStop
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.library.map.GeoPoint

object OfflineRouter {
    private var serverPort = 0
    val trafficTileUrl: String get() = if (serverPort > 0) "http://localhost:$serverPort/traffic/{z}/{x}/{y}" else ""

    init {
        System.loadLibrary("offlinerouter")
        startLocalTileServer()
    }

    private fun startLocalTileServer() {
        Thread {
            try {
                // Bind to loopback ONLY. The previous `ServerSocket(0)` defaulted
                // to 0.0.0.0 which let any app on the device (or anything on the
                // local network) hit /traffic/{z}/{x}/{y}.
                val serverSocket = java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress())
                serverPort = serverSocket.localPort
                Log.d("OFFLINE_ROUTER", "Tile server started on port $serverPort (loopback only)")
                // Hand each client to a small pool so a slow tile doesn't block
                // MapLibre's concurrent tile requests behind the global mutex.
                val pool = Executors.newFixedThreadPool(4)
                while (!serverSocket.isClosed) {
                    val client = serverSocket.accept()
                    // Prevent a half-open / hung client from holding a worker forever.
                    runCatching { client.soTimeout = 5_000 }
                    pool.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.e("OFFLINE_ROUTER", "Tile server error", e)
            }
        }.start()
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val firstLine = reader.readLine() ?: return
            
            // Expected: GET /traffic/{z}/{x}/{y} HTTP/1.1
            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0] == "GET") {
                val pathParts = parts[1].removePrefix("/traffic/").split("/")
                if (pathParts.size == 3) {
                    val z = pathParts[0].toIntOrNull() ?: 0
                    val x = pathParts[1].toIntOrNull() ?: 0
                    val y = pathParts[2].substringBefore("?").toIntOrNull() ?: 0
                    
                    val bytes = getTrafficTileNative(z, x, y)
                    val output = client.getOutputStream()
                    if (bytes != null) {
                        output.write(("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/vnd.mapbox-vector-tile\r\n" +
                                "Content-Encoding: gzip\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Access-Control-Allow-Origin: *\r\n\r\n").toByteArray())
                        output.write(bytes)
                    } else {
                        output.write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.e("OFFLINE_ROUTER", "Error handling client", e)
        } finally {
            client.close()
        }
    }

    private external fun init(basePath: String): Boolean
    private external fun findRouteNative(
            sLat: Double,
            sLon: Double,
            eLat: Double,
            eLon: Double,
            mode: Int
    ): Array<RawStep>?
    /**
     * Offline transit journey planning (P11b): RAPTOR over the per-region
     * `<feed>.transit` index at `<basePath>/<feed>.transit`. `depSecs` is seconds
     * since midnight, `weekday` is 0=Mon..6=Sun, `date` is yyyymmdd — all in the
     * **feed's** timezone (see [getFeedTimezoneNative]), since the index is
     * world-merged. `prevWeekday`/`prevDate` describe the preceding service day,
     * whose GTFS `>24:00:00` trips run into the query day.
     *
     * `overlay*` carry MOTIS realtime so the planner skips cancelled trips and
     * uses live times; pass empty arrays for a schedule-only plan.
     * `overlayCoords` is interleaved `[lat, lon, ...]` and `overlayTimes` is
     * interleaved `[schedSecs, delaySecs, cancelled, ...]`, both parallel to
     * `overlayRoutes`.
     *
     * Returns walk + wait + ride legs as [RawStep]s, or null when the feed is
     * missing, doesn't cover the endpoints, or no journey exists.
     *
     * The [JvmName] is load-bearing: `internal` members mangle to
     * `name$maps` in bytecode, but JNI resolves by the declared name -
     * without it every call dies with UnsatisfiedLinkError (seen on-device
     * 2026-09-14: the probe linked only after this was added, and the five
     * older transit natives were silently broken the same way).
     */
    @JvmName("findTransitRouteNative")
    internal external fun findTransitRouteNative(
            basePath: String,
            feed: String,
            sLat: Double,
            sLon: Double,
            eLat: Double,
            eLon: Double,
            depSecs: Int,
            weekday: Int,
            date: Int,
            prevWeekday: Int,
            prevDate: Int,
            overlayCoords: DoubleArray,
            overlayRoutes: Array<String>,
            overlayTimes: IntArray
    ): Array<RawStep>?
    /**
     * Offline scheduled departure board: upcoming departures from the stop
     * nearest `(lat,lon)` in `<basePath>/<feed>.transit`. Time and overlay
     * arguments are as in [findTransitRouteNative].
     * Returns null when the feed is missing or doesn't cover the point.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("getStopDeparturesNative")
    internal external fun getStopDeparturesNative(
            basePath: String,
            feed: String,
            lat: Double,
            lon: Double,
            depSecs: Int,
            weekday: Int,
            date: Int,
            prevWeekday: Int,
            prevDate: Int,
            overlayCoords: DoubleArray,
            overlayRoutes: Array<String>,
            overlayTimes: IntArray,
            max: Int
    ): Array<RawDeparture>?
    /**
     * Whether `<basePath>/basemap.mamaps` carries a transit section (kind 13).
     *
     * The archive already ships the world transit pack (packed by
     * `mamaps_pack --transit` and read archive-first by the native
     * `transit_index`), but the Kotlin discovery gate used to list only the
     * per-region sidecar packs under `<basePath>` - so on an archive-only
     * device every transit entry point short-circuited before any JNI call.
     * A pure presence probe (section directory only, no pack parse), cheap
     * enough to call on every entry.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("hasTransitArchiveNative")
    internal external fun hasTransitArchiveNative(basePath: String): Boolean

    /**
     * IANA timezone of the feed covering `(lat,lon)` in the given pack, or null
     * when the pack is stale/absent, doesn't cover the point, or its GTFS had no
     * `agency.txt`. Callers resolve this before deriving any query times.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("getFeedTimezoneNative")
    internal external fun getFeedTimezoneNative(
            basePath: String,
            feed: String,
            lat: Double,
            lon: Double
    ): String?

    /**
     * MOTIS/Transitous id of the stop nearest `(lat, lon)` in the baked v5 pack,
     * or null when the pack is absent, predates v5, doesn't cover the point, or
     * its feed's Transitous source name was unknown at build time.
     *
     * A purely local lookup. It exists because the departure board fetches its
     * realtime overlay before it knows which stop the board is for, so it has to
     * name the stop up front — and since `/api/v1/map/stops` is gone, there is no
     * longer any network way to turn a coordinate into a MOTIS id.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("nearestStopMotisIdNative")
    internal external fun nearestStopMotisIdNative(
            basePath: String,
            feed: String,
            lat: Double,
            lon: Double
    ): String?
    /**
     * Drawable rail lines serving the bbox, read from the timetable pack's
     * GTFS-shape sections (no tile rebuild needed). `coords` is flat
     * `[lon0, lat0, ...]`; `routeColor` is 0xRRGGBB (`0` when absent).
     * Empty array (not null) when the pack is absent; null on JNI failure.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("getRailLinesNative")
    internal external fun getRailLinesNative(
            basePath: String,
            feed: String,
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double
    ): Array<RawRailLine>?
    /**
     * A trip's stop-by-stop itinerary for the vehicle-details sheet, decoded
     * from the stable per-trip id `activeVehiclesNative` returns. Times are
     * feed-local-midnight seconds in the query-day frame, like board times.
     * Null when the id does not resolve or the trip does not run today; a
     * cancelled trip still returns with `cancelled` set.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("getTripItineraryNative")
    internal external fun getTripItineraryNative(
            basePath: String,
            feed: String,
            vehicleId: Long,
            weekday: Int,
            date: Int,
            prevWeekday: Int,
            prevDate: Int,
            overlayCoords: DoubleArray,
            overlayRoutes: Array<String>,
            overlayTimes: IntArray
    ): RawTripItinerary?
    /**
     * Simulated in-service transit vehicles for the visible bbox (WS-F): every
     * trip in `<basePath>/<feed>.transit` running at `nowSecs` (seconds since
     * feed-local midnight), interpolated to a live `lon,lat,bearing` along its
     * shape. Positions are computed on-device from the pack schedule + shape +
     * the realtime overlay; there is no live GPS feed.
     *
     * Time and `overlay*` arguments are as in [findTransitRouteNative], so a
     * `>24:00:00` overnight trip is placed and a delayed/cancelled trip is drawn
     * live/suppressed. Returns [RawVehicle]s (possibly empty), or null when the
     * feed is missing/malformed.
     *
     * [JvmName] keeps the JVM name JNI-visible; see [findTransitRouteNative].
     */
    @JvmName("activeVehiclesNative")
    internal external fun activeVehiclesNative(
            basePath: String,
            feed: String,
            nowSecs: Int,
            weekday: Int,
            date: Int,
            prevWeekday: Int,
            prevDate: Int,
            overlayCoords: DoubleArray,
            overlayRoutes: Array<String>,
            overlayTimes: IntArray,
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double
    ): Array<RawVehicle>?
    private external fun updateTrafficNative(
            edgeIds: LongArray,
            speeds: ByteArray,
            packedSquare: Int
    )
    external fun getTrafficSegmentsNative(): DoubleArray
    private external fun notifyTrafficFetchFinishedNative(packedSquare: Int)
    external fun getTrafficTileNative(z: Int, x: Int, y: Int): ByteArray?

    /** Display-traffic version counter — see [OfflineRouterTraffic]. */
    val trafficVersion get() = OfflineRouterTraffic.trafficVersion

    /** Merged per-component display table — see [OfflineRouterTraffic]. */
    internal val trafficComponents get() = OfflineRouterTraffic.trafficComponents

    /** Bump the display-traffic version — see [OfflineRouterTraffic]. */
    fun notifyTrafficUpdated() = OfflineRouterTraffic.notifyTrafficUpdated()

    private var cacheDirPath: String? = null

    external fun ensureTrafficLoadedNative(lat: Double, lon: Double, forceAsync: Boolean)
    private fun fetchTrafficData(
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double,
            packedSquare: Int,
            forceAsync: Boolean
    ) {
        Log.d(
                "TRAFFIC_DATA",
                "fetchTrafficData START: bbox ($minLat,$minLon)-($maxLat,$maxLon) packed=$packedSquare forceAsync=$forceAsync"
        )
        
        val block: suspend () -> Unit = block@{
            try {
                val (status, bytes) =
                        NetworkClient.performRequestBytes(
                                url =
                                        "https://api.vayunmathur.com/maps/traffic?min_lat=$minLat&min_lon=$minLon&max_lat=$maxLat&max_lon=$maxLon"
                        )
                Log.d(
                        "TRAFFIC_DATA",
                        "fetchTrafficData NETWORK DONE: status=$status, size=${bytes.size}"
                )
                // Two-level response (little-endian):
                //   u32 n_big, u32 n_component
                //   n_big       x (u64 big_edge_id,  u8 kph)         -- routing, unchanged
                //   n_component x (u64 component_id,  u8 ratio_pct)  -- display
                // ratio_pct = round(speedRatio*100); 0 = no data. Records are interleaved
                // (id then speed), not struct-of-arrays. The big level still feeds
                // updateTrafficNative exactly as before; the component level is kept in
                // Kotlin and pushed to the renderer as an id->colour table.
                if (status == 200 && bytes.size >= 8) {
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val nBig = buffer.int.toLong() and 0xFFFF_FFFFL
                    val nComponent = buffer.int.toLong() and 0xFFFF_FFFFL
                    val expected = 8L + 9L * (nBig + nComponent)
                    if (bytes.size.toLong() != expected) {
                        Log.w(
                                "TRAFFIC_DATA",
                                "fetchTrafficData SIZE MISMATCH: got ${bytes.size}, expected $expected (n_big=$nBig n_component=$nComponent)"
                        )
                        notifyTrafficFetchFinishedNative(packedSquare)
                        return@block
                    }
                    val nBigI = nBig.toInt()
                    val nComponentI = nComponent.toInt()

                    // Big level: split the interleaved (id, kph) records into the parallel
                    // arrays updateTrafficNative expects.
                    val edgeIds = LongArray(nBigI)
                    val speeds = ByteArray(nBigI)
                    for (i in 0 until nBigI) {
                        edgeIds[i] = buffer.long
                        speeds[i] = buffer.get()
                    }

                    // Component level: kept for display.
                    val compIds = LongArray(nComponentI)
                    val compRatios = ByteArray(nComponentI)
                    for (i in 0 until nComponentI) {
                        compIds[i] = buffer.long
                        compRatios[i] = buffer.get()
                    }

                    Log.d(
                            "TRAFFIC_DATA",
                            "fetchTrafficData PROCESSING: $nBigI big edges, $nComponentI components"
                    )
                    updateTrafficNative(edgeIds, speeds, packedSquare)
                    OfflineRouterTraffic.storeSquare(
                            packedSquare,
                            OfflineRouterTraffic.TrafficComponents(compIds, compRatios),
                    )
                    notifyTrafficUpdated()
                } else {
                    Log.w("TRAFFIC_DATA", "fetchTrafficData NO DATA: status=$status")
                    notifyTrafficFetchFinishedNative(packedSquare)
                }
            } catch (e: Exception) {
                Log.e("TRAFFIC_DATA", "fetchTrafficData ERROR", e)
                notifyTrafficFetchFinishedNative(packedSquare)
            }
            Log.d("TRAFFIC_DATA", "fetchTrafficData END: packed=$packedSquare")
        }

        if (forceAsync) {
            OfflineRouterTraffic.launch(block)
        } else {
            // Previously called runBlocking(Dispatchers.IO) which blocked the
            // native caller's thread (often a Dispatchers.Default worker via
            // getRoute) for an entire 60s HTTP round-trip. That starved the
            // Default pool. Always async; the native side reacts to
            // notifyTrafficUpdated / notifyTrafficFetchFinishedNative when the
            // HTTP response is processed.
            OfflineRouterTraffic.launch(block)
        }
    }

    class RawStep
    @Keep
    constructor(
            val maneuverId: Int,
            val roadName: String,
            val distanceMm: Long,
            val duration10ms: Long,
            val geometry: DoubleArray,
            val speedRatio: Double,
            val isTransit: Boolean,
            val gtfsFeed: String?,
            val stopCode: String?,
            val endStopCode: String?,
            val stopCount: Int,
            /** Packed turn lanes: one int per lane, `dirMask * 2 + valid`, where
             * `dirMask` is a bitmask of Maneuver ordinals the lane offers. */
            val lanePacked: IntArray,
            // Transit-only tail. The JNI ctor descriptor is shared with the
            // driving path, which passes null/0 — keep these LAST so adding to
            // them never renumbers the arguments above.
            /** GTFS `trip_headsign` of the ridden trip. */
            val headsign: String?,
            /** GTFS `route_color` packed as 0xRRGGBB, or 0 when absent. */
            val routeColor: Int,
            /** Departure, seconds since feed-local midnight (0 when unknown). */
            val depSecs: Int,
            /** Arrival, seconds since feed-local midnight (0 when unknown). */
            val arrSecs: Int,
            /**
             * MOTIS/Transitous id of the ride's board stop, baked into the v5
             * pack. Null on a walk/wait leg, on a pre-v5 pack, or when the feed's
             * Transitous source name was unknown at build time — in which case the
             * realtime overlay simply has nothing to ask about for this leg.
             */
            val boardStopId: String?,
            /** MOTIS/Transitous id of the ride's alight stop. See [boardStopId]. */
            val alightStopId: String?,
            /**
             * Per-coordinate ground elevation in metres, parallel to [geometry]
             * (so `elevations.size == geometry.size / 2`). Baked from the DEM at
             * graph-build time (WS-G) and interpolated along each edge. Empty on a
             * transit leg or when the graph carries no elevation data.
             */
            val elevations: DoubleArray,
            /** Whole-route cumulative ascent in metres (repeated on every step). */
            val ascentM: Double,
            /** Whole-route cumulative descent in metres (repeated on every step). */
            val descentM: Double,
    )

    /** One offline scheduled departure from the baked `.transit` index. */
    class RawDeparture
    @Keep
    constructor(
            val routeName: String,
            val headsign: String,
            val feed: String,
            val stopCode: String,
            /** GTFS route colour as packed 0xRRGGBB, or 0 when absent. */
            val routeColor: Int,
            /** GTFS route_type. */
            val routeType: Int,
            /** Scheduled departure, seconds since feed-local midnight. */
            val depSecs: Int,
            /** Realtime shift in seconds; 0 without live data. */
            val delaySecs: Int,
            val cancelled: Boolean,
            /** Whether the realtime overlay covered this departure. */
            val realTime: Boolean,
            // The trip behind this departure, packed like a vehicle id
            // (`(route_idx << 32) | (trip_index << 1) | prev_day`) so a tap
            // opens the same trip sheet. Kept LAST so the shared descriptor
            // never renumbers the arguments above.
            val tripId: Long,
    )

    /**
     * One simulated in-service vehicle as it crosses the JNI boundary. Flat like
     * [RawStep]/[RawDeparture]; [Vehicle] is the typed form callers consume.
     */
    class RawVehicle
    @Keep
    constructor(
            val lon: Double,
            val lat: Double,
            /** Heading in degrees, 0 = north, clockwise, along the direction of travel. */
            val bearing: Double,
            /** GTFS `route_color` packed as 0xRRGGBB, or 0 when absent. */
            val colour: Int,
            /** GTFS `route_type`. */
            val mode: Int,
            /** Stable per-trip id, so a sprite animates between recomputes. */
            val id: Long,
    )

    /**
     * A simulated moving transit vehicle for the map overlay (WS-F). [bearing] is
     * degrees (0 = north, clockwise); [colour] is 0xRRGGBB (0 when absent);
     * [mode] is a coarse label (BUS/TRAM/RAIL/…) for icon selection; [id] is
     * stable across the ~1 Hz recomputes for the same trip.
     */
    data class Vehicle(
            val lon: Double,
            val lat: Double,
            val bearing: Float,
            val colour: Int,
            val mode: String,
            val id: Long,
    )

    /**
     * One drawable rail line as it crosses the JNI boundary: the route's full
     * GTFS-shape polyline with its agency colour. Flat like [RawVehicle].
     */
    class RawRailLine
    @Keep
    constructor(
            val name: String,
            /** GTFS `route_color` packed as 0xRRGGBB, or 0 when absent. */
            val color: Int,
            /** GTFS `route_type`. */
            val routeType: Int,
            val feed: String,
            /** Flat `[lon0, lat0, ...]`, like a [RawStep] geometry. */
            val coords: DoubleArray,
    )

    /**
     * One itinerary stop as it crosses the JNI boundary. Times are
     * feed-local-midnight seconds in the query-day frame, converted by
     * callers exactly like board times.
     */
    class RawTripStop
    @Keep
    constructor(
            val name: String,
            val lat: Double,
            val lon: Double,
            val arrSecs: Int,
            val depSecs: Int,
            /** Baked MOTIS id when the pack carries one, else empty. */
            val motisId: String,
            /** Set on every stop when the trip is cancelled. */
            val cancelled: Boolean,
    )

    /** A trip's full run: header plus one [RawTripStop] per stop, in order. */
    class RawTripItinerary
    @Keep
    constructor(
            val routeName: String,
            val headsign: String,
            /** GTFS `route_color` packed as 0xRRGGBB, or 0 when absent. */
            val color: Int,
            /** GTFS `route_type`. */
            val routeType: Int,
            val feed: String,
            val cancelled: Boolean,
            val stops: Array<RawTripStop>,
    )

    private var isInitialized = false
    /** Base dir (external files) holding the downloaded `basemap.mamaps` archive and legacy `*.transit` packs. */
    private var basePath: String? = null

    /**
     * Initialized base path for the transit helpers in [OfflineRouterTransit].
     * Null when [initialize] has not run yet or found no external files dir.
     */
    internal fun transitBase(context: Context): String? {
        if (!isInitialized) initialize(context)
        return basePath
    }

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        val path = context.getExternalFilesDir(null)?.absolutePath ?: return
        basePath = path
        Log.d("OfflineRouter", "Initializing with path: $path")

        isInitialized = init(path)
        Log.d("OfflineRouter", "Initialization result: $isInitialized")
        cacheDirPath = context.cacheDir.absolutePath
    }

    /**
     * Force a re-load of the routing graph from disk. Call after the single
     * global routing graph (P16) finishes downloading so the freshly downloaded
     * nodes.bin/edges.bin/… replace whatever was (or wasn't) loaded at startup.
     * Re-init is safe: the Rust side atomically swaps the graph behind its lock.
     */
    @Synchronized
    fun reload(context: Context) {
        isInitialized = false
        OfflineRouterTransit.onReload()
        OfflineRouterTraffic.onReload()
        initialize(context)
    }

    /**
     * Plan a route for any [mode]. TRANSIT goes to the on-device RAPTOR planner
     * and nowhere else — there is no online routing fallback, so a journey the
     * pack cannot plan yields no transit route. Every other mode goes to the
     * road graph via [getRouteMulti].
     *
     * This is the **only** correct entry point for a caller whose mode is not a
     * literal: the road graph carries no timetable, so TRANSIT must never reach
     * [getRouteMulti]. Routing every mode-agnostic caller through here is what
     * guarantees that.
     */
    suspend fun getRouteForMode(
            context: Context,
            route: SpecificFeature.Route,
            userPosition: GeoPoint,
            mode: RouteService.TravelMode,
    ): RouteService.Route? = withContext(Dispatchers.Default) {
        if (mode != RouteService.TravelMode.TRANSIT) {
            return@withContext getRouteMulti(context, route, userPosition, mode)
        }
        val positions = route.waypoints.map { it?.position ?: userPosition }
        if (positions.size < 2) return@withContext null
        val start = positions.first()
        val end = positions.last()
        getTransitRouteOffline(context, start, end)
    }

    /**
     * Offline-only multi-waypoint chaining. Replaces the old server-side
     * routing that handled intermediates remotely.
     * Positions = route.waypoints.map { it?.position ?: userPosition }.
     * Chains A->B, B->C ... using [getRoute] and concatenates polylines
     * (dedup join), steps, and sums distance/duration. Returns null if
     * any leg fails or if <2 positions.
     */
    suspend fun getRouteMulti(context: Context, route: SpecificFeature.Route, userPosition: GeoPoint, type: RouteService.TravelMode): RouteService.Route? = withContext(Dispatchers.Default) {
        val positions = route.waypoints.map { it?.position ?: userPosition }
        if (positions.size < 2) return@withContext null
        val legs = mutableListOf<RouteService.Route>()
        for (i in 0 until positions.size - 1) {
            val leg = try { getRoute(context, positions[i], positions[i+1], type) } catch (_: Exception) { return@withContext null }
            legs.add(leg)
        }
        if (legs.isEmpty()) return@withContext null
        if (legs.size == 1) return@withContext legs.first()
        val combinedPolyline = mutableListOf<GeoPoint>()
        val combinedSteps = mutableListOf<RouteService.Step>()
        val combinedElevation = mutableListOf<RouteService.ElevationPoint>()
        var totalDist = 0.0
        var totalSec = 0L
        var totalAscent = 0.0
        var totalDescent = 0.0
        for (leg in legs) {
            if (combinedPolyline.isEmpty()) combinedPolyline.addAll(leg.polyline)
            else {
                val first = leg.polyline.firstOrNull()
                if (first != null && combinedPolyline.lastOrNull() == first) combinedPolyline.addAll(leg.polyline.drop(1))
                else combinedPolyline.addAll(leg.polyline)
            }
            combinedSteps.addAll(leg.step)
            // Offset each leg's profile by the route distance before it so the chart is continuous.
            val distOffset = totalDist
            for (p in leg.elevationProfile) {
                combinedElevation.add(p.copy(distanceMeters = p.distanceMeters + distOffset))
            }
            totalDist += leg.distanceMeters
            totalSec += leg.duration.inWholeSeconds
            totalAscent += leg.ascentMeters
            totalDescent += leg.descentMeters
        }
        RouteService.Route(
            duration = totalSec.seconds,
            distanceMeters = totalDist,
            polyline = combinedPolyline,
            step = combinedSteps,
            elevationProfile = combinedElevation,
            ascentMeters = totalAscent,
            descentMeters = totalDescent,
        )
    }

    /**
     * Offline transit routing (P11d): plan a journey with the on-device RAPTOR
     * planner over the transit pack covering the endpoints (the world pack in
     * the archive when present, else a downloaded per-region `*.transit`
     * index). Returns null when no index is present/covering or no journey is
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
    ): RouteService.Route? = OfflineRouterTransit.getTransitRouteOffline(context, start, end)

    /**
     * Board and alight stops of every ride in a planned journey — see
     * [OfflineRouterTransit] for the shared implementation. Kept here as a
     * thin delegate so existing callers don't move.
     */
    suspend fun nearestStop(
            context: Context,
            lat: Double,
            lon: Double,
    ): TransitStop? = OfflineRouterTransit.nearestStop(context, lat, lon)

    /**
     * Simulated moving transit vehicles within the visible bbox (WS-F) — see
     * [OfflineRouterTransit] for the shared implementation.
     */
    suspend fun activeVehicles(
            context: Context,
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double,
    ): List<Vehicle> = OfflineRouterTransit.activeVehicles(context, minLat, minLon, maxLat, maxLon)

    /** Drawable rail lines — see [OfflineRouterTransit.railLines]. */
    suspend fun railLines(
            context: Context,
            minLat: Double,
            minLon: Double,
            maxLat: Double,
            maxLon: Double,
    ): List<com.vayunmathur.maps.data.transit.RailLine> =
            OfflineRouterTransit.railLines(context, minLat, minLon, maxLat, maxLon)

    /** Trip itinerary for the vehicle sheet — see [OfflineRouterTransit.tripItinerary]. */
    suspend fun tripItinerary(
            context: Context,
            vehicleId: Long,
            lat: Double,
            lon: Double,
    ): com.vayunmathur.maps.data.transit.TripItinerary? =
            OfflineRouterTransit.tripItinerary(context, vehicleId, lat, lon)

    /**
     * Departure board from the on-device transit index for the stop nearest
     * `(lat,lon)` — see [OfflineRouterTransit.getStopDeparturesOffline] for the
     * shared implementation. Kept here as a thin delegate so existing callers
     * don't move.
     *
     * [anchor] is the instant the board starts from, defaulting to now.
     * [until] walks the board forward until it reaches that instant.
     */
    suspend fun getStopDeparturesOffline(
            context: Context,
            lat: Double,
            lon: Double,
            max: Int = 30,
            anchor: java.time.Instant? = null,
            until: java.time.Instant? = null,
    ): List<Departure> =
            OfflineRouterTransit.getStopDeparturesOffline(context, lat, lon, max, anchor, until)

    suspend fun getRoute(
            context: Context,
            start: GeoPoint,
            end: GeoPoint,
            mode: RouteService.TravelMode
    ): RouteService.Route =
            withContext(Dispatchers.Default) {
                Log.d("OfflineRouter", "getRoute: mode=$mode, start=$start, end=$end")
                if (!isInitialized) {
                    initialize(context)
                }
                Log.d("OfflineRouter", "isInitialized=$isInitialized")

                val rawSteps =
                        findRouteNative(
                                start.latitude,
                                start.longitude,
                                end.latitude,
                                end.longitude,
                                mode.ordinal
                        )
                                ?: throw IllegalStateException("No route found")

                buildRoute(context, rawSteps, mode)
            }

    /**
     * Convert native [RawStep]s into a [RouteService.Route] — see
     * [OfflineRouterRouteBuilder.buildRoute] for the shared implementation.
     * Kept here as a thin delegate so existing callers don't move.
     */
    private fun buildRoute(
            context: Context,
            rawSteps: Array<RawStep>,
            mode: RouteService.TravelMode
    ): RouteService.Route = OfflineRouterRouteBuilder.buildRoute(context, rawSteps, mode)
}
