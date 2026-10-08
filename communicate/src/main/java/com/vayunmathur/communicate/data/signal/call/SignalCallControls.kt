package com.vayunmathur.communicate.data.signal.call

import com.vayunmathur.library.log.Log
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallManager

/**
 * Outbound call controls for [SignalCallManager] (split for file length).
 * Extension functions on [SignalCallManager]; behavior identical, call sites unchanged.
 */

fun SignalCallManager.accept(callId: Long): Boolean = withManager("accept") {
    it.acceptCall(CallId(callId))
}

fun SignalCallManager.hangup(): Boolean = withManager("hangup") { it.hangup() }

/** Turn our outgoing video on or off mid-call. */
fun SignalCallManager.setVideoEnabled(enabled: Boolean): Boolean = withManager("setVideoEnable") {
    camera?.setEnabled(enabled)
    it.setVideoEnable(enabled, screenSharing)
}

/**
 * Share the screen instead of the camera. RingRTC is told the outgoing video is a screencast so it adapts
 * the encoder for static content rather than treating it as camera motion.
 */
fun SignalCallManager.setScreenShareEnabled(enabled: Boolean, permission: android.content.Intent?): Boolean {
    val started = camera?.setScreenShare(enabled, permission) ?: false
    if (enabled && !started) return false
    screenSharing = enabled && started
    return withManager("setVideoEnable(screenShare)") {
        it.setVideoEnable(true, screenSharing)
    }
}

fun SignalCallManager.flipCamera() {
    camera?.flip()
}

fun SignalCallManager.setAudioEnabled(enabled: Boolean): Boolean =
    withManager("setAudioEnable") { it.setAudioEnable(enabled) }
