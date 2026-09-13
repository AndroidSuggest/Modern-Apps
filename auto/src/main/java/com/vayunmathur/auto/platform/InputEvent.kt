package com.vayunmathur.auto.platform

/**
 * Something the input channel observed, forwarded to [AutoSessionState] for the phone UI.
 *
 * Injection never waits on these: they are fire-and-forget observations, mirroring
 * [VideoEvent] and [MessagingEvent]. The phone status screen counts touches, keys
 * and drops so the operator can tell "head unit sends, phone injects" from
 * "gated on focus" without a live run.
 */
sealed interface InputEvent {
    /** The binding request went out on the ch8 grant; [count] is keycodes echoed. */
    data class BindingRequested(val count: Int) : InputEvent

    /** The head unit answered the binding request with [status] (0 is success). */
    data class BindingAnswered(val status: Int) : InputEvent

    /** One touch frame was injected: [action] is the MotionEvent action verbatim. */
    data class Touch(val action: Int, val pointerCount: Int) : InputEvent

    /** One key press or release was injected. */
    data class Key(val keycode: Int, val down: Boolean) : InputEvent

    /**
     * A head-unit volume key was consumed, not injected: the car owns its
     * speaker volume (gearhead's car home swallows 24/25), so these count
     * separately from injected keys.
     */
    data class VolumeKey(val keycode: Int, val down: Boolean) : InputEvent

    /** One scroll tick was injected. */
    data class Scroll(val delta: Int) : InputEvent

    /** A report arrived but the arbitrated input flag was off, so it was dropped. */
    data object DroppedNoFocus : InputEvent
}
