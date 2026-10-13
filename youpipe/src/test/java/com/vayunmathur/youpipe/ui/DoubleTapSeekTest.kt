package com.vayunmathur.youpipe.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DoubleTapSeekTest {

    @Test
    fun `forward seek advances by the skip amount`() {
        assertEquals(70_000L, doubleTapSeekTarget(60_000L, 300_000L, forward = true))
    }

    @Test
    fun `backward seek retreats by the skip amount`() {
        assertEquals(50_000L, doubleTapSeekTarget(60_000L, 300_000L, forward = false))
    }

    @Test
    fun `backward seek near start floors at zero instead of restarting from duration`() {
        assertEquals(0L, doubleTapSeekTarget(5_000L, 300_000L, forward = false))
    }

    @Test
    fun `forward seek near end clamps at duration`() {
        assertEquals(300_000L, doubleTapSeekTarget(295_000L, 300_000L, forward = true))
    }

    @Test
    fun `unknown duration leaves forward seeks unbounded`() {
        assertEquals(310_000L, doubleTapSeekTarget(300_000L, -1L, forward = true))
    }

    @Test
    fun `mid-video skips never land near zero`() {
        // The #809 regression: a stale captured position sought from ~0 and the video restarted.
        val target = doubleTapSeekTarget(120_000L, 300_000L, forward = true)
        assertEquals(130_000L, target)
    }
}
