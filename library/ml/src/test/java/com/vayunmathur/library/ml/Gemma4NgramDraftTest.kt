package com.vayunmathur.library.ml

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * [Gemma4NgramDraft]: draft ranking from canned histories, the greedy accept rule, and the
 * degenerate inputs a generate loop would hand it.
 *
 * Pure and JVM-hosted. The `score` stubs stand in for the native parallel-scoring pass the
 * ship gate requires; they return canned argmaxes so the accept loop is tested without a model.
 */
class Gemma4NgramDraftTest {

    @Test
    fun `a repeated trigram predicts its continuation`() {
        val draft = Gemma4NgramDraft(order = 3)
        val history = intArrayOf(7, 8, 9, 1, 7, 8, 9, 2, 7, 8)

        assertContentEquals(intArrayOf(9), draft.draft(history, limit = 4))
    }

    @Test
    fun `continuations rank by frequency`() {
        val draft = Gemma4NgramDraft(order = 2)
        val history = intArrayOf(5, 1, 5, 2, 5, 1, 5, 2, 5, 1, 5)

        assertContentEquals(intArrayOf(1, 2), draft.draft(history, limit = 4))
    }

    @Test
    fun `an unseen context drafts nothing`() {
        val draft = Gemma4NgramDraft(order = 3)

        assertContentEquals(IntArray(0), draft.draft(intArrayOf(1, 2, 3, 4), limit = 4))
    }

    @Test
    fun `a history shorter than the context drafts nothing`() {
        assertContentEquals(IntArray(0), Gemma4NgramDraft(order = 4).draft(intArrayOf(1, 2), 4))
    }

    @Test
    fun `non-positive limits and empty histories draft nothing`() {
        val draft = Gemma4NgramDraft()

        assertContentEquals(IntArray(0), draft.draft(intArrayOf(1, 2, 3), limit = 0))
        assertContentEquals(IntArray(0), draft.draft(IntArray(0), limit = 4))
    }

    @Test
    fun `order zero is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { Gemma4NgramDraft(order = 0) }
    }

    @Test
    fun `unigrams fall back to raw frequencies`() {
        val draft = Gemma4NgramDraft(order = 1)
        val history = intArrayOf(3, 1, 3, 2, 3, 1, 3)

        val drafted = draft.draft(history, limit = 1)

        assertContentEquals(intArrayOf(3), drafted)
    }

    @Test
    fun `accept keeps the matching head and stops at the first mismatch`() {
        val draft = Gemma4NgramDraft()
        // The stub target echoes 10 -> 11 -> 12, then diverges.
        val score = { fed: Int -> if (fed < 12) fed + 1 else 99 }

        assertEquals(3, draft.accepted(prefix = 10, drafted = intArrayOf(11, 12, 99), score))
        assertEquals(1, draft.accepted(prefix = 10, drafted = intArrayOf(11, 42, 43), score))
        assertEquals(0, draft.accepted(prefix = 10, drafted = intArrayOf(41, 42), score))
    }

    @Test
    fun `accept of nothing is nothing`() {
        assertEquals(0, Gemma4NgramDraft().accepted(prefix = 1, drafted = IntArray(0)) { 1 })
    }

    @Test
    fun `the draft-accept round trip recovers a repetition`() {
        // The loop as it would run: draft from the conversation's own statistics, accept against
        // the target. A repeated `7, 8, 9` run drafts 9 after `7, 8` and the agreeing target
        // keeps it.
        val draft = Gemma4NgramDraft(order = 3)
        val history = intArrayOf(7, 8, 9, 4, 7, 8, 9, 4, 7, 8)
        val drafted = draft.draft(history, limit = 3)

        assertTrue(drafted.isNotEmpty(), "the repetition should draft something")
        val kept = draft.accepted(prefix = 8, drafted = drafted) { fed ->
            if (fed == 8) 9 else fed
        }

        assertEquals(1, kept)
    }
}
