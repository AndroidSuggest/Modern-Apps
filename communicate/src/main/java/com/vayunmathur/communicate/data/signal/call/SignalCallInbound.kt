package com.vayunmathur.communicate.data.signal.call

import android.util.Log
import kotlinx.coroutines.launch
import org.signal.ringrtc.CallId
import org.signal.ringrtc.CallManager

/**
 * Inbound call signaling for [SignalCallManager] (split for file length).
 * Extension functions on [SignalCallManager]; behavior identical, call sites unchanged.
 */

fun SignalCallManager.receivedOffer(
    callId: Long,
    senderAci: String,
    senderDeviceId: Int,
    localDeviceId: Int,
    opaque: ByteArray,
    messageAgeSec: Long,
    video: Boolean,
) {
    val manager = callManager ?: return
    scope.launch {
        val keys = signaling.identityKeys(senderAci) ?: run {
            Log.w(SignalCallManager.TAG, "no identity keys for $senderAci, cannot accept the offer")
            return@launch
        }
        val mediaType = if (video) CallManager.CallMediaType.VIDEO_CALL else CallManager.CallMediaType.AUDIO_CALL
        callMediaTypes[callId] = mediaType
        try {
            manager.receivedOffer(
                CallId(callId),
                SignalRemote(senderAci),
                senderDeviceId,
                opaque,
                messageAgeSec,
                mediaType,
                localDeviceId,
                keys.remote,
                keys.local,
            )
        } catch (expected: Throwable) {
            Log.w(SignalCallManager.TAG, "receivedOffer failed", expected)
        }
    }
}

fun SignalCallManager.receivedAnswer(callId: Long, senderAci: String, senderDeviceId: Int, opaque: ByteArray) {
    val manager = callManager ?: return
    scope.launch {
        val keys = signaling.identityKeys(senderAci) ?: return@launch
        try {
            manager.receivedAnswer(
                CallId(callId),
                SignalRemote(senderAci),
                senderDeviceId,
                opaque,
                keys.remote,
                keys.local,
            )
        } catch (expected: Throwable) {
            Log.w(SignalCallManager.TAG, "receivedAnswer failed", expected)
        }
    }
}

fun SignalCallManager.receivedIceCandidates(
    callId: Long,
    senderAci: String,
    senderDeviceId: Int,
    candidates: List<ByteArray>,
) {
    withManager("receivedIceCandidates") {
        it.receivedIceCandidates(
            CallId(callId),
            SignalRemote(senderAci),
            senderDeviceId,
            candidates)
    }
}

fun SignalCallManager.receivedHangup(
    callId: Long,
    senderAci: String,
    senderDeviceId: Int,
    type: CallManager.HangupType,
    deviceId: Int,
) {
    withManager("receivedHangup") {
        it.receivedHangup(
            CallId(callId),
            SignalRemote(senderAci),
            senderDeviceId,
            type,
            deviceId)
    }
}

fun SignalCallManager.receivedBusy(callId: Long, senderAci: String, senderDeviceId: Int) {
    withManager("receivedBusy") {
        it.receivedBusy(CallId(callId), SignalRemote(senderAci), senderDeviceId)
    }
}
