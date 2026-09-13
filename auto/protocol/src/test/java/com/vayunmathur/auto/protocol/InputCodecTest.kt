package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.InputFeedback
import com.vayunmathur.auto.protocol.gal.InputReport
import com.vayunmathur.auto.protocol.gal.KeyBindingRequest
import com.vayunmathur.auto.protocol.gal.TouchAction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the input-channel (service 8) wire format and the codec around it.
 *
 * Exact bytes where the head unit cares (the keybinding echo), round-trips
 * everywhere else: a round-trip passes happily with a wrong field number and
 * the failure only shows up as a head unit that ignores us.
 */
class InputCodecTest {

    @Test
    fun `KeyBindingRequest packs the keycodes into field 1`() {
        val (type, payload) = InputCodec.encodeKeyBinding(listOf(19, 20, 21))
        assertEquals(GalMessage.Input.KEY_BINDING_REQUEST, type)
        assertContentEquals(
            byteArrayOf(
                0x0A, 0x03, // field 1, length-delimited, 3 bytes
                0x13, 0x14, 0x15, // 19, 20, 21
            ),
            payload,
        )
        // And it parses back through the generated class the head unit reads.
        val parsed = KeyBindingRequest.parseFrom(payload)
        assertEquals(listOf(19, 0x14, 0x15), parsed.keycodesList)
    }

    @Test
    fun `key binding response pins status in field 1`() {
        // `xkc`: single int32 field 1. STATUS_SUCCESS = 0 encodes as 08 00.
        val parsed = InputCodec.decodeKeyBindingResponse(byteArrayOf(0x08, 0x00))
        assertEquals(0, parsed.status)
    }

    @Test
    fun `FeedbackRequest carries the event id in field 1`() {
        // `xjs`: `optional int32 feedback_event = 1` -- event 5 is 08 05,
        // the same exact-bytes style as the keybinding echo above.
        val (type, payload) = InputCodec.encodeFeedback(5)
        assertEquals(GalMessage.Input.FEEDBACK, type)
        assertContentEquals(byteArrayOf(0x08, 0x05), payload)
        val parsed = InputFeedback.parseFrom(payload)
        assertEquals(5, parsed.feedbackEvent)
    }

    @Test
    fun `a touch down round-trips with its pointer and index`() {
        val payload = InputCodec.buildReport(
            timestamp = 7,
            action = TouchAction.TOUCH_DOWN,
            pointers = listOf(InputPointer(x = 400, y = 240, pointerId = 0)),
            actionIndex = 0,
        )
        val events = InputCodec.decodeInbound(GalMessage.Input.REPORT, payload)
            ?: error("expected a decoded report")
        assertEquals(7L, events.timestamp)
        val touch = events.touches.single()
        assertEquals(TouchAction.TOUCH_DOWN, touch.action)
        assertEquals(InputPointer(400, 240, 0), touch.pointers.single())
        assertEquals(0, touch.actionIndex)
        assertTrue(events.keys.isEmpty())
        assertTrue(events.scrolls.isEmpty())
        assertTrue(events.absolutes.isEmpty())
    }

    @Test
    fun `a multitouch frame keeps every pointer and the action index`() {
        val payload = InputCodec.buildReport(
            action = TouchAction.TOUCH_POINTER_DOWN,
            pointers = listOf(
                InputPointer(x = 100, y = 100, pointerId = 0),
                InputPointer(x = 700, y = 380, pointerId = 1),
            ),
            actionIndex = 1,
        )
        val touch = InputCodec.decodeInbound(GalMessage.Input.REPORT, payload)!!
            .touches.single()
        assertEquals(TouchAction.TOUCH_POINTER_DOWN, touch.action)
        assertEquals(2, touch.pointers.size)
        assertEquals(1, touch.actionIndex)
        assertEquals(1, touch.pointers[1].pointerId)
    }

    @Test
    fun `an out-of-range action index clamps to the last pointer`() {
        // `jar.java` logs "Bad InputReport" when the index exceeds the count;
        // the codec clamps instead so one bad index cannot kill the frame.
        val payload = InputCodec.buildReport(
            pointers = listOf(InputPointer(x = 10, y = 10, pointerId = 0)),
            actionIndex = 9,
        )
        val touch = InputCodec.decodeInbound(GalMessage.Input.REPORT, payload)!!
            .touches.single()
        assertEquals(0, touch.actionIndex)
    }

    @Test
    fun `key down and up decode with their keycodes`() {
        val payload = InputCodec.buildKeyReport(
            timestamp = 3,
            19 to true,
            19 to false,
        )
        val events = InputCodec.decodeInbound(GalMessage.Input.REPORT, payload)!!
        assertEquals(3L, events.timestamp)
        assertEquals(listOf(InputKey(19, true), InputKey(19, false)), events.keys)
        assertTrue(events.touches.isEmpty())
    }

    @Test
    fun `a scroll tick decodes to its delta`() {
        val payload = InputCodec.buildScrollReport(delta = -1)
        val events = InputCodec.decodeInbound(GalMessage.Input.REPORT, payload)!!
        assertEquals(listOf(InputCodec.SCROLL_KEYCODE to -1), events.scrolls)
    }

    @Test
    fun `a zero scroll delta is dropped`() {
        // `jar.java` ignores zero-delta relative events outright.
        val payload = InputCodec.buildScrollReport(delta = 0)
        val events = InputCodec.decodeInbound(GalMessage.Input.REPORT, payload)!!
        assertTrue(events.scrolls.isEmpty())
    }

    @Test
    fun `an absolute value passes through with its keycode`() {
        val payload = InputCodec.buildAbsoluteReport(
            keycode = InputCodec.TAP_SELECT_KEYCODE,
            value = 1,
        )
        val events = InputCodec.decodeInbound(GalMessage.Input.REPORT, payload)!!
        assertEquals(listOf(InputCodec.TAP_SELECT_KEYCODE to 1), events.absolutes)
    }

    @Test
    fun `anything but a report decodes to null`() {
        // A type that is not a report on ch8 is not ours. Note 0x8001 IS the
        // report here: it aliases media start, sensor request and messaging
        // threads on other services, so that aliasing is defeated one layer
        // up by channel-id routing (InputChannel.onMessage), never by type.
        assertNull(
            InputCodec.decodeInbound(
                GalMessage.Input.KEY_BINDING_REQUEST,
                InputCodec.buildReport(),
            ),
        )
        assertNull(
            InputCodec.decodeInbound(
                GalMessage.Input.KEY_BINDING_RESPONSE,
                byteArrayOf(0x08, 0x00),
            ),
        )
    }

    @Test
    fun `malformed bytes decode to null, never throw`() {
        assertNull(InputCodec.decodeInbound(GalMessage.Input.REPORT, byteArrayOf(0xFF.toByte())))
        assertNull(InputCodec.decodeInbound(GalMessage.Input.REPORT, ByteArray(0)))
    }

    @Test
    fun `a report without its required timestamp decodes to null`() {
        // Field 1 is required (`xjt` checkInit): an uninitialized report is
        // corrupt traffic, not an empty one. `buildPartial` skips the
        // builder-side required check so the payload reaches the codec.
        val payload = InputReport.newBuilder().buildPartial().toByteArray()
        assertNull(InputCodec.decodeInbound(GalMessage.Input.REPORT, payload))
    }

    @Test
    fun `identity scaling keeps DHU pixels on a DHU-sized display`() {
        // DHU default is 800x480 with a matching display: nothing moves.
        val pointer = InputPointer(x = 400, y = 240, pointerId = 0)
        assertEquals(
            pointer,
            InputCodec.scalePointer(pointer, 800, 480, 800, 480),
        )
    }

    @Test
    fun `scaling maps the DHU touchscreen onto a tall car display`() {
        // 800x480 head-unit pixels onto the 1440x2560 car grid: each axis
        // scales independently, rounding to the nearest pixel.
        val scaled = InputCodec.scalePointer(
            InputPointer(x = 400, y = 240, pointerId = 0),
            huWidth = InputCodec.DEFAULT_HU_WIDTH,
            huHeight = InputCodec.DEFAULT_HU_HEIGHT,
            displayWidth = 1440,
            displayHeight = 2560,
        )
        assertEquals(InputPointer(x = 720, y = 1280, pointerId = 0), scaled)
    }

    @Test
    fun `corners map to corners`() {
        // Nearest-pixel rounding of a through-origin scale: the extreme
        // head-unit pixel lands one short of the extreme display pixel
        // (799/800 across is 1438.2, 479/480 down is 2554.7), immaterial
        // for injection; the tall-display test pins the exact factor.
        assertEquals(0, InputCodec.scaleCoordinate(0, 800, 1440, 800))
        assertEquals(1438, InputCodec.scaleCoordinate(799, 800, 1440, 800))
        assertEquals(2555, InputCodec.scaleCoordinate(479, 480, 2560, 480))
    }

    @Test
    fun `a missing head-unit size falls back to the DHU default`() {
        // No touchscreen config in discovery: divide by the 800x480 default
        // rather than by zero.
        assertEquals(400, InputCodec.scaleCoordinate(400, 0, 800, 800))
        assertEquals(240, InputCodec.scaleCoordinate(240, -1, 480, 480))
    }

    @Test
    fun `input message ids are the raw wire values`() {
        // `jar.a()` compares raw ids (no `wub.o` shift): the report the head
        // unit sends is 0x8001 and the binding answer is 0x8003.
        assertEquals(0x8001, GalMessage.Input.REPORT)
        assertEquals(0x8002, GalMessage.Input.KEY_BINDING_REQUEST)
        assertEquals(0x8003, GalMessage.Input.KEY_BINDING_RESPONSE)
        assertEquals(0x8004, GalMessage.Input.FEEDBACK)
    }
}
