package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.SensorType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the Phase 6 maps-dev guidance producers.
 *
 * Coordinate math and event shaping live here (host-testable); the channel
 * owners stay thin. Exact bytes only for the integer fields the teardown or
 * our own protos pin -- e7 scaling is asserted by value, not by byte.
 */
class MapsGuidanceTest {

    @Test
    fun `fix scales decimal degrees to e7`() {
        val snapshot = MapsGuidance.snapshotFromFix(
            latitude = 37.422,
            longitude = -122.084,
            speedMps = 13.9,
            parked = false,
            isNight = true,
        )
        assertEquals(374_220_000L, snapshot.latitudeE7)
        assertEquals(-1_220_840_000L, snapshot.longitudeE7)
        assertEquals(13.9, snapshot.speedMps, 1e-9)
        assertEquals(false, snapshot.parked)
        assertEquals(true, snapshot.isNight)
        // Guidance and route stay idle until the provider overlays them.
        assertEquals(false, snapshot.guidanceActive)
        assertNull(snapshot.route)
    }

    @Test
    fun `sensor events carry stub parity without night when unknown`() {
        val snapshot = MapsGuidance.snapshotFromFix(
            latitude = 37.0,
            longitude = -122.0,
            accuracyM = 8,
            fixTimestampMs = 42L,
        )
        val events = MapsGuidance.toSensorEvents(snapshot)
        assertEquals(
            listOf(
                SensorType.LOCATION,
                SensorType.SPEED,
                SensorType.DRIVING_STATUS_DATA,
            ),
            events.map { it.sensorType },
        )
        val folded = SensorSnapshot().withEvents(events)
        assertEquals(370_000_000L, folded.location?.latitudeE7)
        assertEquals(-1_220_000_000L, folded.location?.longitudeE7)
        assertEquals(8, folded.location?.accuracyM)
        assertEquals(42L, folded.location?.timestampMs)
        assertEquals(0.0, folded.speedMps, 1e-9)
        assertTrue(folded.parked)
        // Unknown night keeps last-known: the event is omitted, not posted empty.
        assertNull(folded.isNight)
    }

    @Test
    fun `sensor events append night when the phone state is known`() {
        val day = MapsGuidance.toSensorEvents(
            MapsGuidance.snapshotFromFix(0.0, 0.0, isNight = false),
        )
        assertEquals(
            listOf(
                SensorType.LOCATION,
                SensorType.SPEED,
                SensorType.DRIVING_STATUS_DATA,
                SensorType.NIGHT_MODE,
            ),
            day.map { it.sensorType },
        )
        assertEquals(false, SensorSnapshot().withEvents(day).isNight)
    }

    @Test
    fun `nav status selects the post arguments and encodes them`() {
        val snapshot = MapsGuidance.snapshotFromFix(37.0, -122.0).copy(
            guidanceActive = true,
            nextRoad = "Amphitheatre Pkwy",
            nextTurnDistanceM = 250,
            maneuver = "turn-right",
        )
        val update = MapsGuidance.toNavStatus(snapshot)
        assertEquals(
            NavStatusUpdate(
                guidanceActive = true,
                nextRoad = "Amphitheatre Pkwy",
                nextTurnDistanceM = 250,
                maneuver = "turn-right",
            ),
            update,
        )
        val (type, payload) = MapsGuidance.encodeNavStatus(update)
        assertEquals(GalMessage.NavigationStatus.STATUS, type)
        val (directType, directPayload) = NavStatusCodec.encodeStatus(
            true,
            "Amphitheatre Pkwy",
            250,
            "turn-right",
        )
        assertEquals(directType, type)
        assertContentEquals(directPayload, payload)
    }

    @Test
    fun `idle nav status is guidance off with no turn fields`() {
        val (type, payload) = MapsGuidance.encodeNavStatus(
            MapsGuidance.toNavStatus(MapsGuidance.snapshotFromFix(0.0, 0.0)),
        )
        val (inactiveType, inactivePayload) = NavStatusCodec.encodeInactive()
        assertEquals(inactiveType, type)
        assertContentEquals(inactivePayload, payload)
    }

    @Test
    fun `ch10 status sits in the service range`() {
        val (type, _) = MapsGuidance.encodeNavStatus(
            NavStatusUpdate(guidanceActive = false),
        )
        assertTrue(type in 0x8000..0xFFFF)
    }
}
