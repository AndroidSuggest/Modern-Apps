package com.vayunmathur.networklocation.geocoder

import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.cos
import kotlin.math.min

/**
 * Reader for a [GeoDb] file. Dictionaries and the grid directory are held in memory; address
 * columns are read block-by-block from the file on demand (seek + inflate one block), so the
 * bulk of the database is never resident. Supports reverse (coord -> address) and structured
 * forward (country/state/city/street -> addresses) geocoding.
 */
class GeoDbReader(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
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
        raf.seek(0)
        require(raf.readInt() == GeoDb.MAGIC) { "bad magic" }
        require(raf.readInt() == GeoDb.VERSION) { "bad version" }
        n = raf.readInt()

        fun readSectionBytes(): ByteArray {
            val size = raf.readInt()
            val b = ByteArray(size)
            raf.readFully(b)
            return b
        }
        fun sectionOffset(): Pair<Long, Int> {
            val size = raf.readInt()
            val off = raf.filePointer
            raf.seek(off + size) // skip body; we'll seek back for block reads
            return off to size
        }

        dictHouse = decodeDict(readSectionBytes())
        dictStreet = decodeDict(readSectionBytes())
        dictCity = decodeDict(readSectionBytes())
        dictState = decodeDict(readSectionBytes())
        dictCountry = decodeDict(readSectionBytes())
        dictPostcode = decodeDict(readSectionBytes())

        // Phase 1: record column offsets by skipping their bodies (do NOT build Column yet —
        // Column construction seeks, which would corrupt this sequential scan).
        val colOffsets = LongArray(GeoDb.COLUMN_COUNT)
        for (i in 0 until GeoDb.COLUMN_COUNT) colOffsets[i] = sectionOffset().first

        // Grid directory.
        val grid = readSectionBytes()
        DataInputStream(grid.inputStream()).use { g ->
            val count = g.readInt()
            cellIds = LongArray(count)
            cellStarts = IntArray(count)
            for (i in 0 until count) { cellIds[i] = g.readLong(); cellStarts[i] = g.readInt() }
        }

        val fwdOff = sectionOffset().first

        // Phase 2: now that the sequential scan is done, build the on-demand column readers.
        val delta = booleanArrayOf(true, true, false, false, false, false, false, false)
        columns = Array(GeoDb.COLUMN_COUNT) { i -> Column(raf, colOffsets[i], delta[i]) }
        fwd = Column(raf, fwdOff, delta = true)
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
                    // Only scan the ring edge on expansions after the first.
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

    /** All addresses matching the given components (case-insensitive exact on each field). */
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
        // Lower bound over the forward ordering.
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
        // Dictionaries are stored sorted (lexical), so binary search matches the stored order.
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

    override fun close() = raf.close()

    private fun decodeDict(section: ByteArray): Array<String> {
        val di = DataInputStream(section.inputStream())
        val rawSize = di.readInt()
        val compSize = di.readInt()
        val comp = ByteArray(compSize); di.readFully(comp)
        val raw = inflate(comp, rawSize)
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
 * On-demand column reader. Parses the column header (block sizes) up front, then inflates and
 * decodes a single block per access, caching the most recently used block.
 */
private class Column(
    private val raf: RandomAccessFile,
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
        synchronized(raf) {
            raf.seek(sectionOffset)
            n = raf.readInt()
            blockCount = raf.readInt()
            rawLens = IntArray(blockCount)
            compLens = IntArray(blockCount)
            for (b in 0 until blockCount) { rawLens[b] = raf.readInt(); compLens[b] = raf.readInt() }
            val bodyStart = raf.filePointer
            blockFileOffset = LongArray(blockCount)
            var off = bodyStart
            for (b in 0 until blockCount) { blockFileOffset[b] = off; off += compLens[b] }
        }
    }

    fun get(i: Int): Int {
        val block = i / GeoDb.BLOCK
        if (block != cachedBlock) decode(block)
        return cachedValues[i - block * GeoDb.BLOCK]
    }

    private fun decode(block: Int) {
        val comp = ByteArray(compLens[block])
        synchronized(raf) {
            raf.seek(blockFileOffset[block])
            raf.readFully(comp)
        }
        val raw = inflate(comp, rawLens[block])
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
