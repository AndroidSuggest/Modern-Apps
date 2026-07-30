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
 */
class Dictionary private constructor(
    private val words: List<String>,
    private val freqs: IntArray,
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
        if (prefix.isBlank()) return emptyList()
        val lower = prefix.lowercase()
        var i = lowerBound(lower)
        // Collect (index) of all words that start with the prefix, then rank by frequency.
        val matches = ArrayList<Int>()
        while (i < words.size && words[i].startsWith(lower)) {
            matches.add(i)
            i++
        }
        return matches
            .sortedByDescending { freqs[it] }
            .take(limit)
            .map { capitalizeLike(prefix, words[it]) }
    }

    /**
     * A best correction for a misspelled [word], or null if none is confident. Considers
     * dictionary words within edit distance 1, picking the most frequent. Kept deliberately
     * simple/offline; a full scan with a cheap length pre-filter is fine for a starter list.
     */
    fun autocorrect(word: String): String? {
        if (word.length < 2) return null
        val lower = word.lowercase()
        if (contains(lower)) return null
        var best = -1
        var bestFreq = -1
        for (idx in words.indices) {
            val cand = words[idx]
            if (kotlin.math.abs(cand.length - lower.length) > 1) continue
            if (!withinEditDistance1(lower, cand)) continue
            if (freqs[idx] > bestFreq) {
                bestFreq = freqs[idx]
                best = idx
            }
        }
        return if (best >= 0) capitalizeLike(word, words[best]) else null
    }

    // --- internals ---

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
        val EMPTY = Dictionary(emptyList(), IntArray(0))

        /** Load and index the bundled word list off the main thread. */
        suspend fun load(context: Context): Dictionary = withContext(Dispatchers.IO) {
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
            // De-dup keeping the highest frequency, then sort alphabetically for binary search.
            val byWord = LinkedHashMap<String, Int>()
            for ((w, f) in entries) {
                val prev = byWord[w]
                if (prev == null || f > prev) byWord[w] = f
            }
            val sorted = byWord.entries.sortedBy { it.key }
            Dictionary(sorted.map { it.key }, IntArray(sorted.size) { sorted[it].value })
        }

        /** Apply [sample]'s leading capitalization to [word]. */
        private fun capitalizeLike(sample: String, word: String): String = when {
            sample.isNotEmpty() && sample.all { it.isUpperCase() } && sample.length > 1 -> word.uppercase()
            sample.isNotEmpty() && sample[0].isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }

        /** True iff Levenshtein distance between [a] and [b] is at most 1. */
        private fun withinEditDistance1(a: String, b: String): Boolean {
            val la = a.length
            val lb = b.length
            when (la - lb) {
                0 -> {
                    var diff = 0
                    for (i in 0 until la) if (a[i] != b[i]) {
                        diff++
                        if (diff > 1) return false
                    }
                    return true
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
