package com.vayunmathur.maps.util

/**
 * The word-index half of [PoiIndex]: prefix search over `poi_name_index.bin`.
 *
 * Split out so PoiIndex.kt stays under the FileLength limit. These are `internal`
 * extensions on `PoiIndex.Mapped` — which therefore has to be visible outside the
 * file — plus the indexed search entry point that [PoiIndex.searchByName]
 * delegates to.
 */

private const val WORD_BYTE_MASK = 0xFF
private const val WORD_CASE_OFFSET = 32
private const val WORD_VT = 0x0B
private const val WORD_FF = 0x0C
private const val WORD_ENTRY_BYTES = 5L
/** Bytes of one int32 slot in the word-index arrays. */
private const val INT_SLOT_BYTES = 4

/**
 * ASCII-only lowercase, and deliberately not [Char.lowercase].
 *
 * `poi_name_index.bin` is sorted by the writer, so the reader has to reproduce that
 * order byte for byte. Rust's `to_lowercase` and Kotlin's `lowercase` do not agree on
 * every input, and here a disagreement is a POI that can never be found. See the
 * cross-language contract in `osm_ingest/src/poi_side.rs`.
 */
internal fun asciiLowerW(b: Byte): Int {
    val v = b.toInt() and WORD_BYTE_MASK
    return if (v >= 'A'.code && v <= 'Z'.code) v + WORD_CASE_OFFSET else v
}

internal fun isAsciiSpaceW(b: Byte): Boolean {
    val v = b.toInt() and WORD_BYTE_MASK
    return v == ' '.code || v == '\t'.code || v == '\n'.code || v == '\r'.code ||
        v == WORD_VT || v == WORD_FF
}

/** A query as the index's sort key: UTF-8 bytes, ASCII-lowercased. */
internal fun poiQueryKey(query: String): ByteArray {
    val raw = query.toByteArray(Charsets.UTF_8)
    return ByteArray(raw.size) { asciiLowerW(raw[it]).toByte() }
}

private fun PoiIndex.Mapped.entryOrdinal(i: Int): Int =
    nameIdx!!.getInt(PoiIndex.NAME_INDEX_HEADER_BYTES + INT_SLOT_BYTES * i)

private fun PoiIndex.Mapped.entryWordIdx(i: Int): Int {
    val off = PoiIndex.NAME_INDEX_HEADER_BYTES + INT_SLOT_BYTES * entryCount + i
    return nameIdx!!.get(off).toInt() and WORD_BYTE_MASK
}

/**
 * Byte range of the [wordIdx]th whitespace-separated word of the name at [off], or
 * null when the name has fewer words. Must match `word_at` in `poi_side.rs`.
 */
private fun PoiIndex.Mapped.wordRange(off: Int, wordIdx: Int): IntRange? {
    if (off < 0 || off >= namesLen) return null
    var i = off
    var idx = 0
    while (hasNameByte(i)) {
        i = skipSpaces(i)
        if (!hasNameByte(i)) break
        val start = i
        i = skipWord(i)
        if (idx == wordIdx) return start until i
        idx++
    }
    return null
}

/** Whether [i] is still inside the NUL-terminated name. */
private fun PoiIndex.Mapped.hasNameByte(i: Int): Boolean =
    i < namesLen && names.get(i).toInt() != 0

/** Advance past whitespace within the name. */
private fun PoiIndex.Mapped.skipSpaces(i: Int): Int {
    var j = i
    while (hasNameByte(j) && isAsciiSpaceW(names.get(j))) j++
    return j
}

/** Advance past one word within the name. */
private fun PoiIndex.Mapped.skipWord(i: Int): Int {
    var j = i
    while (hasNameByte(j) && !isAsciiSpaceW(names.get(j))) j++
    return j
}

private fun PoiIndex.Mapped.entryWord(i: Int): IntRange? =
    wordRange(nameOff(entryOrdinal(i)), entryWordIdx(i))

/** ASCII-lowercased byte compare of the word at [range] against [key]. */
private fun PoiIndex.Mapped.compareWord(range: IntRange, key: ByteArray): Int {
    val len = range.last - range.first + 1
    for (k in 0 until minOf(len, key.size)) {
        val d = asciiLowerW(names.get(range.first + k)) - (key[k].toInt() and WORD_BYTE_MASK)
        if (d != 0) return d
    }
    return len - key.size
}

private fun PoiIndex.Mapped.wordStartsWith(range: IntRange, key: ByteArray): Boolean {
    if (range.last - range.first + 1 < key.size) return false
    for (k in key.indices) {
        if (asciiLowerW(names.get(range.first + k)) != (key[k].toInt() and WORD_BYTE_MASK)) return false
    }
    return true
}

/** First entry whose word is >= [key], or [entryCount]. */
private fun PoiIndex.Mapped.lowerBoundWord(key: ByteArray): Int {
    var lo = 0
    var hi = entryCount
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val w = entryWord(mid)
        // A word we cannot resolve sorts first, so the search steps past it rather
        // than stalling on a name the index disagrees with.
        if (w == null || compareWord(w, key) < 0) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * Ordinals whose name has a word starting with [key], each paired with whether the
 * match was on the name's *first* word.
 *
 * Binary search plus a walk of the matching range, so the cost is the number of
 * matches rather than the size of the name pool.
 */
internal fun PoiIndex.Mapped.wordPrefixMatches(key: ByteArray, cap: Int): List<IntArray> {
    if (nameIdx == null || entryCount == 0) return emptyList()
    val out = ArrayList<IntArray>()
    val seen = HashSet<Int>()
    var i = lowerBoundWord(key)
    while (i < entryCount && out.size < cap && matchesAt(i, key)) {
        val ordinal = entryOrdinal(i)
        // A name can match on more than one word ("Pizza Pizza"); the better rank
        // wins, and the index lists word 0 first within one name.
        if (seen.add(ordinal)) out.add(intArrayOf(ordinal, entryWordIdx(i)))
        i++
    }
    return out
}

/** Whether entry [i]'s word still has [key] as a prefix (null word sorts out). */
private fun PoiIndex.Mapped.matchesAt(i: Int, key: ByteArray): Boolean {
    val w = entryWord(i) ?: return false
    return wordStartsWith(w, key)
}

private class WordRanked(val record: PoiIndex.PoiRecord, val rank: Int, val distSq: Double)

private class Ranked(val record: PoiIndex.PoiRecord, val rank: Int, val distSq: Double)

internal fun searchByScan(
    m: PoiIndex.Mapped,
    query: String,
    nearLat: Double,
    nearLon: Double,
    limit: Int,
): List<PoiIndex.PoiRecord> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()

    // Pass 1: walk the name pool once, recording matching offsets and their
    // rank (0 = prefix match, 1 = substring). Decoding each unique name once
    // is the dedup win the side-file layout is designed for.
    val matchRank = matchNamePool(m, q)
    if (matchRank.isEmpty()) return emptyList()

    // Pass 2: scan records, keeping those whose name offset matched.
    val out = collectScanMatches(m, matchRank, nearLat, nearLon)
    out.sortWith(compareBy({ it.rank }, { it.distSq }))
    return out.take(limit).map { it.record }
}

private fun matchNamePool(m: PoiIndex.Mapped, q: String): Map<Int, Int> {
    val matchRank = HashMap<Int, Int>()
    var pos = 0
    while (pos < m.namesLen) {
        var end = pos
        while (end < m.namesLen && m.names.get(end).toInt() != 0) end++
        if (end > pos) {
            rankName(m.nameAt(pos), q)?.let { matchRank[pos] = it }
        }
        pos = end + 1
    }
    return matchRank
}

/** Rank of [name] against the query (0 = prefix, 1 = substring), or null. */
private fun rankName(name: String?, q: String): Int? {
    if (name == null) return null
    val lower = name.lowercase()
    if (lower.startsWith(q)) return 0
    if (lower.contains(q)) return 1
    return null
}

private fun collectScanMatches(
    m: PoiIndex.Mapped,
    matchRank: Map<Int, Int>,
    nearLat: Double,
    nearLon: Double,
): ArrayList<Ranked> {
    val out = ArrayList<Ranked>(minOf(PoiIndex.CANDIDATE_CAP, m.count))
    var i = 0
    while (i < m.count && out.size < PoiIndex.CANDIDATE_CAP) {
        scanMatchAt(m, i, matchRank, nearLat, nearLon)?.let { out.add(it) }
        i++
    }
    return out
}

private fun scanMatchAt(
    m: PoiIndex.Mapped,
    i: Int,
    matchRank: Map<Int, Int>,
    nearLat: Double,
    nearLon: Double,
): Ranked? {
    val off = m.nameOff(i)
    val rank = matchRank[off] ?: return null
    val latE7 = m.latE7(i)
    val lonE7 = m.lonE7(i)
    return Ranked(
        PoiIndex.PoiRecord(latE7, lonE7, m.type(i), m.nameAt(off) ?: "", i),
        rank,
        PoiIndex.distanceSq(
            latE7 * PoiIndex.E7_TO_DEGREES,
            lonE7 * PoiIndex.E7_TO_DEGREES,
            nearLat,
            nearLon,
        ),
    )
}

internal fun searchByWordIndex(
    m: PoiIndex.Mapped,
    query: String,
    nearLat: Double,
    nearLon: Double,
    limit: Int,
    candidateCap: Int,
): List<PoiIndex.PoiRecord> {
    val key = poiQueryKey(query.trim())
    if (key.isEmpty()) return emptyList()
    val out = ArrayList<WordRanked>()
    for (hit in m.wordPrefixMatches(key, candidateCap)) {
        val (ordinal, wordIdx) = hit
        // Matching the name's first word is the indexed equivalent of the scan's
        // "starts with", and ranks the same way.
        val rank = if (wordIdx == 0) 0 else 1
        out.add(
            WordRanked(
                m.record(ordinal),
                rank,
                PoiIndex.distanceSq(
                    nearLat,
                    nearLon,
                    m.latE7(ordinal) * PoiIndex.E7_TO_DEGREES,
                    m.lonE7(ordinal) * PoiIndex.E7_TO_DEGREES,
                ),
            )
        )
    }
    out.sortWith(compareBy({ it.rank }, { it.distSq }))
    return out.take(limit).map { it.record }
}
