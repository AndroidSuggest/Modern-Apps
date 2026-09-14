package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.DrivingStatusReading
import com.vayunmathur.auto.protocol.gal.LocationReading
import com.vayunmathur.auto.protocol.gal.NightReading
import com.vayunmathur.auto.protocol.gal.SensorEvent
import com.vayunmathur.auto.protocol.gal.SensorType
import com.vayunmathur.auto.protocol.gal.SpeedReading
import kotlin.math.roundToLong

/**
 * One point of the active route polyline, in degrees.
 *
 * Own data class rather than `:library:map`'s `GeoPoint`: `auto/protocol`
 * stays Android-free and must not take a renderer dependency. The pre-host
 * nav banner reads road/maneuver/distance straight off the snapshot.
 */
data class MapRoutePoint(
    val longitude: Double,
    val latitude: Double,
)

/**
 * The phone-side navigation picture, in one snapshot.
 *
 * Produced on the Android side by `NavGuidanceMonitor` (GPS fix plus the
 * maps-module route provider) and consumed three ways: folded into
 * [SensorSnapshot] through [MapsGuidance.toSensorEvents] for the ch7 live
 * values, posted onto ch10 through [NavStatusUpdate] for the cluster, and
 * shown on the pre-host nav banner until the hosted `NavigationTemplate`
 * takes over (the hosted map surface itself comes from the maps app, not
 * from this snapshot).
 *
 * Plain data, no Android types, so the whole mapping is host-testable. A
 * snapshot with [guidanceActive] false and a null [route] is the idle map:
 * position and puck only, which is what the monitor produces before the
 * maps provider binds or when no route is active.
 */
data class NavSnapshot(
    /** Fix latitude x10^7, matching `LocationReading.latitude_e7`. */
    val latitudeE7: Long,
    /** Fix longitude x10^7, matching `LocationReading.longitude_e7`. */
    val longitudeE7: Long,
    /** Fix accuracy in metres, or null when the provider does not know it. */
    val accuracyM: Int? = null,
    /** Fix wall-clock millis, or null when the provider does not know it. */
    val fixTimestampMs: Long? = null,
    /** GPS ground speed in metres/second; 0 when unknown or stationary. */
    val speedMps: Double = 0.0,
    /** Whether the vehicle is parked; true until motion says otherwise. */
    val parked: Boolean = true,
    /** Phone night state, or null when unknown (keeps last-known downstream). */
    val isNight: Boolean? = null,
    /** Whether turn-by-turn guidance is actively navigating. */
    val guidanceActive: Boolean = false,
    /** Next road name, or null when idle or unnamed. */
    val nextRoad: String? = null,
    /** Metres to the next maneuver, or null when idle. */
    val nextTurnDistanceM: Int? = null,
    /** Maneuver token for the upcoming turn, or null when idle. */
    val maneuver: String? = null,
    /** Course over ground, degrees clockwise from north, or null with no heading. */
    val bearingDeg: Float? = null,
    /** Active route polyline, or null when idle or unknown. */
    val route: List<MapRoutePoint>? = null,
)

/**
 * Turn-guidance arguments for `NavStatusChannel.postStatus`, as data.
 *
 * Mirrors that signature one for one so the service can forward without
 * reshaping; [MapsGuidance.encodeNavStatus] renders the same update to the
 * wire for tests and head-unit stand-ins.
 */
data class NavStatusUpdate(
    val guidanceActive: Boolean,
    val nextRoad: String? = null,
    val nextTurnDistanceM: Int? = null,
    val maneuver: String? = null,
)

/**
 * Pure guidance producers (Phase 6, maps-dev): navigation snapshot shaping
 * plus the hooks sensors-dev consumes.
 *
 * Android-free like everything else in this module. The service-side wiring
 * (which owns the channels) is documented in `auto/docs/HANDOFF.md` §10;
 * this file only builds the payloads:
 *
 * - ch7 live values: [toSensorEvents] builds the `gal.SensorEvent` list a
 *   `SensorChannel` folds through `SensorSnapshot.withEvents`. Night is
 *   omitted (not posted empty) when [NavSnapshot.isNight] is null, so an
 *   unknown phone state keeps last-known instead of clobbering it -- the
 *   same payload-less rule `withEvents` already enforces.
 * - ch10 turn updates: [toNavStatus] selects the post arguments;
 *   [encodeNavStatus] renders them through [NavStatusCodec].
 */
object MapsGuidance {

    /**
     * Builds a snapshot from a decimal-degrees fix.
     *
     * Guidance and route fields stay at their idle defaults: the caller
     * overlays the maps provider's route state (or leaves idle) afterwards.
     */
    fun snapshotFromFix(
        latitude: Double,
        longitude: Double,
        accuracyM: Int? = null,
        fixTimestampMs: Long? = null,
        speedMps: Double = 0.0,
        parked: Boolean = true,
        isNight: Boolean? = null,
    ): NavSnapshot = NavSnapshot(
        latitudeE7 = (latitude * E7).roundToLong(),
        longitudeE7 = (longitude * E7).roundToLong(),
        accuracyM = accuracyM,
        fixTimestampMs = fixTimestampMs,
        speedMps = speedMps,
        parked = parked,
        isNight = isNight,
    )

    /**
     * Renders [snapshot] as the ch7 event list.
     *
     * Always LOCATION, SPEED and DRIVING_STATUS (stub parity: the stub set
     * subscribes to exactly these plus NIGHT_MODE), with NIGHT appended only
     * when the snapshot knows the phone state. Deterministic order so tests
     * and stand-ins can pin it.
     */
    fun toSensorEvents(snapshot: NavSnapshot): List<SensorEvent> {
        val events = ArrayList<SensorEvent>(4)
        val fix = LocationReading.newBuilder()
            .setLatitudeE7(snapshot.latitudeE7)
            .setLongitudeE7(snapshot.longitudeE7)
        if (snapshot.accuracyM != null) fix.accuracyM = snapshot.accuracyM
        if (snapshot.fixTimestampMs != null) fix.timestampMs = snapshot.fixTimestampMs
        events += SensorEvent.newBuilder()
            .setSensorType(SensorType.LOCATION)
            .setLocation(fix)
            .build()
        events += SensorEvent.newBuilder()
            .setSensorType(SensorType.SPEED)
            .setSpeed(SpeedReading.newBuilder().setSpeedMps(snapshot.speedMps))
            .build()
        events += SensorEvent.newBuilder()
            .setSensorType(SensorType.DRIVING_STATUS_DATA)
            .setDrivingStatus(DrivingStatusReading.newBuilder().setParked(snapshot.parked))
            .build()
        if (snapshot.isNight != null) {
            events += SensorEvent.newBuilder()
                .setSensorType(SensorType.NIGHT_MODE)
                .setNight(NightReading.newBuilder().setIsNight(snapshot.isNight))
                .build()
        }
        return events
    }

    /** Selects the ch10 post arguments for [snapshot]. */
    fun toNavStatus(snapshot: NavSnapshot): NavStatusUpdate = NavStatusUpdate(
        guidanceActive = snapshot.guidanceActive,
        nextRoad = snapshot.nextRoad,
        nextTurnDistanceM = snapshot.nextTurnDistanceM,
        maneuver = snapshot.maneuver,
    )

    /** Renders [update] to the ch10 wire type plus payload. */
    fun encodeNavStatus(update: NavStatusUpdate): Pair<Int, ByteArray> =
        NavStatusCodec.encodeStatus(
            update.guidanceActive,
            update.nextRoad,
            update.nextTurnDistanceM,
            update.maneuver,
        )

    private const val E7 = 10_000_000.0
}
