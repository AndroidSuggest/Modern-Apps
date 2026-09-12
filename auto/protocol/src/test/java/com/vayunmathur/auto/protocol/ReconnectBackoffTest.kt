package com.vayunmathur.auto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reconnect policy: exponential growth from the base, a hard cap, and a reset on
 * success. Jitter is injected so the caps assert exactly.
 */
class ReconnectBackoffTest {

    /** Always draws the maximum, so each delay equals its cap. */
    private fun maxBackoff() = ReconnectBackoff(jitter = { bound -> bound - 1 })

    @Test
    fun `first failure waits the base delay`() {
        assertEquals(500, maxBackoff().onFailure())
    }

    @Test
    fun `delays double until the cap`() {
        val backoff = maxBackoff()

        assertEquals(500, backoff.onFailure())
        assertEquals(1_000, backoff.onFailure())
        assertEquals(2_000, backoff.onFailure())
        assertEquals(4_000, backoff.onFailure())
        assertEquals(8_000, backoff.onFailure())
        assertEquals(16_000, backoff.onFailure())
        assertEquals(30_000, backoff.onFailure())
        assertEquals(30_000, backoff.onFailure())
    }

    @Test
    fun `long streaks saturate at the cap without overflowing`() {
        val backoff = maxBackoff()
        repeat(100) { backoff.onFailure() }

        assertEquals(30_000, backoff.onFailure())
        assertEquals(101, backoff.failures)
    }

    @Test
    fun `success resets the streak to the base`() {
        val backoff = maxBackoff()
        backoff.onFailure()
        backoff.onFailure()

        backoff.onSuccess()

        assertEquals(0, backoff.failures)
        assertEquals(500, backoff.onFailure())
    }

    @Test
    fun `delays stay inside zero and the cap whatever the draw`() {
        val minBackoff = ReconnectBackoff(jitter = { 0 })
        repeat(10) {
            assertEquals(0, minBackoff.onFailure())
        }

        val custom = ReconnectBackoff(
            baseDelayMs = 100,
            maxDelayMs = 250,
            jitter = { bound -> bound - 1 },
        )
        assertEquals(100, custom.onFailure())
        assertEquals(200, custom.onFailure())
        assertEquals(250, custom.onFailure())
        assertTrue(custom.onFailure() <= 250)
    }
}
