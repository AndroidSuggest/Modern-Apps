package com.vayunmathur.findfamily.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Pure-JVM tests for [NoShowPolicy]. No Android, no database — every case
 * feeds primitives and instants straight into the policy.
 */
class NoShowPolicyTest {

    private val expectedAt = Instant.fromEpochSeconds(1_786_570_000L)
    private val grace = 15.minutes
    private val deadline = expectedAt + grace

    // --- due / grace ---

    @Test
    fun notDueBeforeDeadline() {
        assertFalse(NoShowPolicy.isDue(expectedAt, grace, deadline - 1.seconds))
    }

    @Test
    fun dueExactlyAtDeadlineIsInclusive() {
        assertTrue(NoShowPolicy.isDue(expectedAt, grace, deadline))
    }

    @Test
    fun dueAfterDeadline() {
        assertTrue(NoShowPolicy.isDue(expectedAt, grace, deadline + 1.hours))
    }

    @Test
    fun zeroGraceIsDueAtExpectedAt() {
        assertFalse(NoShowPolicy.isDue(expectedAt, 0.seconds, expectedAt - 1.seconds))
        assertTrue(NoShowPolicy.isDue(expectedAt, 0.seconds, expectedAt))
    }

    @Test
    fun deadlineAddsGrace() {
        assertEquals(deadline, NoShowPolicy.deadline(expectedAt, grace))
    }

    // --- accuracy gate ---

    @Test
    fun accuracyGateAcceptsUpTo100mInclusive() {
        assertTrue(NoShowPolicy.isAccurateEnough(0.0))
        assertTrue(NoShowPolicy.isAccurateEnough(100.0))
        assertFalse(NoShowPolicy.isAccurateEnough(100.01))
    }

    @Test
    fun accuracyGateRejectsNonPhysicalValues() {
        assertFalse(NoShowPolicy.isAccurateEnough(-1.0))
        assertFalse(NoShowPolicy.isAccurateEnough(Double.NaN))
        assertFalse(NoShowPolicy.isAccurateEnough(Double.POSITIVE_INFINITY))
    }

    // --- enter containment edge cases ---

    @Test
    fun enterNeedsWholeErrorCircleInside() {
        assertTrue(NoShowPolicy.isEnterInside(distanceMeters = 40.0, accuracyMeters = 30.0, rangeMeters = 100.0))
        // Circle exactly touching the boundary stays where it was (strict <).
        assertFalse(NoShowPolicy.isEnterInside(distanceMeters = 70.0, accuracyMeters = 30.0, rangeMeters = 100.0))
        assertFalse(NoShowPolicy.isEnterInside(distanceMeters = 90.0, accuracyMeters = 30.0, rangeMeters = 100.0))
    }

    @Test
    fun exitHysteresisKeepsEdgeCasesInside() {
        // Center outside the radius but within the hysteresis margin: still inside.
        assertTrue(NoShowPolicy.isStillInside(distanceMeters = 110.0, accuracyMeters = 0.0, rangeMeters = 100.0))
        // Wholly past margin * accuracy: left.
        assertFalse(NoShowPolicy.isStillInside(distanceMeters = 120.0, accuracyMeters = 0.0, rangeMeters = 100.0))
        // Exact margin boundary stays (strict <).
        assertFalse(NoShowPolicy.isStillInside(distanceMeters = 120.0, accuracyMeters = 0.0, rangeMeters = 100.0))
    }

    @Test
    fun exitAccountsForAccuracyOnBothSides() {
        // Far center, but a huge error circle still overlaps the margin.
        assertTrue(NoShowPolicy.isStillInside(distanceMeters = 200.0, accuracyMeters = 100.0, rangeMeters = 100.0))
        // Precise fix just outside the margin has left.
        assertFalse(NoShowPolicy.isStillInside(distanceMeters = 125.0, accuracyMeters = 1.0, rangeMeters = 100.0))
    }

    // --- haversine sanity ---

    @Test
    fun haversineIsZeroForSamePointAndSymmetric() {
        assertEquals(0.0, NoShowPolicy.haversineMeters(48.0, 2.0, 48.0, 2.0))
        val a = NoShowPolicy.haversineMeters(48.0, 2.0, 49.0, 3.0)
        val b = NoShowPolicy.haversineMeters(49.0, 3.0, 48.0, 2.0)
        assertEquals(a, b, absoluteTolerance = 1e-6)
    }

    @Test
    fun haversineMatchesDegreeScale() {
        // One degree of latitude is ~111 km everywhere.
        val d = NoShowPolicy.haversineMeters(48.0, 2.0, 49.0, 2.0)
        assertTrue(d > 110_000.0 && d < 112_000.0, "expected ~111 km, got $d")
    }

    // --- arrival at waypoint ---

    private val waypointLat = 48.8566
    private val waypointLon = 2.3522
    private val range = 100.0

    @Test
    fun freshAccurateFixInsideCountsAsArrival() {
        assertTrue(
            NoShowPolicy.hasArrivedAtWaypoint(
                fixLat = waypointLat,
                fixLon = waypointLon,
                fixAccuracyMeters = 10.0,
                fixTimestamp = expectedAt + 1.minutes,
                expectedAt = expectedAt,
                waypointLat = waypointLat,
                waypointLon = waypointLon,
                waypointRangeMeters = range,
            )
        )
    }

    @Test
    fun staleFixInsideDoesNotCountAsArrival() {
        // A shutdown/parting report republishing a pre-expectation position
        // says nothing about the expected arrival, even if it lands on it.
        assertFalse(
            NoShowPolicy.hasArrivedAtWaypoint(
                fixLat = waypointLat,
                fixLon = waypointLon,
                fixAccuracyMeters = 10.0,
                fixTimestamp = expectedAt - 1.seconds,
                expectedAt = expectedAt,
                waypointLat = waypointLat,
                waypointLon = waypointLon,
                waypointRangeMeters = range,
            )
        )
    }

    @Test
    fun staleFixByReportedVsMeasuredDistinction() {
        // Measured long before the expectation but sent after it: still stale.
        // Callers must pass the measurement time (LocationValue.timestamp),
        // never the send time (reportedAt).
        assertFalse(
            NoShowPolicy.hasArrivedAtWaypoint(
                fixLat = waypointLat,
                fixLon = waypointLon,
                fixAccuracyMeters = 10.0,
                fixTimestamp = expectedAt - 2.hours,
                expectedAt = expectedAt,
                waypointLat = waypointLat,
                waypointLon = waypointLon,
                waypointRangeMeters = range,
            )
        )
    }

    @Test
    fun inaccurateFixInsideDoesNotCountAsArrival() {
        assertFalse(
            NoShowPolicy.hasArrivedAtWaypoint(
                fixLat = waypointLat,
                fixLon = waypointLon,
                fixAccuracyMeters = 150.0,
                fixTimestamp = expectedAt + 1.minutes,
                expectedAt = expectedAt,
                waypointLat = waypointLat,
                waypointLon = waypointLon,
                waypointRangeMeters = range,
            )
        )
    }

    @Test
    fun fixOutsideWaypointDoesNotCountAsArrival() {
        assertFalse(
            NoShowPolicy.hasArrivedAtWaypoint(
                fixLat = waypointLat + 0.01, // ~1.1 km north
                fixLon = waypointLon,
                fixAccuracyMeters = 10.0,
                fixTimestamp = expectedAt + 1.minutes,
                expectedAt = expectedAt,
                waypointLat = waypointLat,
                waypointLon = waypointLon,
                waypointRangeMeters = range,
            )
        )
    }

    @Test
    fun boundaryTouchingFixDoesNotCountAsArrival() {
        // ~100 m north with perfect accuracy: circle touches, strict < says out.
        assertFalse(
            NoShowPolicy.hasArrivedAtWaypoint(
                fixLat = waypointLat + 100.0 / 111_320.0,
                fixLon = waypointLon,
                fixAccuracyMeters = 0.0,
                fixTimestamp = expectedAt + 1.minutes,
                expectedAt = expectedAt,
                waypointLat = waypointLat,
                waypointLon = waypointLon,
                waypointRangeMeters = range,
            )
        )
    }

    // --- waypoint-less arrival ---

    @Test
    fun anyFreshFixCountsWhenNoWaypointWatched() {
        assertTrue(NoShowPolicy.hasAnyFreshFix(expectedAt, expectedAt))
        assertTrue(NoShowPolicy.hasAnyFreshFix(expectedAt + 1.hours, expectedAt))
        assertFalse(NoShowPolicy.hasAnyFreshFix(expectedAt - 1.seconds, expectedAt))
    }

    // --- fire-once guard ---

    @Test
    fun firesWhenDueUnfiredAndNobodyArrived() {
        assertTrue(
            NoShowPolicy.shouldFire(
                fired = false,
                expectedAt = expectedAt,
                grace = grace,
                now = deadline,
                arrived = false,
            )
        )
    }

    @Test
    fun alreadyFiredNeverRefires() {
        assertFalse(
            NoShowPolicy.shouldFire(
                fired = true,
                expectedAt = expectedAt,
                grace = grace,
                now = deadline + 24.hours,
                arrived = false,
            )
        )
    }

    @Test
    fun notDueDoesNotFire() {
        assertFalse(
            NoShowPolicy.shouldFire(
                fired = false,
                expectedAt = expectedAt,
                grace = grace,
                now = deadline - 1.seconds,
                arrived = false,
            )
        )
    }

    @Test
    fun arrivalSuppressesFiring() {
        assertFalse(
            NoShowPolicy.shouldFire(
                fired = false,
                expectedAt = expectedAt,
                grace = grace,
                now = deadline + 1.hours,
                arrived = true,
            )
        )
    }
}
