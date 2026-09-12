package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AbsoluteEvent
import com.vayunmathur.auto.protocol.gal.AbsoluteEvents
import com.vayunmathur.auto.protocol.gal.InputReport
import com.vayunmathur.auto.protocol.gal.KeyBindingRequest
import com.vayunmathur.auto.protocol.gal.KeyBindingResponse
import com.vayunmathur.auto.protocol.gal.KeyEvent
import com.vayunmathur.auto.protocol.gal.KeyEvents
import com.vayunmathur.auto.protocol.gal.RelativeEvent
import com.vayunmathur.auto.protocol.gal.RelativeEvents
import com.vayunmathur.auto.protocol.gal.TouchAction
import com.vayunmathur.auto.protocol.gal.TouchEvent
import com.vayunmathur.auto.protocol.gal.TouchPointer

/**
 * One decoded finger: display-agnostic head-unit pixels plus the pointer id the
 * head unit assigned. Coordinates are in the head-unit touchscreen space from
 * discovery (`TouchConfig` width/height, DHU default 800x480); the receiver
 * scales them to car-display pixels with [scaleCoordinate].
 */
data class InputPointer(val x: Int, val y: Int, val pointerId: Int)

/** A decoded touch frame: the action plus whichever pointer it acts on. */
data class InputTouch(val action: TouchAction, val pointers: List<InputPointer>, val actionIndex: Int)

/** A decoded key press or release: the Android keycode and the direction. */
data class InputKey(val keycode: Int, val down: Boolean)

/**
 * A decoded head-unit input report, already classified.
 *
 * Every section is optional on the wire (`xjt` fields 3-7) and the phone handles
 * whichever are present, so each list may be empty independently. The head unit
 * is the only sender: the phone never builds or receives its own reports.
 */
data class InputEvents(
    val timestamp: Long,
    val touches: List<InputTouch>,
    val keys: List<InputKey>,
    /** Scroll ticks: (keycode 65536, delta) pairs; a zero delta is dropped upstream. */
    val scrolls: List<Pair<Int, Int>>,
    /** Absolute values (e.g. tap-as-select keycode 65541) the owner may act on. */
    val absolutes: List<Pair<Int, Int>>,
)

/**
 * Pure input-channel (INPUT_SOURCE, service 8) codecs: touch, keys and scroll.
 *
 * Android-free like everything else in this module, so decoding, keybinding and
 * coordinate scaling are all host-testable. The head unit owns the input
 * hardware and sends [GalMessage.Input.REPORT]; the phone answers with
 * [GalMessage.Input.KEY_BINDING_REQUEST] on channel open (the keycode list the
 * head unit advertised in discovery) and observes
 * [GalMessage.Input.KEY_BINDING_RESPONSE]. Sends go out with
 * `GalConnection.send(channelId, type, payload)` -- always encrypted, never
 * CONTROL-flagged, like all service-channel traffic.
 *
 * Wire values, from the APK bytecode and descriptors rather than the decompiler
 * (HANDOFF.md section 9): `jar.a()` compares the raw ids, so 0x8001 is the
 * report and 0x8003 the binding response -- no `wub.o` off-by-one here.
 */
object InputCodec {

    /**
     * Head-unit keycode for a scroll tick. A relative event with this keycode
     * and a non-zero delta becomes a scroll of that many detents; a zero delta
     * is ignored (`jar.java`: "Ignoring zero delta relative event").
     */
    const val SCROLL_KEYCODE = 65536

    /**
     * Head-unit keycode for the touchpad tap-as-select behind absolute event
     * 65541: value 1 presses KEYCODE_DPAD_CENTER, 0 releases it.
     */
    const val TAP_SELECT_KEYCODE = 65541

    /** Android `KEYCODE_DPAD_CENTER`, the key tap-as-select synthesizes. */
    const val KEYCODE_DPAD_CENTER = 23

    /**
     * DHU default touchscreen: `config/default.ini` is 800x480, which is
     * `VIDEO_800x480`. These are the fallback head-unit dimensions when
     * discovery advertised no touchscreen config.
     */
    const val DEFAULT_HU_WIDTH = 800
    const val DEFAULT_HU_HEIGHT = 480

    /** Phone -> HU: echo the discovery-advertised keycodes back on channel open. */
    fun encodeKeyBinding(keycodes: List<Int>): Pair<Int, ByteArray> =
        GalMessage.Input.KEY_BINDING_REQUEST to KeyBindingRequest.newBuilder()
            .addAllKeycodes(keycodes)
            .build()
            .toByteArray()

    /** Parses a HU -> phone binding response, throwing on malformed bytes. */
    fun decodeKeyBindingResponse(payload: ByteArray): KeyBindingResponse =
        KeyBindingResponse.parseFrom(payload)

    /**
     * Classifies one inbound ch8 message, or null when it is not an input
     * report we handle: a type other than REPORT, malformed bytes, or a
     * report missing its required timestamp. Null means "observed and
     * ignored", never an error -- the pump must survive whatever a head
     * unit sends. Route by channel id before calling: the 0x04 CONTROL
     * frame flag does NOT mean "control channel" (HANDOFF.md section 9),
     * and 0x8001 is aliased across services (media start, sensor request,
     * messaging threads), so only ch8 traffic belongs here.
     */
    fun decodeInbound(type: Int, payload: ByteArray): InputEvents? {
        if (type != GalMessage.Input.REPORT) return null
        val report = runCatching { InputReport.parseFrom(payload) }.getOrNull()
            ?: return null
        if (!report.isInitialized) return null
        return InputEvents(
            timestamp = report.timestamp,
            touches = listOfNotNull(
                report.takeIf { it.hasTouchscreen() }?.touchscreen?.toTouch(),
                report.takeIf { it.hasTouchpad() }?.touchpad?.toTouch(),
            ),
            keys = if (report.hasKeyEvents()) report.keyEvents.eventsList.map { it.toKey() }
            else emptyList(),
            scrolls = if (report.hasRelativeEvents()) {
                report.relativeEvents.eventList
                    .filter { it.keycode == SCROLL_KEYCODE && it.delta != 0 }
                    .map { it.keycode to it.delta }
            } else emptyList(),
            absolutes = if (report.hasAbsEvents()) {
                report.absEvents.eventList.map { it.keycode to it.value }
            } else emptyList(),
        )
    }

    private fun TouchEvent.toTouch(): InputTouch? {
        // An empty frame carries no pointers; `jar.java` guards on the count
        // before touching the index, so an empty frame is observed, not an error.
        if (pointersCount == 0) return null
        // The action enum is already the MotionEvent action (`wox.A` minus one),
        // so it passes through verbatim; absent means DOWN (0 arrives as default).
        val index = if (hasActionIndex()) actionIndex else 0
        return InputTouch(
            action = action,
            pointers = pointersList.map { InputPointer(x = it.x, y = it.y, pointerId = it.pointerId) },
            actionIndex = index.coerceIn(0, pointersCount - 1),
        )
    }

    private fun KeyEvent.toKey(): InputKey = InputKey(keycode = keycode, down = keyDown)

    /**
     * Scales one head-unit touchscreen pixel to a car-display pixel.
     *
     * The head unit reports in its own touchscreen space (`TouchConfig`
     * width/height from discovery) while the car UI renders at its own size,
     * so each axis scales independently and rounds to the nearest pixel.
     * A zero or negative head-unit dimension cannot divide, so it falls back
     * to the DHU default for that axis rather than producing garbage.
     */
    fun scaleCoordinate(value: Int, huSize: Int, displaySize: Int, defaultHuSize: Int): Int {
        val source = if (huSize > 0) huSize else defaultHuSize
        return ((value.toLong() * displaySize + source / 2) / source).toInt()
    }

    /** Scales both axes of one pointer into car-display pixels. */
    fun scalePointer(
        pointer: InputPointer,
        huWidth: Int,
        huHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): InputPointer = pointer.copy(
        x = scaleCoordinate(pointer.x, huWidth, displayWidth, DEFAULT_HU_WIDTH),
        y = scaleCoordinate(pointer.y, huHeight, displayHeight, DEFAULT_HU_HEIGHT),
    )

    /**
     * Builds a touchscreen report carrying one touch frame, for tests and for
     * head-unit stand-ins. The phone never sends these on the wire.
     */
    fun buildReport(
        timestamp: Long = 0,
        action: TouchAction = TouchAction.TOUCH_DOWN,
        pointers: List<InputPointer> = listOf(InputPointer(0, 0, 0)),
        actionIndex: Int = 0,
    ): ByteArray = InputReport.newBuilder()
        .setTimestamp(timestamp)
        .setTouchscreen(
            TouchEvent.newBuilder()
                .setAction(action)
                .setActionIndex(actionIndex)
                .addAllPointers(
                    pointers.map {
                        TouchPointer.newBuilder()
                            .setX(it.x)
                            .setY(it.y)
                            .setPointerId(it.pointerId)
                            .build()
                    },
                ),
        )
        .build()
        .toByteArray()

    /** Builds a report carrying key events only. */
    fun buildKeyReport(timestamp: Long = 0, vararg keys: Pair<Int, Boolean>): ByteArray =
        InputReport.newBuilder()
            .setTimestamp(timestamp)
            .setKeyEvents(
                KeyEvents.newBuilder().addAllEvents(
                    keys.map { (keycode, down) ->
                        KeyEvent.newBuilder()
                            .setKeycode(keycode)
                            .setKeyDown(down)
                            .setMetaState(0)
                            .build()
                    },
                ),
            )
            .build()
            .toByteArray()

    /** Builds a report carrying one scroll tick (keycode 65536, non-zero delta). */
    fun buildScrollReport(timestamp: Long = 0, delta: Int): ByteArray = InputReport.newBuilder()
        .setTimestamp(timestamp)
        .setRelativeEvents(
            RelativeEvents.newBuilder().addEvent(
                RelativeEvent.newBuilder().setKeycode(SCROLL_KEYCODE).setDelta(delta),
            ),
        )
        .build()
        .toByteArray()

    /** Builds a report carrying one absolute value (e.g. tap-as-select). */
    fun buildAbsoluteReport(timestamp: Long = 0, keycode: Int, value: Int): ByteArray =
        InputReport.newBuilder()
            .setTimestamp(timestamp)
            .setAbsEvents(
                AbsoluteEvents.newBuilder().addEvent(
                    AbsoluteEvent.newBuilder().setKeycode(keycode).setValue(value),
                ),
            )
            .build()
            .toByteArray()
}
