package com.vayunmathur.findfamily.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Pure due/containment policy for no-show alerts (issue 702).
 *
 * Unit-testable by construction: every input is a primitive, [Instant], or
 * [Duration]. No Android, no Compose, no database types — the scheduler maps
 * rows to these parameters at its own boundary.
 *
 * Containment mirrors the live geofence path
 * (`data/Coord.kt:havershine` + `util/LocationTrackingInbound.kt`):
 * entering needs the whole error circle inside the radius, leaving needs it
 * wholly outside a hysteresis margin, and fixes worse than
 * [NO_SHOW_MAX_ACCURACY_METERS] cannot move the answer. The distance itself is
 * a pure haversine here rather than the platform geodesic, so values agree to
 * within the sub-meter noise the strict inequalities already tolerate.
 */
object NoShowPolicy {

    /** Mirror of the inbound geofence accuracy gate: worse fixes decide nothing. */
    const val NO_SHOW_MAX_ACCURACY_METERS = 100.0

    /** Mirror of the inbound exit hysteresis: leaving needs a clear margin. */
    const val NO_SHOW_EXIT_HYSTERESIS = 1.2

    /** Mean Earth radius in meters, matching the platform geodesic scale. */
    const val EARTH_RADIUS_METERS = 6_371_000.0

    /** The alert deadline: the moment [grace] after [expectedAt] it becomes due. */
    fun deadline(expectedAt: Instant, grace: Duration): Instant = expectedAt + grace

    /** True once [now] has reached [expectedAt] plus [grace] (inclusive). */
    fun isDue(expectedAt: Instant, grace: Duration, now: Instant): Boolean =
        now >= deadline(expectedAt, grace)

    /** True when a fix is precise enough to decide geofence membership. */
    fun isAccurateEnough(accuracyMeters: Double): Boolean =
        accuracyMeters.isFinite() && accuracyMeters >= 0.0 &&
            accuracyMeters <= NO_SHOW_MAX_ACCURACY_METERS

    /**
     * Pure haversine distance in meters. Same contract as the data-layer
     * geodesic (meters between WGS84 points); the spherical approximation
     * differs by far less than a typical fix accuracy.
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val halfDLat = (lat2 - lat1) * Math.PI / 360.0
        val halfDLon = (lon2 - lon1) * Math.PI / 360.0
        val lat1Rad = lat1 * Math.PI / 180.0
        val lat2Rad = lat2 * Math.PI / 180.0
        val a = sin(halfDLat) * sin(halfDLat) +
            cos(lat1Rad) * cos(lat2Rad) * sin(halfDLon) * sin(halfDLon)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * Enter semantics: the whole error circle must fit inside [rangeMeters].
     * Strict `<`, mirroring inbound — a circle exactly touching the boundary
     * stays where it was.
     */
    fun isEnterInside(distanceMeters: Double, accuracyMeters: Double, rangeMeters: Double): Boolean =
        distanceMeters + accuracyMeters < rangeMeters

    /**
     * Exit semantics: still inside while any part of the error circle (plus
     * the hysteresis margin) overlaps the radius. Strict `<`, mirroring
     * inbound.
     */
    fun isStillInside(distanceMeters: Double, accuracyMeters: Double, rangeMeters: Double): Boolean =
        distanceMeters - accuracyMeters < rangeMeters * NO_SHOW_EXIT_HYSTERESIS

    /**
     * A fix only proves arrival when it was *measured* at or after the
     * expectation. Shutdown and low-battery reports deliberately republish a
     * stale last-known position, so ranking them by send time would let a
     * parting shot from long before [expectedAt] suppress an alert it says
     * nothing about.
     */
    fun isFixFreshForArrival(fixTimestamp: Instant, expectedAt: Instant): Boolean =
        fixTimestamp >= expectedAt

    /**
     * Arrival at a waypoint: a fresh, accurate fix whose whole error circle
     * is inside the radius. Any failed gate reads as "not arrived" — a fix
     * that cannot say which side of the boundary it is on must not suppress
     * the alert.
     */
    fun hasArrivedAtWaypoint(
        fixLat: Double,
        fixLon: Double,
        fixAccuracyMeters: Double,
        fixTimestamp: Instant,
        expectedAt: Instant,
        waypointLat: Double,
        waypointLon: Double,
        waypointRangeMeters: Double,
    ): Boolean {
        if (!isFixFreshForArrival(fixTimestamp, expectedAt)) return false
        if (!isAccurateEnough(fixAccuracyMeters)) return false
        val distance = haversineMeters(fixLat, fixLon, waypointLat, waypointLon)
        return isEnterInside(distance, fixAccuracyMeters, waypointRangeMeters)
    }

    /**
     * Arrival when the alert watches no waypoint: any fresh-enough location
     * update counts, regardless of position or accuracy.
     */
    fun hasAnyFreshFix(fixTimestamp: Instant, expectedAt: Instant): Boolean =
        isFixFreshForArrival(fixTimestamp, expectedAt)

    /**
     * Fire-once guard for the scheduler: fires only for an unfired alert whose
     * deadline has passed and whose arrival was not observed. Persistence
     * (claim-then-notify, delete vs re-arm) stays with the scheduler; this is
     * the in-memory half that keeps a second pass from double-firing.
     */
    fun shouldFire(
        fired: Boolean,
        expectedAt: Instant,
        grace: Duration,
        now: Instant,
        arrived: Boolean,
    ): Boolean = !fired && !arrived && isDue(expectedAt, grace, now)
}
