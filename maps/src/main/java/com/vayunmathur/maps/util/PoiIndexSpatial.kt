package com.vayunmathur.maps.util

/**
 * The CSR spatial-grid half of [PoiIndex]: bbox queries over `poi_spatial.bin`.
 *
 * Split out so PoiIndex.kt stays under the FileLength limit. These are `internal`
 * extensions on `PoiIndex.Mapped`; `forEachInBbox` keeps dispatching to
 * [forEachInCells] when the grid is present.
 */

/** Cell offset along one axis. Must match `cell_axis` in `poi_side.rs`. */
private fun PoiIndex.Mapped.axis(value: Int, origin: Int): Int {
    val d = value.toLong() - origin.toLong()
    return if (d <= 0) 0 else (d / cellE7).toInt()
}

private fun PoiIndex.Mapped.row(latE7: Int): Int = axis(latE7, lat0E7)
private fun PoiIndex.Mapped.col(lonE7: Int): Int = axis(lonE7, lon0E7).coerceAtMost(cols - 1)

/** Index of [cellId] in the ascending cell-id array, or -1 when unpopulated. */
private fun PoiIndex.Mapped.cellIndexOf(cellId: Int): Int {
    val buf = spatial ?: return -1
    var lo = 0
    var hi = cellCount
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val v = buf.getInt(PoiIndex.SPATIAL_HEADER_BYTES + 4 * mid)
        if (v == cellId) return mid
        if (v < cellId) lo = mid + 1 else hi = mid
    }
    return -1
}

/** CSR prefix entry [i], i.e. where cell `i`'s ordinals begin. */
private fun PoiIndex.Mapped.cellOff(i: Int): Int =
    spatial!!.getInt(PoiIndex.SPATIAL_HEADER_BYTES + 4 * cellCount + 4 * i)

private fun PoiIndex.Mapped.gridOrdinal(k: Int): Int =
    spatial!!.getInt(PoiIndex.SPATIAL_HEADER_BYTES + 4 * cellCount + 4 * (cellCount + 1) + 4 * k)

internal fun PoiIndex.Mapped.forEachInCells(
    minLatE7: Int,
    maxLatE7: Int,
    minLonE7: Int,
    maxLonE7: Int,
    onHit: (ordinal: Int, latE7: Int, lonE7: Int) -> Boolean,
) {
    val hits = ArrayList<Int>()
    for (r in row(minLatE7)..row(maxLatE7)) {
        for (c in col(minLonE7)..col(maxLonE7)) {
            val ci = cellIndexOf(r * cols + c)
            if (ci < 0) continue
            for (k in cellOff(ci) until cellOff(ci + 1)) {
                val ordinal = gridOrdinal(k)
                if (ordinal < 0 || ordinal >= count) continue
                if (latE7(ordinal) in minLatE7..maxLatE7 &&
                    lonE7(ordinal) in minLonE7..maxLonE7
                ) {
                    hits.add(ordinal)
                }
            }
        }
    }
    hits.sort()
    for (ordinal in hits) {
        if (!onHit(ordinal, latE7(ordinal), lonE7(ordinal))) return
    }
}

private class Hit(val idx: Int, val distSq: Double)

/**
 * Nearest POIs to ([lat],[lon]) within [maxMeters], closest first (bbox
 * pre-filter + squared-distance sort). Names are decoded only for the
 * returned winners. Empty when the index isn't loaded.
 */
internal fun PoiIndex.nearest(
    lat: Double,
    lon: Double,
    limit: Int = 20,
    maxMeters: Double = 250.0,
): List<PoiIndex.PoiRecord> {
    val m = mappedForQuery() ?: return emptyList()
    val dLat = maxMeters / 111_320.0
    val cosLat = Math.cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
    val dLon = maxMeters / (111_320.0 * cosLat)
    val minLatE7 = ((lat - dLat) * 1e7).toInt()
    val maxLatE7 = ((lat + dLat) * 1e7).toInt()
    val minLonE7 = ((lon - dLon) * 1e7).toInt()
    val maxLonE7 = ((lon + dLon) * 1e7).toInt()

    val hits = ArrayList<Hit>()
    m.forEachInBbox(minLatE7, maxLatE7, minLonE7, maxLonE7) { i, latE7, lonE7 ->
        hits.add(Hit(i, distanceSq(latE7 / 1e7, lonE7 / 1e7, lat, lon)))
        true
    }
    // Stable, so records at an equal distance stay in file order — the tie-break the
    // scan this replaced had, and what the equivalence test pins.
    hits.sortBy { it.distSq }
    return hits.take(limit).map { m.record(it.idx) }
}

/**
 * All POIs whose coordinate falls inside the [west]..[east] × [south]..[north]
 * bounding box, capped at [cap]. Names are decoded for each returned record.
 * Empty when the index isn't loaded; used to drive the ambient offline pin
 * overlay (P29).
 */
internal fun PoiIndex.inViewport(
    west: Double,
    south: Double,
    east: Double,
    north: Double,
    cap: Int = 300,
): List<PoiIndex.PoiRecord> {
    val m = mappedForQuery() ?: return emptyList()
    if (cap <= 0) return emptyList()
    val minLatE7 = (south * 1e7).toInt()
    val maxLatE7 = (north * 1e7).toInt()
    val minLonE7 = (west * 1e7).toInt()
    val maxLonE7 = (east * 1e7).toInt()
    val out = ArrayList<PoiIndex.PoiRecord>(minOf(cap, m.count))
    m.forEachInBbox(minLatE7, maxLatE7, minLonE7, maxLonE7) { i, _, _ ->
        out.add(m.record(i))
        out.size < cap
    }
    return out
}
