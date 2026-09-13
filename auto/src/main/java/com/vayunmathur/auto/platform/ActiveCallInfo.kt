package com.vayunmathur.auto.platform

/**
 * One live telecom call for the projected call card ([UnCallView] shape in
 * gearhead: incoming vs active, hold/mute/end actions, duration ticker).
 *
 * [telecomState] is the `android.telecom.Call` state int (so the platform
 * module never imports telecom); [number] is the handle's scheme-specific
 * part, null when withheld; [acceptedMs] is wall-clock accept time, 0 while
 * ringing; [held] disables the action row with the button-disabled alpha;
 * [muted] reflects the InCall mute state.
 */
data class ActiveCallInfo(
    val telecomState: Int,
    val number: String?,
    val acceptedMs: Long = 0L,
    val held: Boolean = false,
    val muted: Boolean = false,
) {
    /** True while ringing (incoming), false once active/held. */
    val isIncoming: Boolean get() = acceptedMs == 0L
}
