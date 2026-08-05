package com.vayunmathur.measure.domain

import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.distanceTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolygonTest {

    private fun a(id: Long, x: Double, y: Double, z: Double) = Anchor(id, x, y, z)

    @Test
    fun `unit square has unit area`() {
        val square = listOf(
            a(1, 0.0, 0.0, 0.0),
            a(2, 1.0, 0.0, 0.0),
            a(3, 1.0, 1.0, 0.0),
            a(4, 0.0, 1.0, 0.0),
        )
        assertEquals(1.0, polygonArea(square), 1e-9)
    }

    @Test
    fun `area is orientation independent`() {
        // The same rectangle standing vertically instead of lying flat.
        val vertical = listOf(
            a(1, 0.0, 0.0, 0.0),
            a(2, 2.0, 0.0, 0.0),
            a(3, 2.0, 0.0, 3.0),
            a(4, 0.0, 0.0, 3.0),
        )
        assertEquals(6.0, polygonArea(vertical), 1e-9)
    }

    @Test
    fun `area is unaffected by winding direction`() {
        val cw = listOf(
            a(1, 0.0, 0.0, 0.0),
            a(2, 0.0, 1.0, 0.0),
            a(3, 1.0, 1.0, 0.0),
            a(4, 1.0, 0.0, 0.0),
        )
        assertEquals(1.0, polygonArea(cw), 1e-9)
    }

    @Test
    fun `triangle area is half base times height`() {
        val tri = listOf(
            a(1, 0.0, 0.0, 0.0),
            a(2, 4.0, 0.0, 0.0),
            a(3, 0.0, 3.0, 0.0),
        )
        assertEquals(6.0, polygonArea(tri), 1e-9)
    }

    @Test
    fun `fewer than three vertices has no area`() {
        assertEquals(0.0, polygonArea(emptyList()), 1e-12)
        assertEquals(0.0, polygonArea(listOf(a(1, 0.0, 0.0, 0.0))), 1e-12)
        assertEquals(0.0, polygonArea(listOf(a(1, 0.0, 0.0, 0.0), a(2, 1.0, 0.0, 0.0))), 1e-12)
    }

    @Test
    fun `open perimeter excludes the closing edge`() {
        val chain = listOf(
            a(1, 0.0, 0.0, 0.0),
            a(2, 3.0, 0.0, 0.0),
            a(3, 3.0, 4.0, 0.0),
        )
        assertEquals(7.0, polygonPerimeter(chain, closed = false), 1e-9)
        // Closing adds the 3-4-5 hypotenuse back to the start.
        assertEquals(12.0, polygonPerimeter(chain, closed = true), 1e-9)
    }

    @Test
    fun `perimeter of a unit square is four`() {
        val square = listOf(
            a(1, 0.0, 0.0, 0.0),
            a(2, 1.0, 0.0, 0.0),
            a(3, 1.0, 1.0, 0.0),
            a(4, 0.0, 1.0, 0.0),
        )
        assertEquals(4.0, polygonPerimeter(square, closed = true), 1e-9)
    }

    @Test
    fun `coplanar vertices report no planarity error`() {
        val square = listOf(
            a(1, 0.0, 0.0, 0.0),
            a(2, 1.0, 0.0, 0.0),
            a(3, 1.0, 1.0, 0.0),
            a(4, 0.0, 1.0, 0.0),
        )
        assertTrue(planarityError(square) < 1e-9)
    }

    @Test
    fun `a lifted corner is detected as non planar`() {
        val skewed = listOf(
            a(1, 0.0, 0.0, 0.0),
            a(2, 1.0, 0.0, 0.0),
            a(3, 1.0, 1.0, 0.0),
            a(4, 0.0, 1.0, 0.5),
        )
        assertTrue(
            planarityError(skewed) > 0.05,
            "a 50 cm lifted corner should be flagged, got ${planarityError(skewed)}"
        )
    }

    @Test
    fun `distance between anchors is euclidean`() {
        val p = a(1, 0.0, 0.0, 0.0)
        val q = a(2, 3.0, 4.0, 12.0)
        assertEquals(13.0, p.distanceTo(q), 1e-9)
    }
}
