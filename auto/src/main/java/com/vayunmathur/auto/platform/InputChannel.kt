package com.vayunmathur.auto.platform

import android.util.Log
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.InputCodec
import com.vayunmathur.auto.protocol.InputKey
import com.vayunmathur.auto.protocol.InputPointer
import com.vayunmathur.auto.protocol.gal.Service

/**
 * A touch frame scaled to car-display pixels, ready to inject.
 *
 * [action] already IS the `MotionEvent` action: the head unit numbers touch
 * actions exactly as `MotionEvent` does (DOWN 0, UP 1, MOVE 2, POINTER_DOWN 5,
 * POINTER_UP 6), so it passes through verbatim and only the POINTER_DOWN/UP
 * variants need the index shifted in at injection time.
 */
data class ScaledTouch(
    val action: Int,
    val pointers: List<InputPointer>,
    val actionIndex: Int,
)

/**
 * The input source channel: turns head-unit touch, keys and scroll into car
 * UI events once the head unit grants the channel.
 *
 * Bring-up needs nothing beyond the generic open: ch8 opens in the wire-order
 * pass like every other service, and the binding request goes out on the
 * grant -- sending on a channel the head unit has not opened earns a bare
 * MessageError (0xff). Injection itself gates on the arbitrated input flag
 * (projected video WITH input focus): a `PROJECTED_NO_INPUT_FOCUS` head unit
 * still streams video but its input must be dropped, as must everything while
 * the car shows its own UI.
 *
 * Route by channel id: 0x8001 is aliased across services (media start, sensor
 * request, messaging threads), so only ch8 traffic belongs to [InputCodec].
 * The 0x04 CONTROL frame flag does NOT mean "control channel" (HANDOFF.md
 * section 9) and never enters this decision.
 */
class InputChannel(
    private val service: Service,
    private val connection: GalConnection,
    /** Arbitrated input flag; the service reads it off the session's focus state. */
    private val isInputAllowed: () -> Boolean = { false },
    /** Current car-display size in pixels, or null before video streaming starts. */
    private val displaySize: () -> Pair<Int, Int>? = { null },
    /** Fire-and-forget observations for the phone status screen; never gates. */
    private val onEvent: (InputEvent) -> Unit = {},
    /** Injects one scaled touch frame; returns whether it was consumed. */
    private val touchSink: (ScaledTouch) -> Boolean = { false },
    /** Injects one key press or release; returns whether it was consumed. */
    private val keySink: (InputKey) -> Boolean = { false },
    /** Injects one scroll tick; returns whether it was consumed. */
    private val scrollSink: (delta: Int) -> Boolean = { false },
) {
    val channelId: Int get() = service.id

    /** Head-unit touchscreen size, or the DHU default when undiscovered. */
    private val huSize: Pair<Int, Int> =
        service.inputSource.touchscreenList.firstOrNull()?.let { it.width to it.height }
            ?: (InputCodec.DEFAULT_HU_WIDTH to InputCodec.DEFAULT_HU_HEIGHT)

    /**
     * Whether the binding echo has gone out. Set in [requestBinding], read in
     * [onMessage] to log (not crash on) traffic that arrives with no owner
     * bound yet -- e.g. a head unit that sends before the grant path runs.
     */
    @Volatile
    private var bound = false

    /**
     * The grant arrived: echo the discovery-advertised keycodes back so the
     * head unit knows which keys we handle. Called once per channel open, and
     * again when video focus flaps back to PROJECTED with input (a head unit
     * that parked input may need the re-bind to resume sending reports).
     * Idempotent: re-sending the same echo is harmless.
     */
    fun requestBinding() {
        val keycodes = service.inputSource.keycodesSupportedList
        val (type, payload) = InputCodec.encodeKeyBinding(keycodes)
        connection.send(channelId, type, payload)
        bound = true
        Log.i(TAG, "input channel open; bound ${keycodes.size} keycodes")
        onEvent(InputEvent.BindingRequested(keycodes.size))
    }

    /** One message for this channel; anything else is ignored, never misparsed. */
    fun onMessage(channelId: Int, type: Int, payload: ByteArray) {
        if (channelId != this.channelId) {
            Log.w(TAG, "ignoring 0x${type.toString(16)} for channel $channelId")
            return
        }
        if (!bound) {
            // Traffic before the grant's binding echo: the head unit is
            // talking on a channel we never bound (or the grant path never
            // ran). Log, don't crash -- the binding goes out on the grant and
            // on the focus flap back to input-allowed.
            Log.w(TAG, "ch8 traffic before binding (0x${type.toString(16)}); dropping")
            onEvent(InputEvent.DroppedNoFocus)
            return
        }
        if (type == GalMessage.Input.KEY_BINDING_RESPONSE) {
            val status = runCatching { InputCodec.decodeKeyBindingResponse(payload) }.getOrNull()
            Log.i(TAG, "key binding response: ${status?.status}")
            onEvent(InputEvent.BindingAnswered(status?.status ?: -1))
            return
        }
        val events = InputCodec.decodeInbound(type, payload)
        if (events == null) {
            Log.d(TAG, "unhandled input message 0x${type.toString(16)}")
            return
        }
        if (!isInputAllowed()) {
            Log.d(TAG, "dropping ${events.describe()} without input focus")
            onEvent(InputEvent.DroppedNoFocus)
            return
        }
        val display = displaySize()
        if (display == null) {
            Log.d(TAG, "dropping ${events.describe()} with no car display yet")
            onEvent(InputEvent.DroppedNoFocus)
            return
        }
        val (displayWidth, displayHeight) = display
        events.touches.forEach { touch ->
            val scaled = ScaledTouch(
                action = touch.action.number,
                pointers = touch.pointers.map {
                    InputCodec.scalePointer(
                        it,
                        huWidth = huSize.first,
                        huHeight = huSize.second,
                        displayWidth = displayWidth,
                        displayHeight = displayHeight,
                    )
                },
                actionIndex = touch.actionIndex,
            )
            if (touchSink(scaled)) onEvent(InputEvent.Touch(scaled.action, scaled.pointers.size))
        }
        events.keys.forEach { key ->
            // Volume keys are consumed, never injected: gearhead's car home
            // swallows KEYCODE_VOLUME_UP/DOWN (its dispatchKeyEvent returns
            // true for 24/25) because the head unit owns its speaker volume.
            // Injecting them would double-handle volume the HU already manages.
            if (key.keycode == VOLUME_UP || key.keycode == VOLUME_DOWN) {
                onEvent(InputEvent.VolumeKey(key.keycode, key.down))
            } else if (keySink(key)) {
                onEvent(InputEvent.Key(key.keycode, key.down))
            }
        }
        // Tap-as-select rides the absolute section: keycode 65541, value 1
        // presses DPAD_CENTER and anything else releases it (`jar.java`).
        events.absolutes.forEach { (keycode, value) ->
            if (keycode == InputCodec.TAP_SELECT_KEYCODE) {
                val key = InputKey(InputCodec.KEYCODE_DPAD_CENTER, value == 1)
                if (keySink(key)) onEvent(InputEvent.Key(key.keycode, key.down))
            } else {
                Log.d(TAG, "ignoring absolute event keycode=$keycode value=$value")
            }
        }
        events.scrolls.forEach { (_, delta) ->
            if (scrollSink(delta)) onEvent(InputEvent.Scroll(delta))
        }
    }

    private companion object {
        const val TAG = "MaAuto.Input"

        /** Consumed, never injected: the head unit owns its speaker volume. */
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25

        private fun com.vayunmathur.auto.protocol.InputEvents.describe(): String =
            "input report (${touches.size} touch, ${keys.size} keys, " +
                "${scrolls.size} scroll, ${absolutes.size} abs)"
    }
}
