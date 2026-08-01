package com.vayunmathur.keyboard.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline word dictionary backing the suggestion strip and autocorrect.
 *
 * Words are kept lowercase, sorted alphabetically in a parallel [words]/[freqs] pair so
 * prefix lookups are a binary search + short forward scan (a lightweight stand-in for a
 * trie that also gives O(log n) `contains`). Ranking is purely by frequency. The whole
 * thing is loaded once, off the main thread, from `assets/dict/words_en.txt`.
 *
 * A frequency of 0 means **known but never offered**: [contains] accepts the word, so it
 * is not treated as a misspelling and [autocorrect] will not rewrite it, but it is never
 * returned by [suggestions] and never proposed as a correction of something else. The
 * generator files profanity this way — dropping those words outright would make the
 * keyboard quietly "correct" them into whatever else is within one edit.
 */
class Dictionary private constructor(
    private val words: List<String>,
    private val freqs: IntArray,
    /**
     * Indices into [words] bucketed by word length, so [autocorrect] can look at just the
     * three lengths that can be within one edit instead of scanning the whole list. Index
     * `n` holds every word of length `n`; short trailing lengths may be empty.
     */
    private val byLength: Array<IntArray>,
) {
    /** True if [word] (any case) is a known dictionary word. */
    fun contains(word: String): Boolean {
        if (word.isEmpty()) return false
        return indexOf(word.lowercase()) >= 0
    }

    /**
     * Up to [limit] completions of [prefix], most frequent first, capitalized to match how
     * the user typed the prefix. Returns empty for a blank prefix.
     */
    fun suggestions(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isBlank() || limit <= 0) return emptyList()
        val lower = prefix.lowercase()

        // Runs on every keystroke, and a one-letter prefix matches thousands of words, so
        // this keeps a fixed top-[limit] instead of collecting every match and sorting it.
        val bestIdx = IntArray(limit) { -1 }
        val bestFreq = IntArray(limit)
        var i = lowerBound(lower)
        while (i < words.size && words[i].startsWith(lower)) {
            offer(bestIdx, bestFreq, i, freqs[i])
            i++
        }

        return bestIdx.filter { it >= 0 }.map { capitalizeLike(prefix, words[it]) }
    }

    /**
     * A best correction for a misspelled [word], or null if none is confident. Considers
     * dictionary words within edit distance 1, picking the most frequent.
     */
    fun autocorrect(word: String): String? {
        if (word.length < 2) return null
        val lower = word.lowercase()
        if (contains(lower)) return null

        var best = -1
        var bestFreq = 0 // 0 also excludes never-offered words without a second check.
        // Edit distance 1 can only change the length by one, so these are the only three
        // buckets worth looking at — roughly 5% of the list rather than all of it.
        for (len in (lower.length - 1)..(lower.length + 1)) {
            if (len < 1 || len >= byLength.size) continue
            for (idx in byLength[len]) {
                if (freqs[idx] <= bestFreq) continue // includes the freq == 0 exclusion
                if (!withinEditDistance1(lower, words[idx])) continue
                bestFreq = freqs[idx]
                best = idx
            }
        }
        return if (best >= 0) capitalizeLike(word, words[best]) else null
    }

    // --- internals ---

    /**
     * Insert [idx]/[freq] into a descending fixed-size top-k, dropping the smallest. Words
     * at frequency 0 are never offered, so they are refused outright.
     */
    private fun offer(bestIdx: IntArray, bestFreq: IntArray, idx: Int, freq: Int) {
        if (freq <= 0) return
        var pos = bestIdx.size
        while (pos > 0 && (bestIdx[pos - 1] < 0 || bestFreq[pos - 1] < freq)) pos--
        if (pos == bestIdx.size) return
        for (shift in bestIdx.size - 1 downTo pos + 1) {
            bestIdx[shift] = bestIdx[shift - 1]
            bestFreq[shift] = bestFreq[shift - 1]
        }
        bestIdx[pos] = idx
        bestFreq[pos] = freq
    }

    /** Index of an exact match, or -1. */
    private fun indexOf(w: String): Int {
        var lo = 0
        var hi = words.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = words[mid].compareTo(w)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** First index whose word is >= [w] (binary search lower bound). */
    private fun lowerBound(w: String): Int {
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < w) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** An empty dictionary used before loading completes. */
        val EMPTY = Dictionary(emptyList(), IntArray(0), emptyArray())

        /** Load and index the bundled word list off the main thread. */
        suspend fun load(context: Context): Dictionary = withContext(Dispatchers.IO) {
            build(readAsset(context))
        }

        /**
         * Index an in-memory word list. Exists so the ranking, edit-distance and
         * never-offered rules can be unit tested without an Android [Context] or the
         * 42k-entry asset.
         */
        internal fun fromEntries(entries: List<Pair<String, Int>>): Dictionary = build(entries)

        private fun readAsset(context: Context): List<Pair<String, Int>> {
            val entries = ArrayList<Pair<String, Int>>()
            runCatching {
                context.assets.open("dict/words_en.txt").bufferedReader().useLines { lines ->
                    // Fallback frequency = position from the top (file is ordered most-common first),
                    // so common words rank above rarer ones even without an explicit column.
                    var order = 0
                    for (raw in lines) {
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) continue
                        val parts = line.split('\t')
                        val word = parts[0].trim().lowercase()
                        if (word.isEmpty()) continue
                        val freq = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: (1_000_000 - order)
                        entries.add(word to freq)
                        order++
                    }
                }
            }
            return entries
        }

        private fun build(entries: List<Pair<String, Int>>): Dictionary {
            // De-dup keeping the highest frequency, then sort alphabetically for binary search.
            val byWord = LinkedHashMap<String, Int>()
            for ((w, f) in entries) {
                val prev = byWord[w]
                if (prev == null || f > prev) byWord[w] = f
            }
            val sorted = byWord.entries.sortedBy { it.key }
            val words = sorted.map { it.key }
            val freqs = IntArray(sorted.size) { sorted[it].value }

            // Bucket indices by length in one pass: count, allocate exactly, then fill.
            val maxLen = words.maxOfOrNull { it.length } ?: 0
            val counts = IntArray(maxLen + 1)
            for (w in words) counts[w.length]++
            val byLength = Array(maxLen + 1) { IntArray(counts[it]) }
            val fill = IntArray(maxLen + 1)
            for (i in words.indices) {
                val len = words[i].length
                byLength[len][fill[len]++] = i
            }

            return Dictionary(words, freqs, byLength)
        }

        /** Apply [sample]'s leading capitalization to [word]. */
        private fun capitalizeLike(sample: String, word: String): String = when {
            sample.isNotEmpty() && sample.all { it.isUpperCase() } && sample.length > 1 -> word.uppercase()
            sample.isNotEmpty() && sample[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }

        /**
         * True iff [a] is at most one edit from [b], counting a swap of two adjacent
         * characters as a single edit (Damerau-Levenshtein rather than plain Levenshtein).
         *
         * Transpositions have to count as one edit or the most common typos of all go
         * uncorrected: "teh", "recieve" and "thsi" are each two plain-Levenshtein edits
         * from the intended word, so a strict Levenshtein-1 rule either leaves them alone
         * or — worse — picks whatever unrelated word happens to be one substitution away
         * ("teh" -> "ten", "recieve" -> "relieve").
         */
        private fun withinEditDistance1(a: String, b: String): Boolean {
            val la = a.length
            val lb = b.length
            when (la - lb) {
                0 -> {
                    // Collect at most the first two mismatches; more than two can never be
                    // one edit, and exactly two is only reachable via a transposition.
                    var first = -1
                    var second = -1
                    for (i in 0 until la) {
                        if (a[i] == b[i]) continue
                        when {
                            first < 0 -> first = i
                            second < 0 -> second = i
                            else -> return false
                        }
                    }
                    if (second < 0) return true // identical, or one substitution
                    return second == first + 1 &&
                        a[first] == b[second] && a[second] == b[first]
                }
                1 -> return isOneInsertion(a, b) // a has the extra char
                -1 -> return isOneInsertion(b, a) // b has the extra char
                else -> return false
            }
        }

        /** True iff [longer] equals [shorter] with exactly one extra character inserted. */
        private fun isOneInsertion(longer: String, shorter: String): Boolean {
            var i = 0
            var j = 0
            var skipped = false
            while (i < longer.length && j < shorter.length) {
                if (longer[i] == shorter[j]) {
                    i++; j++
                } else {
                    if (skipped) return false
                    skipped = true
                    i++ // skip the extra character in the longer string
                }
            }
            return true
        }
    }
}
