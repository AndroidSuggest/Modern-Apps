package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.NavigationStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pins the navigation-status channel (service 10) wire format.
 *
 * MA sender-defined throughout: the teardown never recovered the channel
 * message IDs, so these pin OUR numbering (STATUS 0x8001) to catch an
 * accidental renumber here rather than at a DHU that silently ignores us.
 * If the teardown ever recovers the real IDs, re-pin against them.
 */
class NavStatusCodecTest {

    @Test
    fun `the inactive stub states guidance off explicitly`() {
        // guidance_active false is 08 00 -- stated, not implied by absence.
        val (type, payload) = NavStatusCodec.encodeInactive()
        assertEquals(GalMessage.NavigationStatus.STATUS, type)
        assertContentEquals(byteArrayOf(0x08, 0x00), payload)
    }

    @Test
    fun `a live status round-trips its turn fields`() {
        val (type, payload) = NavStatusCodec.encodeStatus(
            guidanceActive = true,
            nextRoad = "El Camino Real",
            nextTurnDistanceM = 250,
            maneuver = "TURN_RIGHT",
        )
        assertEquals(GalMessage.NavigationStatus.STATUS, type)
        val parsed = NavigationStatus.parseFrom(payload)
        assertEquals(true, parsed.guidanceActive)
        assertEquals("El Camino Real", parsed.nextRoad)
        assertEquals(250, parsed.nextTurnDistanceM)
        assertEquals("TURN_RIGHT", parsed.maneuver)
    }

    @Test
    fun `absent turn fields stay absent`() {
        // A bare active flip posts only field 1; the head unit keeps its
        // last turn rather than clearing it on missing fields.
        val (_, payload) = NavStatusCodec.encodeStatus(guidanceActive = true)
        val parsed = NavigationStatus.parseFrom(payload)
        assertEquals(true, parsed.guidanceActive)
        assertEquals(false, parsed.hasNextRoad())
        assertEquals(false, parsed.hasNextTurnDistanceM())
        assertEquals(false, parsed.hasManeuver())
    }
}
