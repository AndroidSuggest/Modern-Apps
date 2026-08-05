package com.vayunmathur.measure.domain

import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.distanceTo
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Area of a polygon whose vertices are 3D points assumed to be roughly coplanar.
 *
 * Uses the vector cross-product form of the shoelace formula, which works directly in
 * 3D and needs no projection onto a fitted plane. Anchors snapped to the same plane are
 * exactly coplanar; free-placed ones are only approximately so, and this degrades
 * gracefully rather than requiring a separate code path.
 */
fun polygonArea(vertices: List<Anchor>): Double {
    if (vertices.size < 3) return 0.0
    var cx = 0.0
    var cy = 0.0
    var cz = 0.0
    val origin = vertices[0]
    for (i in 1 until vertices.size - 1) {
        val a = vertices[i]
        val b = vertices[i + 1]
        val ux = a.x - origin.x
        val uy = a.y - origin.y
        val uz = a.z - origin.z
        val vx = b.x - origin.x
        val vy = b.y - origin.y
        val vz = b.z - origin.z
        cx += uy * vz - uz * vy
        cy += uz * vx - ux * vz
        cz += ux * vy - uy * vx
    }
    return 0.5 * sqrt(cx * cx + cy * cy + cz * cz)
}

/** Total edge length. When [closed], includes the edge from the last vertex back to the first. */
fun polygonPerimeter(vertices: List<Anchor>, closed: Boolean): Double {
    if (vertices.size < 2) return 0.0
    var total = 0.0
    for (i in 0 until vertices.size - 1) {
        total += vertices[i].distanceTo(vertices[i + 1])
    }
    if (closed && vertices.size >= 3) {
        total += vertices.last().distanceTo(vertices.first())
    }
    return total
}

/**
 * How far the vertices deviate from a best-fit plane, in metres.
 *
 * Reported so the UI can warn that an area figure is unreliable: a "room" whose corners
 * are 20 cm out of plane was not tapped on one surface, and its area is meaningless.
 */
fun planarityError(vertices: List<Anchor>): Double {
    if (vertices.size < 4) return 0.0
    val n = vertices.size
    val cx = vertices.sumOf { it.x } / n
    val cy = vertices.sumOf { it.y } / n
    val cz = vertices.sumOf { it.z } / n

    // Normal from the two widest-spread edges off the centroid.
    val a = vertices[0]
    val b = vertices[n / 3]
    val c = vertices[2 * n / 3]
    val ux = b.x - a.x
    val uy = b.y - a.y
    val uz = b.z - a.z
    val vx = c.x - a.x
    val vy = c.y - a.y
    val vz = c.z - a.z
    var nx = uy * vz - uz * vy
    var ny = uz * vx - ux * vz
    var nz = ux * vy - uy * vx
    val len = sqrt(nx * nx + ny * ny + nz * nz)
    if (len < 1e-12) return 0.0
    nx /= len
    ny /= len
    nz /= len

    return vertices.maxOf { abs((it.x - cx) * nx + (it.y - cy) * ny + (it.z - cz) * nz) }
}
