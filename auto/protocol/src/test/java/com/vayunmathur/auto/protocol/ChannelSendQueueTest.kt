package com.vayunmathur.auto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The send-queue contract the channel owners rely on: control always jumps the line,
 * service channels round-robin so a video flood cannot starve acks or key bindings,
 * and each channel stays FIFO.
 */
class ChannelSendQueueTest {

    private fun send(channelId: Int, byte: Byte = 0): QueuedSend =
        QueuedSend(channelId, byteArrayOf(byte), isControl = false, encrypted = true)

    @Test
    fun `control drains before video even when video queued first`() {
        val queue = ChannelSendQueue()
        queue.enqueue(send(GalService.VIDEO_SINK.id, 1))
        queue.enqueue(send(ChannelSendQueue.CONTROL_CHANNEL_ID, 2))

        assertEquals(2, queue.poll()!!.payload.single())
        assertEquals(1, queue.poll()!!.payload.single())
        assertNull(queue.poll())
    }

    @Test
    fun `service channels round-robin so acks never head-block behind video`() {
        val queue = ChannelSendQueue()
        repeat(5) { queue.enqueue(send(GalService.VIDEO_SINK.id, 10)) }
        queue.enqueue(send(GalService.AUDIO_SOURCE.id, 20))

        // Video first (it queued first), then the mic ack, then video again.
        assertEquals(10, queue.poll()!!.payload.single())
        assertEquals(20, queue.poll()!!.payload.single())
        assertEquals(10, queue.poll()!!.payload.single())
    }

    @Test
    fun `each channel stays FIFO`() {
        val queue = ChannelSendQueue()
        queue.enqueue(send(GalService.INPUT_SOURCE.id, 1))
        queue.enqueue(send(GalService.VIDEO_SINK.id, 9))
        queue.enqueue(send(GalService.INPUT_SOURCE.id, 2))

        // Round-robin: input(1), video(9), input(2) — input's own order preserved.
        assertEquals(1, queue.poll()!!.payload.single())
        assertEquals(9, queue.poll()!!.payload.single())
        assertEquals(2, queue.poll()!!.payload.single())
    }

    @Test
    fun `a drained channel leaves the rotation and rejoins on enqueue`() {
        val queue = ChannelSendQueue()
        queue.enqueue(send(2, 1))
        queue.enqueue(send(8, 2))
        assertEquals(1, queue.poll()!!.payload.single())
        assertEquals(2, queue.poll()!!.payload.single())
        assertTrue(queue.isEmpty())

        queue.enqueue(send(8, 3))
        assertEquals(3, queue.poll()!!.payload.single())
        assertNull(queue.poll())
    }

    @Test
    fun `pending counts cover every channel`() {
        val queue = ChannelSendQueue()
        assertTrue(queue.isEmpty())
        assertEquals(0, queue.pendingCount())
        assertEquals(0, queue.pendingBytes())

        queue.enqueue(send(0, 1))
        queue.enqueue(QueuedSend(2, ByteArray(100), isControl = false, encrypted = true))

        assertFalse(queue.isEmpty())
        assertEquals(2, queue.pendingCount())
        assertEquals(101, queue.pendingBytes())
    }

    @Test
    fun `clear drops everything unsent for the next session`() {
        val queue = ChannelSendQueue()
        queue.enqueue(send(0))
        queue.enqueue(send(2))
        queue.enqueue(send(6))

        queue.clear()

        assertTrue(queue.isEmpty())
        assertNull(queue.poll())
    }
}
