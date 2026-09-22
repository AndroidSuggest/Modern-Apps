package com.vayunmathur.library.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The native pick row contract (`bridge/pick.rs` emission vs [parseLabelRow]).
 *
 * Rows are `\u0001`-joined `layerId/name/kind/lon/lat/featureId/regionId`; legacy rows
 * carry 6 fields and parse with `regionId = 0`. Before the 7-field parse existed, the
 * `parts.size == 6` filter dropped every hit and taps selected nothing — silently, because
 * the empty list is also the legitimate "no hit" answer. These pin the sizes so a format
 * drift fails here rather than on device.
 */
class LabelRowParseTest {

    private fun row(vararg parts: String) = parts.toList()

    @Test
    fun seven_parts_parse_with_the_region_id_set() {
        val label = parseLabelRow(row("places-locality", "Paris", "locality", "2.35", "48.85", "1234", "777"))
            ?: error("expected a label")
        assertEquals("places-locality", label.layerId)
        assertEquals("Paris", label.name)
        assertEquals("locality", label.kind)
        assertEquals(2.35, label.position.longitude)
        assertEquals(48.85, label.position.latitude)
        assertEquals(1234L, label.featureId)
        assertEquals(777L, label.regionId)
    }

    @Test
    fun six_parts_parse_as_legacy_with_region_id_zero() {
        val label = parseLabelRow(row("places-locality", "Paris", "locality", "2.35", "48.85", "1234"))
            ?: error("expected a label")
        assertEquals(1234L, label.featureId)
        assertEquals(0L, label.regionId, "legacy rows link to nothing")
    }

    @Test
    fun wrong_sizes_are_dropped() {
        assertNull(parseLabelRow(row("places-locality", "Paris", "locality", "2.35", "48.85")))
        assertNull(parseLabelRow(row("places-locality", "Paris", "locality", "2.35", "48.85", "1", "2", "3")))
        assertNull(parseLabelRow(emptyList()))
    }

    @Test
    fun unparseable_numbers_fall_back_to_zero() {
        val label = assertNotNull(
            parseLabelRow(row("places-locality", "Paris", "locality", "oops", "oops", "oops", "oops")),
        )
        assertEquals(0.0, label.position.longitude)
        assertEquals(0.0, label.position.latitude)
        assertEquals(0L, label.featureId)
        assertEquals(0L, label.regionId)
    }
}
