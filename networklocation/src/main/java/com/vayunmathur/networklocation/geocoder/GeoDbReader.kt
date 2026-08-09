package com.vayunmathur.networklocation.geocoder

import java.io.DataInputStream
import java.io.File
import kotlin.math.cos
import kotlin.math.min

/**
 * Reader for a [GeoDb] file, backed by a [ByteSource] (a plain file or an mmap'd APK asset).
 * Dictionaries and the grid directory are held in memory; address columns are read
 * block-by-block on demand (positional read + decompress one block), so the bulk of the database
 * is never resident. Supports reverse (coord -> address) and structured forward geocoding.
 */
class GeoDbReader(private val src: ByteSource) : AutoCloseable {
    /** Convenience: read from a plain file. */
    constructor(file: File) : this(ChannelByteSource.of(file))

    private val n: Int

    private val dictHouse: Array<String>
    private val dictStreet: Array<String>
    private val dictCity: Array<String>
    private val dictState: Array<String>
    private val dictCountry: Array<String>
    private val dictPostcode: Array<String>

    private val cellIds: LongArray
    private val cellStarts: IntArray

    private val columns: Array<Column>
    private val fwd: Column // record indices in forward-sorted order

    init {
        var cursor = 0L
        require(src.readIntAt(cursor) == GeoDb.MAGIC) { "bad magic" }; cursor += 4
        require(src.readIntAt(cursor) == GeoDb.VERSION) { "bad version" }; cursor += 4
        n = src.readIntAt(cursor); cursor += 4

        fun readSectionBytes(): ByteArray {
            val size = src.readIntAt(cursor); cursor += 4
            val b = src.read(cursor, size); cursor += size
            return b
        }
        fun skipSection(): Long {
            val size = src.readIntAt(cursor); cursor += 4
            val off = cursor
            cursor += size
            return off
        }

        dictHouse = decodeDict(readSectionBytes())
        dictStreet = decodeDict(readSectionBytes())
        dictCity = decodeDict(readSectionBytes())
        dictState = decodeDict(readSectionBytes())
        dictCountry = decodeDict(readSectionBytes())
        dictPostcode = decodeDict(readSectionBytes())

        val colOffsets = LongArray(GeoDb.COLUMN_COUNT) { skipSection() }

        val grid = readSectionBytes()
        DataInputStream(grid.inputStream()).use { g ->
            val count = g.readInt()
            cellIds = LongArray(count)
            cellStarts = IntArray(count)
            for (i in 0 until count) { cellIds[i] = g.readLong(); cellStarts[i] = g.readInt() }
        }

        val fwdOff = skipSection()

        val delta = booleanArrayOf(true, true, false, false, false, false, false, false)
        columns = Array(GeoDb.COLUMN_COUNT) { i -> Column(src, colOffsets[i], delta[i]) }
        fwd = Column(src, fwdOff, delta = true)
    }

    val size: Int get() = n

    // ------------------------------------------------------------------ reverse geocoding

    /** Nearest stored address to (lat, lon), or null if the DB is empty. */
    fun reverse(lat: Double, lon: Double): GeoResult? {
        if (n == 0) return null
        val qLatM = GeoDb.toMicro(lat)
        val qLonM = GeoDb.toMicro(lon)
        val row = (qLatM - GeoDb.MIN_LAT_MICRO) / GeoDb.CELL_MICRO
        val col = (qLonM - GeoDb.MIN_LON_MICRO) / GeoDb.CELL_MICRO
        val lonScale = cos(Math.toRadians(lat))

        var bestRec = -1
        var bestDist = Double.MAX_VALUE
        var radius = 1
        while (bestRec < 0 && radius <= 32) {
            for (r in (row - radius)..(row + radius)) {
                if (r < 0) continue
                for (c in (col - radius)..(col + radius)) {
                    // On expansions past the first, only scan the new ring edge.
                    if (radius > 1 && r > row - radius && r < row + radius &&
                        c > col - radius && c < col + radius
                    ) continue
                    val cc = ((c % GeoDb.COLS) + GeoDb.COLS) % GeoDb.COLS
                    val cell = r.toLong() * GeoDb.COLS + cc
                    val gi = gridIndex(cell)
                    if (gi < 0) continue
                    val start = cellStarts[gi]
                    val end = if (gi + 1 < cellStarts.size) cellStarts[gi + 1] else n
                    for (i in start until end) {
                        val dLat = (columns[GeoDb.C_LAT].get(i) - qLatM).toDouble()
                        val dLon = (columns[GeoDb.C_LON].get(i) - qLonM).toDouble() * lonScale
                        val d = dLat * dLat + dLon * dLon
                        if (d < bestDist) { bestDist = d; bestRec = i }
                    }
                }
            }
            radius++
        }
        return if (bestRec < 0) null else resolve(bestRec)
    }

    // ------------------------------------------------------------------ forward geocoding

    /** All addresses matching the given components (exact per field). */
    fun forward(
        country: String,
        state: String,
        city: String,
        street: String,
        limit: Int = 20,
    ): List<GeoResult> {
        val kCountry = dictIndex(dictCountry, country)
        val kState = dictIndex(dictState, state)
        val kCity = dictIndex(dictCity, city)
        val kStreet = dictIndex(dictStreet, street)
        if (kCountry < 0 || kState < 0 || kCity < 0 || kStreet < 0) return emptyList()

        val target = intArrayOf(kCountry, kState, kCity, kStreet)
        var lo = 0
        var hi = n
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareKey(fwd.get(mid), target) < 0) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<GeoResult>()
        var k = lo
        while (k < n && out.size < limit) {
            val rec = fwd.get(k)
            if (compareKey(rec, target) != 0) break
            out.add(resolve(rec))
            k++
        }
        return out
    }

    private fun compareKey(rec: Int, target: IntArray): Int {
        var c = columns[GeoDb.C_COUNTRY].get(rec).compareTo(target[0]); if (c != 0) return c
        c = columns[GeoDb.C_STATE].get(rec).compareTo(target[1]); if (c != 0) return c
        c = columns[GeoDb.C_CITY].get(rec).compareTo(target[2]); if (c != 0) return c
        return columns[GeoDb.C_STREET].get(rec).compareTo(target[3])
    }

    // ------------------------------------------------------------------ helpers

    private fun resolve(rec: Int): GeoResult = GeoResult(
        lat = GeoDb.toDeg(columns[GeoDb.C_LAT].get(rec)),
        lon = GeoDb.toDeg(columns[GeoDb.C_LON].get(rec)),
        house = dictHouse[columns[GeoDb.C_HOUSE].get(rec)],
        street = dictStreet[columns[GeoDb.C_STREET].get(rec)],
        city = dictCity[columns[GeoDb.C_CITY].get(rec)],
        state = dictState[columns[GeoDb.C_STATE].get(rec)],
        country = dictCountry[columns[GeoDb.C_COUNTRY].get(rec)],
        postcode = dictPostcode[columns[GeoDb.C_POSTCODE].get(rec)],
    )

    /** Index of [cell] in the sparse grid directory, or -1. */
    private fun gridIndex(cell: Long): Int {
        var lo = 0
        var hi = cellIds.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = cellIds[mid]
            when {
                v < cell -> lo = mid + 1
                v > cell -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private fun dictIndex(dict: Array<String>, key: String): Int {
        var lo = 0
        var hi = dict.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = dict[mid].compareTo(key)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    override fun close() = src.close()

    private fun decodeDict(section: ByteArray): Array<String> {
        val di = DataInputStream(section.inputStream())
        val rawSize = di.readInt()
        val compSize = di.readInt()
        val comp = ByteArray(compSize); di.readFully(comp)
        val raw = decompress(comp, rawSize)
        val r = DataInputStream(raw.inputStream())
        val count = r.readInt()
        return Array(count) {
            val len = r.readInt()
            val b = ByteArray(len); r.readFully(b)
            String(b, Charsets.UTF_8)
        }
    }
}

/**
 * On-demand column reader. Parses the column header (block sizes) up front, then decompresses and
 * decodes a single block per access, caching the most recently used block. [get] is
 * synchronised so multiple binder threads can share one reader.
 */
private class Column(
    private val src: ByteSource,
    sectionOffset: Long,
    private val delta: Boolean,
) {
    private val n: Int
    private val blockCount: Int
    private val rawLens: IntArray
    private val compLens: IntArray
    private val blockFileOffset: LongArray

    private var cachedBlock = -1
    private var cachedValues: IntArray = IntArray(0)

    init {
        var p = sectionOffset
        n = src.readIntAt(p); p += 4
        blockCount = src.readIntAt(p); p += 4
        rawLens = IntArray(blockCount)
        compLens = IntArray(blockCount)
        for (b in 0 until blockCount) {
            rawLens[b] = src.readIntAt(p); p += 4
            compLens[b] = src.readIntAt(p); p += 4
        }
        blockFileOffset = LongArray(blockCount)
        var off = p
        for (b in 0 until blockCount) { blockFileOffset[b] = off; off += compLens[b] }
    }

    @Synchronized
    fun get(i: Int): Int {
        val block = i / GeoDb.BLOCK
        if (block != cachedBlock) decode(block)
        return cachedValues[i - block * GeoDb.BLOCK]
    }

    private fun decode(block: Int) {
        val comp = src.read(blockFileOffset[block], compLens[block])
        val raw = decompress(comp, rawLens[block])
        val count = min(GeoDb.BLOCK, n - block * GeoDb.BLOCK)
        val values = IntArray(count)
        val vr = VarIntReader(raw)
        var prev = 0
        for (k in 0 until count) {
            val v = unzigzag(vr.readVarInt())
            val actual = if (delta) prev + v else v
            values[k] = actual
            prev = actual
        }
        cachedValues = values
        cachedBlock = block
    }
}
