package com.vayunmathur.auto.platform

/**
 * Process-wide push for active-call snapshots into the live session's car
 * card. The telecom owners (`CarProjectionInCallService`, bound by the
 * platform in car mode) cannot reference the session service directly --
 * the service is created per session while the InCall binding is
 * platform-owned -- so the session installs a push here on bring-up and
 * clears it on teardown. A null push means no session (phone UI only).
 *
 * Plain volatile field, never a flow: telecom callbacks need the push
 * synchronously, and the service thread already serializes install/clear.
 * Best-effort by contract: pushes must never throw into telecom callbacks.
 */
object CallCardPush {
    @Volatile
    var push: ((ActiveCallInfo?) -> Unit)? = null

    /** Forwards one snapshot (or null on remove) to the live session, if any. */
    fun push(info: ActiveCallInfo?) {
        runCatching { push?.invoke(info) }
    }
}
