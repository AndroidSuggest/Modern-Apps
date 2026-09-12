package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.MessagingAction
import com.vayunmathur.auto.protocol.gal.MessagingActionType
import com.vayunmathur.auto.protocol.gal.MessagingMessage
import com.vayunmathur.auto.protocol.gal.MessagingThread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the ch14 (NOTIFICATION, service 14) message set: threads, body, reply, mark-read.
 *
 * Byte-exact where the field layout is ours to define (MA sender-defined DTOs in
 * `gal/notification.proto`): a renumbered field here only breaks us against
 * ourselves, but the exact-bytes form still catches an accidental proto edit
 * the same day it lands, not at a DHU that silently ignores us.
 */
class MessagingCodecTest {

    @Test
    fun `threads encode under the THREADS type`() {
        val thread = MessagingThread.newBuilder()
            .setThreadId("t1")
            .setTitle("Ada")
            .setSender("+15551234567")
            .setLastMessageMs(1_700_000_000_000L)
            .setUnreadCount(2)
            .build()
        val (type, payload) = MessagingCodec.encodeThreads(listOf(thread))
        assertEquals(GalMessage.Notification.THREADS, type)

        val decoded = MessageCodec.decode(GalService.NOTIFICATION.id, MessageCodec.encode(type, payload))
        assertEquals(GalMessage.Notification.THREADS, decoded.type)
        val parsed = com.vayunmathur.auto.protocol.gal.MessagingThreads.parseFrom(decoded.payload)
        assertEquals(1, parsed.threadsCount)
        assertEquals("t1", parsed.getThreads(0).threadId)
        assertEquals("Ada", parsed.getThreads(0).title)
        assertEquals(2, parsed.getThreads(0).unreadCount)
    }

    @Test
    fun `a message body pins field numbers on the wire`() {
        val message = MessagingMessage.newBuilder()
            .setThreadId("t1")
            .setMessageId("m7")
            .setSender("Ada")
            .setBody("On my way")
            .setTimestampMs(42L)
            .build()
        val (type, payload) = MessagingCodec.encodeMessage(message)
        assertEquals(GalMessage.Notification.MESSAGE, type)
        assertContentEquals(
            byteArrayOf(
                0x0A, 0x02, 0x74, 0x31, // field 1, thread_id "t1"
                0x12, 0x02, 0x6D, 0x37, // field 2, message_id "m7"
                0x1A, 0x03, 0x41, 0x64, 0x61, // field 3, sender "Ada"
                0x22, 0x09, 0x4F, 0x6E, 0x20, 0x6D, 0x79, 0x20, 0x77, 0x61, 0x79, // field 4
                0x28, 0x2A, // field 5, varint 42
            ),
            payload,
        )
    }

    @Test
    fun `a dismiss carries thread and optional message ids`() {
        val (type, withMessage) = MessagingCodec.encodeDismiss("t1", "m7")
        assertEquals(GalMessage.Notification.DISMISS, type)
        val parsed = com.vayunmathur.auto.protocol.gal.MessagingDismiss.parseFrom(withMessage)
        assertEquals("t1", parsed.threadId)
        assertEquals("m7", parsed.messageId)

        val (_, bare) = MessagingCodec.encodeDismiss("t1")
        assertTrue(!com.vayunmathur.auto.protocol.gal.MessagingDismiss.parseFrom(bare).hasMessageId())
    }

    @Test
    fun `a typed reply classifies with its text`() {
        val payload = MessagingAction.newBuilder()
            .setThreadId("t1")
            .setAction(MessagingActionType.MESSAGE_REPLY)
            .setReplyText("Sounds good")
            .build()
            .toByteArray()
        val inbound = MessagingCodec.decodeInbound(GalMessage.Notification.ACTION, payload)
        assertEquals(InboundMessagingAction.Reply("t1", "Sounds good"), inbound)
    }

    @Test
    fun `a mark-read classifies without text`() {
        val payload = MessagingAction.newBuilder()
            .setThreadId("t1")
            .setAction(MessagingActionType.MESSAGE_MARK_READ)
            .build()
            .toByteArray()
        assertEquals(
            InboundMessagingAction.MarkRead("t1"),
            MessagingCodec.decodeInbound(GalMessage.Notification.ACTION, payload),
        )
    }

    @Test
    fun `a voice-reply request maps to a mic turn`() {
        val payload = MessagingAction.newBuilder()
            .setThreadId("t1")
            .setAction(MessagingActionType.MESSAGE_VOICE_REPLY_BEGIN)
            .build()
            .toByteArray()
        assertEquals(
            InboundMessagingAction.VoiceReplyBegin("t1"),
            MessagingCodec.decodeInbound(GalMessage.Notification.ACTION, payload),
        )
    }

    @Test
    fun `garbage on ch14 is observed, never fatal`() {
        // Wrong type, truncated ACTION, and an uninitialized ACTION all decode
        // to null: the pump logs them and carries on.
        assertNull(MessagingCodec.decodeInbound(GalMessage.Notification.MESSAGE, byteArrayOf(1, 2, 3)))
        assertNull(MessagingCodec.decodeInbound(GalMessage.Notification.ACTION, byteArrayOf(0x08)))
        assertNull(
            MessagingCodec.decodeInbound(
                GalMessage.Notification.ACTION,
                // buildPartial: a full build() would throw at the call site for
                // the missing required `action`, never reaching the codec.
                MessagingAction.newBuilder().setThreadId("t1").buildPartial().toByteArray(),
            ),
        )
    }

    @Test
    fun `ch14 types are distinct from media and sensor ids`() {
        val ids = setOf(
            GalMessage.Notification.THREADS,
            GalMessage.Notification.MESSAGE,
            GalMessage.Notification.DISMISS,
            GalMessage.Notification.ACTION,
        )
        assertEquals(4, ids.size)
        assertTrue(ids.all { it in 0x8000..0xFFFF })
    }
}
