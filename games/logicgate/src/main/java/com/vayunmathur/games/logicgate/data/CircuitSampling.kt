package com.vayunmathur.games.logicgate.data

/**
 * Shared input-vector sampling for the evaluator.
 *
 * Exhaustive up to [EXHAUSTIVE_BITS] inputs; beyond that a seeded random
 * sample (plus all-off / all-on / alternating anchors) capped at
 * [SAMPLE_LIMIT]. The seed is fixed so the evaluator, the truth-table view
 * and the correctness check all agree on row indices.
 */
internal object CircuitSampling {
    const val EXHAUSTIVE_BITS = 10
    const val SAMPLE_LIMIT = 256
    const val SAMPLE_SEED = 1234L
    const val HIGH_BIT_COUNT = 20
    const val MAX_SETTLE_ITERS = 30

    /** Bit, nibble and byte bus widths used across evaluator hints. */
    const val BIT = 1
    const val BUS_4 = 4
    const val BUS_8 = 8

    /** All combos to try for [bits] inputs: exhaustive or seeded sample. */
    fun combos(bits: Int): List<List<Boolean>> = vectors(bits, SAMPLE_LIMIT)

    fun vectors(totalInputBits: Int, limit: Int = SAMPLE_LIMIT): List<List<Boolean>> {
        if (totalInputBits <= EXHAUSTIVE_BITS) {
            return (0 until (1 shl totalInputBits)).map { c -> decode(c, totalInputBits) }.take(limit)
        }
        val rnd = java.util.Random(SAMPLE_SEED)
        val vs = mutableListOf<List<Boolean>>()
        vs.add(List(totalInputBits) { false })
        vs.add(List(totalInputBits) { true })
        vs.add(List(totalInputBits) { it % 2 == 0 })
        while (vs.size < limit) vs.add(List(totalInputBits) { rnd.nextBoolean() })
        return vs
    }

    /** Combo index list used by the row loop (int form, decoded per row). */
    fun comboIndices(bits: Int): List<Int> {
        if (bits <= EXHAUSTIVE_BITS) return (0 until (1 shl bits)).toList()
        val rnd = java.util.Random(SAMPLE_SEED)
        val set = LinkedHashSet<Int>()
        set.add(0)
        set.add((1 shl minOf(bits, HIGH_BIT_COUNT)) - 1)
        while (set.size < SAMPLE_LIMIT) {
            var v = 0
            for (b in 0 until minOf(bits, HIGH_BIT_COUNT)) {
                if (rnd.nextBoolean()) v = v or (1 shl b)
            }
            set.add(v)
        }
        return set.toList()
    }

    fun decodeCombo(c: Int, bits: Int): List<Boolean> {
        if (bits == 0) return emptyList()
        return List(bits) { i ->
            if (i < HIGH_BIT_COUNT) {
                ((c shr i) and 1) == 1
            } else {
                java.util.Random(c.toLong() + i).nextBoolean()
            }
        }
    }

    private fun decode(c: Int, bits: Int): List<Boolean> =
        List(bits) { i -> ((c shr i) and 1) == 1 }
}
