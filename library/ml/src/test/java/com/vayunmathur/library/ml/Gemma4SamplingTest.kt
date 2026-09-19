package com.vayunmathur.library.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [sampleGemma4Logits]: truncation to top_k, the top_p mass cut, determinism, temperature edges.
 *
 * Pure and JVM-hosted, following the `fitAudio` internal-pure pattern: no model, no device, just
 * logits in and a token out. Every case asserts against properties of the config rather than
 * hard-coded draws, so a change of RNG keeps the suite meaningful.
 */
class Gemma4SamplingTest {

    /** Five logits, best first: token 0 dominates, token 4 is negligible. */
    private fun logits() = floatArrayOf(10f, 5f, 3f, 1f, -2f)

    @Test
    fun `greedy config returns the argmax`() {
        assertEquals(0, sampleGemma4Logits(logits(), Sampling.Greedy, step = 0))
        assertEquals(0, sampleGemma4Logits(logits(), Sampling(topK = 1), step = 0))
    }

    @Test
    fun `temperature zero is greedy whatever the truncation says`() {
        assertEquals(
            0,
            sampleGemma4Logits(logits(), Sampling(topK = 64, topP = 0.95f, temperature = 0f), 0),
        )
    }

    @Test
    fun `a fixed seed reproduces the draw`() {
        val config = Sampling.Chat.copy(seed = 42L)

        assertEquals(
            sampleGemma4Logits(logits(), config, step = 7),
            sampleGemma4Logits(logits(), config, step = 7),
        )
    }

    @Test
    fun `different steps draw independently`() {
        // Twenty draws from a flat distribution must not all agree: same seed, different steps.
        val config = Sampling(topK = 5, topP = 1.0f, temperature = 100f, seed = 1L)
        val flat = floatArrayOf(1f, 1f, 1f, 1f, 1f)
        val draws = (0 until 20).map { sampleGemma4Logits(flat, config, step = it) }.toSet()

        assertTrue(draws.size > 1, "twenty independent draws from a flat pool all agreed: $draws")
    }

    @Test
    fun `top_k 1 never leaves the argmax however hot it runs`() {
        val config = Sampling(topK = 1, topP = 1.0f, temperature = 100f, seed = 9L)

        for (step in 0 until 10) {
            assertEquals(0, sampleGemma4Logits(logits(), config, step))
        }
    }

    @Test
    fun `vanishing top_p degrades to greedy rather than an empty draw`() {
        val config = Sampling(topK = 64, topP = 0.0001f, temperature = 1f, seed = 3L)

        for (step in 0 until 10) {
            assertEquals(0, sampleGemma4Logits(logits(), config, step))
        }
    }

    @Test
    fun `top_p 1 draws from the whole pool`() {
        // Flat logits, full pool: twenty draws must reach past the first entry.
        val config = Sampling(topK = 5, topP = 1.0f, temperature = 1f, seed = 5L)
        val flat = floatArrayOf(2f, 2f, 2f, 2f, 2f)
        val draws = (0 until 20).map { sampleGemma4Logits(flat, config, step = it) }.toSet()

        assertTrue(draws.size > 1, "a full-pool draw never left token 0: $draws")
    }

    @Test
    fun `an empty vocabulary returns minus one like a failed step`() {
        assertEquals(-1, sampleGemma4Logits(floatArrayOf(), Sampling.Chat, step = 0))
    }

    @Test
    fun `a single-entry vocabulary always returns it`() {
        assertEquals(0, sampleGemma4Logits(floatArrayOf(3f), Sampling.Chat, step = 0))
    }

    @Test
    fun `top_k wider than the vocabulary keeps every token`() {
        // Must not throw or clip: k applies `in 1 until size`, so an oversized k keeps all.
        val token = sampleGemma4Logits(floatArrayOf(1f, 2f), Sampling(topK = 64), step = 0)

        assertTrue(token == 0 || token == 1, "draw outside the vocabulary: $token")
    }
}
