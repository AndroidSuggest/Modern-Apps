package com.vayunmathur.networklocation.geocoder

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Self-contained, offline geocoder database for the whole planet.
 *
 * Design (see the sizing discussion): addresses are stored in **grid-primary order** so that
 *  - reverse geocoding (coord -> nearest address) is a cheap cell lookup + local scan, needing
 *    only a small sparse grid directory (no per-record spatial index), and
 *  - coordinates delta-compress well because neighbours are adjacent in the file.
 * Every field is a dictionary index (strings deduped once), and every column is stored in
 * Deflate-compressed blocks so the reader mmaps the file and inflates just one small block per
 * access. Forward geocoding uses a second ordering (records sorted by
 * country/state/city/street/house) for binary search.
 *
 * Coordinates are micro-degrees (1e-6 deg, ~11 cm) as Int.
 *
 * NOTE: this is the v1 core (correct + compressed). Two follow-up optimisations, documented
 * where relevant: (a) resolve dictionary strings on-demand from the mmap instead of holding
 * them in RAM, and (b) make the forward index street-granular to drop the per-record second
 * ordering. Neither changes the on-disk contract for reverse geocoding.
 */
object GeoDb {
    const val MAGIC = 0x4D41_4745 // "MAGE"
    const val VERSION = 1
    const val BLOCK = 4096

    // Grid: 0.05-degree cells over the whole planet.
    const val CELL_MICRO = 50_000
    const val COLS = 360_000_000 / CELL_MICRO // 7200
    const val MIN_LAT_MICRO = -90_000_000
    const val MIN_LON_MICRO = -180_000_000

    val FIELD_COUNT = 6 // house, street, city, state, country, postcode
    // Column indices.
    const val C_LAT = 0
    const val C_LON = 1
    const val C_HOUSE = 2
    const val C_STREET = 3
    const val C_CITY = 4
    const val C_STATE = 5
    const val C_COUNTRY = 6
    const val C_POSTCODE = 7
    const val COLUMN_COUNT = 8

    fun cellIdOf(latMicro: Int, lonMicro: Int): Long {
        val row = ((latMicro - MIN_LAT_MICRO) / CELL_MICRO).toLong()
        val col = ((lonMicro - MIN_LON_MICRO) / CELL_MICRO).toLong()
        return row * COLS + col
    }

    fun toMicro(deg: Double): Int = (deg * 1_000_000.0).roundToInt()
    fun toDeg(micro: Int): Double = micro / 1_000_000.0
}

data class GeoAddress(
    val lat: Double,
    val lon: Double,
    val house: String,
    val street: String,
    val city: String,
    val state: String,
    val country: String,
    val postcode: String,
)

/** A resolved reverse-geocode / forward-geocode hit. */
data class GeoResult(
    val lat: Double,
    val lon: Double,
    val house: String,
    val street: String,
    val city: String,
    val state: String,
    val country: String,
    val postcode: String,
)

// ---------------------------------------------------------------------------------------------
// VarInt / zig-zag helpers.
// ---------------------------------------------------------------------------------------------

private fun ByteArrayOutputStream.writeVarInt(v0: Int) {
    var v = v0
    while (true) {
        val b = v and 0x7F
        v = v ushr 7
        if (v == 0) { write(b); return }
        write(b or 0x80)
    }
}

private fun zigzag(v: Int): Int = (v shl 1) xor (v shr 31)
internal fun unzigzag(v: Int): Int = (v ushr 1) xor -(v and 1)

internal class VarIntReader(val a: ByteArray) {
    var pos = 0
    fun readVarInt(): Int {
        var shift = 0
        var result = 0
        while (true) {
            val b = a[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
}

private fun deflate(data: ByteArray): ByteArray {
    val d = Deflater(Deflater.BEST_COMPRESSION, true)
    d.setInput(data); d.finish()
    val out = ByteArrayOutputStream(max(64, data.size / 2))
    val buf = ByteArray(8192)
    while (!d.finished()) out.write(buf, 0, d.deflate(buf))
    d.end()
    return out.toByteArray()
}

internal fun inflate(data: ByteArray, expected: Int): ByteArray {
    val inf = Inflater(true)
    inf.setInput(data)
    val out = ByteArray(expected)
    var off = 0
    while (off < expected && !inf.finished()) off += inf.inflate(out, off, expected - off)
    inf.end()
    return out
}

// ---------------------------------------------------------------------------------------------
// Writer.
// ---------------------------------------------------------------------------------------------

/** Column encoded as delta+zigzag+varint per block, each block Deflate-compressed. */
private fun encodeColumn(values: IntArray, delta: Boolean): Pair<ByteArray, IntArray> {
    val n = values.size
    val blocks = (n + GeoDb.BLOCK - 1) / GeoDb.BLOCK
    val body = ByteArrayOutputStream()
    val rawLens = IntArray(blocks)
    val compLens = IntArray(blocks)
    for (b in 0 until blocks) {
        val start = b * GeoDb.BLOCK
        val end = min(start + GeoDb.BLOCK, n)
        val raw = ByteArrayOutputStream()
        var prev = 0
        for (i in start until end) {
            val v = values[i]
            val enc = if (delta) v - prev else v
            raw.writeVarInt(zigzag(enc))
            prev = v
        }
        val rawBytes = raw.toByteArray()
        val comp = deflate(rawBytes)
        rawLens[b] = rawBytes.size
        compLens[b] = comp.size
        body.write(comp)
    }
    // Section layout: [n][blocks]{rawLen,compLen}*blocks [body]
    val head = ByteArrayOutputStream()
    val d = DataOutputStream(head)
    d.writeInt(n); d.writeInt(blocks)
    for (b in 0 until blocks) { d.writeInt(rawLens[b]); d.writeInt(compLens[b]) }
    val out = head.toByteArray() + body.toByteArray()
    return out to compLens
}

private fun encodeDict(strings: List<String>): ByteArray {
    val raw = ByteArrayOutputStream()
    val d = DataOutputStream(raw)
    d.writeInt(strings.size)
    for (s in strings) {
        val b = s.toByteArray(Charsets.UTF_8)
        d.writeInt(b.size); d.write(b)
    }
    val rawBytes = raw.toByteArray()
    val comp = deflate(rawBytes)
    val out = ByteArrayOutputStream()
    val h = DataOutputStream(out)
    h.writeInt(rawBytes.size); h.writeInt(comp.size)
    out.write(comp)
    return out.toByteArray()
}

class GeoDbWriter {
    fun write(file: File, addresses: List<GeoAddress>) {
        // Build dictionaries (sorted unique -> index is lexical order).
        val houses = sortedDict(addresses) { it.house }
        val streets = sortedDict(addresses) { it.street }
        val cities = sortedDict(addresses) { it.city }
        val states = sortedDict(addresses) { it.state }
        val countries = sortedDict(addresses) { it.country }
        val postcodes = sortedDict(addresses) { it.postcode }

        // Quantise + assign grid cells, then sort by cell (grid-primary order).
        data class Rec(
            val cell: Long, val latM: Int, val lonM: Int,
            val house: Int, val street: Int, val city: Int,
            val state: Int, val country: Int, val postcode: Int,
        )
        val recs = addresses.map { a ->
            val latM = GeoDb.toMicro(a.lat)
            val lonM = GeoDb.toMicro(a.lon)
            Rec(
                GeoDb.cellIdOf(latM, lonM), latM, lonM,
                houses.index(a.house), streets.index(a.street), cities.index(a.city),
                states.index(a.state), countries.index(a.country), postcodes.index(a.postcode),
            )
        }.sortedBy { it.cell }
        val n = recs.size

        // Columns.
        val lat = IntArray(n) { recs[it].latM }
        val lon = IntArray(n) { recs[it].lonM }
        val house = IntArray(n) { recs[it].house }
        val street = IntArray(n) { recs[it].street }
        val city = IntArray(n) { recs[it].city }
        val state = IntArray(n) { recs[it].state }
        val country = IntArray(n) { recs[it].country }
        val postcode = IntArray(n) { recs[it].postcode }

        // Sparse grid directory: for each non-empty cell, its start record index.
        val cellIds = ArrayList<Long>()
        val cellStarts = ArrayList<Int>()
        var prevCell = Long.MIN_VALUE
        for (i in 0 until n) {
            val c = recs[i].cell
            if (c != prevCell) { cellIds.add(c); cellStarts.add(i); prevCell = c }
        }

        // Forward ordering: record indices sorted by (country,state,city,street,house).
        val fwd = (0 until n).sortedWith(compareBy(
            { country[it] }, { state[it] }, { city[it] }, { street[it] }, { house[it] },
        )).toIntArray()

        // Serialise. Sections are length-prefixed and written sequentially; the reader scans
        // the length prefixes once to record each section's (offset, length) for seek-based
        // block reads.
        java.io.DataOutputStream(
            java.io.BufferedOutputStream(java.io.FileOutputStream(file))
        ).use { out ->
            out.writeInt(GeoDb.MAGIC)
            out.writeInt(GeoDb.VERSION)
            out.writeInt(n)

            // Dictionaries (6).
            for (dict in listOf(houses, streets, cities, states, countries, postcodes)) {
                val bytes = encodeDict(dict.list)
                out.writeInt(bytes.size); out.write(bytes)
            }

            // Columns (8): lat/lon delta-encoded, index columns raw-varint.
            val columns = listOf(
                lat to true, lon to true,
                house to false, street to false, city to false,
                state to false, country to false, postcode to false,
            )
            for ((values, delta) in columns) {
                val (bytes, _) = encodeColumn(values, delta)
                out.writeInt(bytes.size); out.write(bytes)
            }

            // Grid directory.
            val gridBytes = ByteArrayOutputStream()
            DataOutputStream(gridBytes).use { g ->
                g.writeInt(cellIds.size)
                for (i in cellIds.indices) { g.writeLong(cellIds[i]); g.writeInt(cellStarts[i]) }
            }
            out.writeInt(gridBytes.size()); gridBytes.writeTo(out)

            // Forward ordering (delta-varint, deflated as a single column).
            val (fwdBytes, _) = encodeColumn(fwd, delta = true)
            out.writeInt(fwdBytes.size); out.write(fwdBytes)
        }
    }

    private class Dict(val list: List<String>) {
        private val idx = HashMap<String, Int>(list.size * 2).apply {
            list.forEachIndexed { i, s -> put(s, i) }
        }
        fun index(s: String): Int = idx[s]!!
    }

    private fun sortedDict(addresses: List<GeoAddress>, sel: (GeoAddress) -> String): Dict =
        Dict(addresses.map(sel).toSortedSet().toList())
}
