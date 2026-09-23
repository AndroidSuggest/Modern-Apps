package com.vayunmathur.library.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The marker label arrays crossing JNI in [MapNative.setMarkers].
 *
 * Labels ride parallel to ids/coords/icons (same index): a null label packs as the empty
 * string, which the native side reads as "icon alone". Pinned so a future change cannot
 * quietly drop the labels array or misalign it with the pins.
 */
class MarkerArraysTest {

    private fun pin(id: Long, label: String?) =
        MapMarker(id, GeoPoint(1.0, 2.0), MarkerIcon.FAMILY, label = label)

    @Test
    fun `labels pack parallel to ids with nulls as empty`() {
        val packed = packMapMarkers(listOf(pin(7L, "Ada"), pin(8L, null)))

        assertEquals(listOf(7L, 8L), packed.ids.toList())
        assertEquals(listOf("Ada", ""), packed.labels.toList())
    }

    @Test
    fun `unlabeled pins pack all empty`() {
        val packed = packMapMarkers(
            listOf(
                MapMarker(1L, GeoPoint(0.0, 0.0), MarkerIcon.PARKING),
                MapMarker(2L, GeoPoint(0.0, 0.0), MarkerIcon.SEARCH),
            )
        )

        assertTrue(packed.labels.all { it.isEmpty() })
        assertEquals(2, packed.ids.size)
        assertEquals(4, packed.lonLat.size)
        assertEquals(2, packed.icons.size)
    }

    @Test
    fun `coordinates and icons are untouched by labels`() {
        val packed = packMapMarkers(
            listOf(MapMarker(3L, GeoPoint(10.0, 20.0), MarkerIcon.SAVED, label = "Home"))
        )

        assertEquals(10.0f, packed.lonLat[0])
        assertEquals(20.0f, packed.lonLat[1])
        assertEquals(MarkerIcon.SAVED, packed.icons[0])
        assertEquals("Home", packed.labels[0])
    }
}
