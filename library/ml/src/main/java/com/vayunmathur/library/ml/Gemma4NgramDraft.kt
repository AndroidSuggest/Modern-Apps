package com.vayunmathur.library.ml

/**
 * An n-gram drafter for a speculative-decoding spike, deliberately unwired.
 *
 * # What this is and why it is not called anywhere
 *
 * Speculative decoding drafts K tokens cheaply, scores them against the target model in one
 * parallel pass, and keeps the accepted head. The win needs that parallel scoring pass; this
 * runtime's JNI boundary is one token per crossing (`stepGemma4`, `logitsGemma4`), so scoring K
 * drafts costs K crossings - the same serial work as generating them, with the draft overhead
 * on top. A Kotlin-only prototype wired into `generate()` would therefore only demonstrate a
 * slowdown, not a speedup.
 *
 * So this is the half that CAN be evaluated without native support: the drafter. It proposes
 * tokens from the conversation's own n-gram statistics - repetition is common in chat (lists,
 * echoed names, tool syntax) - and the unit tests pin its hit behaviour on canned histories.
 * The ship/wont-fix gate is explicit: ship means adding a native parallel-scoring entry point
 * (one crossing for K tokens) and wiring the accept loop into `generate()`; until measured
 * tokens/sec before/after via `Gemma4Engine.ensureLoaded + benchmark()` shows a win, this stays
 * a utility with tests.
 *
 * Pure and JVM-hosted, like `sampleGemma4Logits`: histories in, predictions out, no model.
 *
 * @param order the n-gram order: a context of `order - 1` trailing tokens predicts the next.
 * Must be >= 1; order 1 is unigram frequencies.
 * @param history the token ids seen so far, oldest first.
 */
class Gemma4NgramDraft(val order: Int = 3) {
    init {
        require(order >= 1) { "n-gram order must be >= 1, was $order" }
    }

    /**
     * Up to [limit] predicted next tokens for the context ending [history], most likely first.
     *
     * Counts how each occurrence of the trailing `order - 1` ids continues in [history] and
     * ranks continuations by frequency. Empty when the context never occurs (nothing to learn
     * from) or when [history] is shorter than the context it needs - both ordinary, both mean
     * "fall back to a plain step".
     */
    fun draft(history: IntArray, limit: Int): IntArray {
        if (limit <= 0 || history.isEmpty()) return IntArray(0)
        val width = order - 1
        if (history.size < width) return IntArray(0)
        val context = history.copyOfRange(history.size - width, history.size)
        val continuations = LinkedHashMap<Int, Int>()
        var at = 0
        while (at + width < history.size) {
            var matches = true
            for (offset in 0 until width) {
                if (history[at + offset] != context[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                val next = history[at + width]
                continuations[next] = (continuations[next] ?: 0) + 1
            }
            at++
        }
        return continuations.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
            .toIntArray()
    }

    /**
     * How many of [drafted] the target model would accept after [prefix], scored greedily.
     *
     * The accept rule of speculative decoding's greedy path: walk the drafts in order, keep each
     * while it equals the target's own argmax at that position, stop at the first mismatch. The
     * [score] callback is the target model's next-token function - in production a native
     * parallel pass, in tests a stub - taking the token fed at each position and returning the
     * argmax that follows it.
     *
     * Returns 0 when the first draft already mismatches, which means "generate that position
     * normally and try again later".
     */
    fun accepted(prefix: Int, drafted: IntArray, score: (Int) -> Int): Int {
        var kept = 0
        var fed = prefix
        for (candidate in drafted) {
            if (score(fed) != candidate) break
            kept++
            fed = candidate
        }
        return kept
    }
}
