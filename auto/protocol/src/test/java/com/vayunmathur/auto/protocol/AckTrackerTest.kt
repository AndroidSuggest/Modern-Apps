package com.vayunmathur.auto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The 0x8004 ack counter wraps mod 256; the tracker must unwrap it into a
 * monotone sequence rather than freezing at 255 or stepping backwards.
 */
class AckTrackerTest {

    @Test
    fun `starts empty`() {
        assertNull(AckTracker().lastUnwrapped)
    }

    @Test
    fun `advances in order`() {
        val tracker = AckTracker()
        assertEquals(3, tracker.onAck(3))
        assertEquals(118, tracker.onAck(118))
        assertEquals(118, tracker.lastUnwrapped)
    }

    @Test
    fun `wraps past 255 instead of regressing`() {
        val tracker = AckTracker()
        assertEquals(254, tracker.onAck(254))
        assertEquals(255, tracker.onAck(255))
        // Wrap: raw 0 continues the sequence at 256, and 1 follows at 257.
        assertEquals(256, tracker.onAck(0))
        assertEquals(257, tracker.onAck(1))
        assertEquals(257, tracker.lastUnwrapped)
    }

    @Test
    fun `stale arrivals hold the line`() {
        val tracker = AckTracker()
        assertEquals(5, tracker.onAck(5))
        // A duplicate and a reordered older value change nothing.
        assertEquals(5, tracker.onAck(5))
        assertEquals(5, tracker.onAck(2))
        assertEquals(5, tracker.lastUnwrapped)
    }

    @Test
    fun `reset clears the sequence`() {
        val tracker = AckTracker()
        tracker.onAck(10)
        tracker.reset()
        assertNull(tracker.lastUnwrapped)
        assertEquals(3, tracker.onAck(3))
    }
}
