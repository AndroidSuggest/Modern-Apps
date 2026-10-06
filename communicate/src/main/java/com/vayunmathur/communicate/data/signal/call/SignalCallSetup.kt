package com.vayunmathur.communicate.data.signal.call

import android.util.Log
import com.vayunmathur.communicate.data.call.WebRtcInit
import java.util.UUID
import org.signal.ringrtc.CallManager
import org.webrtc.EglBase

/**
 * Call setup for [SignalCallManager] (split for file length).
 * Extension functions on [SignalCallManager]; behavior identical, call sites unchanged.
 */

fun SignalCallManager.ensureInitialized(localAci: String): CallManager? {
    callManager?.let { return it }
    return try {
        if (initialized.compareAndSet(false, true)) {
            WebRtcInit.ensureInitialized(appContext)
            CallManager.initialize(appContext, SignalRingRtcLogger(), ringRtcFieldTrials())
        }
        val manager = CallManager.createCallManager(this) ?: return null
        manager.setSelfUuid(UUID.fromString(localAci))
        eglBase = EglBase.create()
        callManager = manager
        manager
    } catch (expected: Throwable) {
        // A failed initialize leaves nothing usable; let a later attempt retry.
        initialized.set(false)
        Log.e(SignalCallManager.TAG, "RingRTC initialization failed", expected)
        null
    }
}

fun SignalCallManager.placeCall(
    localAci: String,
    localDeviceId: Int,
    remoteAci: String,
    video: Boolean,
): Boolean {
    val manager = ensureInitialized(localAci) ?: return false
    val mediaType = if (video) CallManager.CallMediaType.VIDEO_CALL else CallManager.CallMediaType.AUDIO_CALL
    return try {
        manager.call(SignalRemote(remoteAci), mediaType, localDeviceId)
        true
    } catch (expected: Throwable) {
        Log.w(SignalCallManager.TAG, "could not place a call to $remoteAci", expected)
        false
    }
}

/** The EGL context group calls must share, so both call types use one GL thread. */
fun SignalCallManager.eglBaseForGroupCalls(): EglBase? = eglBase

/** RingRTC keeps one factory per CallManager, so group calls must reuse this instance. */
fun SignalCallManager.ringRtcCallManager(): CallManager? = callManager

/** The EGL context renderers must share with the decoder, so frames can be drawn. */
fun SignalCallManager.eglContext(): EglBase.Context? = eglBase?.eglBaseContext
