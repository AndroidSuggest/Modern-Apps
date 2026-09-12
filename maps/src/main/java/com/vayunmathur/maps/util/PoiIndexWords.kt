package com.vayunmathur.maps.util

/**
 * The word-index half of [PoiIndex]: prefix search over `poi_name_index.bin`.
 *
 * Split out so PoiIndex.kt stays under the FileLength limit. These are `internal`
 * extensions on `PoiIndex.Mapped` — which therefore has to be visible outside the
 * file — plus the indexed search entry point that [PoiIndex.searchByName]
 * delegates to.
 */

private fun asciiLowerW(b: Byte): Int {
    val v = b.toInt() and 0xFF
    return if (v >= 'A'.code && v <= 'Z'.code) v + 32 else v
}

private fun isAsciiSpaceW(b: Byte): Boolean {
    val v = b.toInt() and 0xFF
    return v == ' '.code || v == '\t'.code || v == '\n'.code || v == '\r'.code ||
        v == 0x0B || v == 0x0C
}

/** A query as the index's sort key: UTF-8 bytes, ASCII-lowercased. */
internal fun poiQueryKey(query: String): ByteArray {
    val raw = query.toByteArray(Charsets.UTF_8)
    return ByteArray(raw.size) { asciiLowerW(raw[it]).toByte() }
}

private fun PoiIndex.Mapped.entryOrdinal(i: Int): Int =
    nameIdx!!.getInt(PoiIndex.NAME_INDEX_HEADER_BYTES + 4 * i)

private fun PoiIndex.Mapped.entryWordIdx(i: Int): Int =
    nameIdx!!.get(PoiIndex.NAME_INDEX_HEADER_BYTES + 4 * entryCount + i).toInt() and 0xFF

/**
 * Byte range of the [wordIdx]th whitespace-separated word of the name at [off], or
 * null when the name has fewer words. Must match `word_at` in `poi_side.rs`.
 */
private fun PoiIndex.Mapped.wordRange(off: Int, wordIdx: Int): IntRange? {
    if (off < 0 || off >= namesLen) return null
    var i = off
    var idx = 0
    while (i < namesLen && names.get(i).toInt() != 0) {
        while (i < namesLen && names.get(i).toInt() != 0 && isAsciiSpaceW(names.get(i))) i++
        if (i >= namesLen || names.get(i).toInt() == 0) break
        val start = i
        while (i < namesLen && names.get(i).toInt() != 0 && !isAsciiSpaceW(names.get(i))) i++
        if (idx == wordIdx) return start until i
        idx++
    }
    return null
}

private fun PoiIndex.Mapped.entryWord(i: Int): IntRange? =
    wordRange(nameOff(entryOrdinal(i)), entryWordIdx(i))

/** ASCII-lowercased byte compare of the word at [range] against [key]. */
private fun PoiIndex.Mapped.compareWord(range: IntRange, key: ByteArray): Int {
    val len = range.last - range.first + 1
    for (k in 0 until minOf(len, key.size)) {
        val d = asciiLowerW(names.get(range.first + k)) - (key[k].toInt() and 0xFF)
        if (d != 0) return d
    }
    return len - key.size
}

private fun PoiIndex.Mapped.wordStartsWith(range: IntRange, key: ByteArray): Boolean {
    if (range.last - range.first + 1 < key.size) return false
    for (k in key.indices) {
        if (asciiLowerW(names.get(range.first + k)) != (key[k].toInt() and 0xFF)) return false
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
    while (i < entryCount && out.size < cap) {
        val w = entryWord(i) ?: break
        if (!wordStartsWith(w, key)) break
        val ordinal = entryOrdinal(i)
        // A name can match on more than one word ("Pizza Pizza"); the better rank
        // wins, and the index lists word 0 first within one name.
        if (seen.add(ordinal)) out.add(intArrayOf(ordinal, entryWordIdx(i)))
        i++
    }
    return out
}

private class WordRanked(val record: PoiIndex.PoiRecord, val rank: Int, val distSq: Double)

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
                PoiIndex.distanceSq(nearLat, nearLon, m.latE7(ordinal) / 1e7, m.lonE7(ordinal) / 1e7),
            )
        )
    }
    out.sortWith(compareBy({ it.rank }, { it.distSq }))
    return out.take(limit).map { it.record }
}
