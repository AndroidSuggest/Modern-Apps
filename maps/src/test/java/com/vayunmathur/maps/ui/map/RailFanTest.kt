package com.vayunmathur.maps.ui.map

import com.vayunmathur.library.map.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The host-side corridor fan: lane counts step with the zoom bands, offsets
 * squash from the middle, tapers ease mouth pieces. Ported from the tile
 * style (`transit-rail`: spread 6.0, lanes ramp `[[0,1],[9,2],[11,3],[13,4]]`,
 * `lane_offset_px`), so the pack overlay fans exactly like the tile layer.
 */
class RailFanTest {

    @Test
    fun `lane count steps at the style bands`() {
        assertEquals(1, railLaneCount(0.0))
        assertEquals(1, railLaneCount(8.9))
        assertEquals(2, railLaneCount(9.0))
        assertEquals(2, railLaneCount(10.0))
        assertEquals(3, railLaneCount(11.0))
        assertEquals(3, railLaneCount(12.0))
        assertEquals(4, railLaneCount(13.0))
        assertEquals(4, railLaneCount(16.0))
    }

    @Test
    fun `a lone line never offsets`() {
        assertEquals(0.0, railLaneOffsetDp(13.0, 0, 1, 255))
        assertEquals(0.0, railLaneOffsetDp(13.0, 0, 1, 0))
    }

    @Test
    fun `two lanes split symmetrically`() {
        assertEquals(-3.0, railLaneOffsetDp(13.0, 0, 2, 255))
        assertEquals(3.0, railLaneOffsetDp(13.0, 1, 2, 255))
    }

    @Test
    fun `six colours squash from the middle`() {
        // The tile oracle: 0,1,1,2,2,3 — edges keep lanes, middle doubles.
        val offsets = (0 until 6).map { railLaneOffsetDp(13.0, it, 6, 255) }
        assertEquals(listOf(-9.0, -3.0, -3.0, 3.0, 3.0, 9.0), offsets)
    }

    @Test
    fun `taper scales the offset linearly`() {
        assertEquals(0.0, railLaneOffsetDp(13.0, 1, 2, 0))
        val half = railLaneOffsetDp(13.0, 1, 2, 128)
        assertTrue(abs(half - 3.0 * 128 / 255) < 1e-9, "$half")
    }

    @Test
    fun `offset polyline shifts right of travel`() {
        // Due-north line: right of travel is east.
        val line = listOf(GeoPoint(-122.4, 37.7), GeoPoint(-122.4, 37.71))
        val shifted = offsetPolyline(line, 10.0)
        assertEquals(2, shifted.size)
        assertTrue(shifted[0].longitude > -122.4, "east of the line")
        assertTrue(abs(shifted[0].latitude - 37.7) < 1e-9, "latitude unchanged")
        // ~10 m east at this latitude.
        val east = (shifted[0].longitude + 122.4) * 111_320.0 * 0.79
        assertTrue(abs(east - 10.0) < 0.5, "$east")
    }

    @Test
    fun `zero offset returns the line untouched`() {
        val line = listOf(GeoPoint(-122.4, 37.7), GeoPoint(-122.4, 37.71))
        assertEquals(line, offsetPolyline(line, 0.0))
    }
}
