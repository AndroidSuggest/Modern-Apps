package com.vayunmathur.communicate.data.signal

import android.util.Log
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.data.signal.call.SignalCallManager
import com.vayunmathur.communicate.data.signal.call.toRingRtc
import com.vayunmathur.communicate.telephony.InAppCallTelecom
import kotlinx.coroutines.launch
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * SignalClient 1:1 + group calling (split from SignalClient.kt for file length).
 *
 * Extension functions on [SignalClient]; behavior identical, call sites unchanged.
 * The RingRTC bridge instances ([SignalClient.callManager] etc.) stay in core —
 * extension properties cannot hold state.
 */

/**
 * Hand an inbound `CallMessage` to RingRTC, which owns the call state machine.
 *
 * `destinationDeviceId` is application-level addressing: the server fans out to every device, so a
 * message meant for a different one of our devices must be ignored here.
 */
internal suspend fun SignalClient.handleCallMessage(
    cm: SignalServiceProtos.CallMessage,
    senderAci: String,
    senderDeviceId: Int,
    env: SignalProtocol.SignalEnvelope,
    timestamp: Long,
) {
    val localDeviceId = authData?.deviceId ?: PRIMARY_DEVICE_ID
    if (cm.hasDestinationDeviceId() && cm.destinationDeviceId != localDeviceId) return
    val manager = callManager ?: run {
        Log.w(TAG, "RingRTC unavailable, dropping a call message from $senderAci")
        return
    }
    // Ensure the native stack is up before feeding anything in.
    val localAci = authData?.aci?.takeIf { it.isNotEmpty() } ?: return
    if (manager.ensureInitialized(localAci) == null) return

    when {
        cm.hasOffer() -> {
            val offer = cm.offer
            val isVideo = offer.type == SignalServiceProtos.CallMessage.Offer.Type.OFFER_VIDEO_CALL
            // Logged in full so an inbound offer from official Signal can be compared against what we
            // send: our own offers are accepted by the server but never ring, and our side reports
            // success, so the difference has to be in the message itself.
            val ageSec = ((env.serverTimestamp - env.timestamp).coerceAtLeast(0L)) / 1000
            Log.i(
                TAG,
                "inbound Offer callId=${offer.id} from $senderAci:$senderDeviceId " +
                    "opaque=${offer.opaque.size()}B type=${offer.type} " +
                    "hasDestinationDeviceId=${cm.hasDestinationDeviceId()} " +
                    "destinationDeviceId=${cm.destinationDeviceId} ageSec=$ageSec " +
                    "urgent=${env.urgent}",
            )
            // Recorded before RingRTC reports Ringing, which is how the shared registry tells an
            // inbound call from an outbound one, and what `answer()` needs.
            pendingIncomingCallAci = senderAci
            pendingIncomingCallId = offer.id
            _events.emit(
                SignalEvent.CallOffer(
                    callId = offer.id.toString(),
                    from = senderAci,
                    callCreator = senderAci,
                    isVideo = isVideo,
                    peerName = displayNameFor(senderAci),
                    timestamp = timestamp,
                ),
            )
            // Hand the call to the system so it owns ringing, audio focus and routing.
            appContext?.let { ctx -> InAppCallTelecom.addIncoming(ctx, senderAci) }
            manager.receivedOffer(
                callId = offer.id,
                senderAci = senderAci,
                senderDeviceId = senderDeviceId,
                localDeviceId = localDeviceId,
                opaque = offer.opaque.toByteArray(),
                messageAgeSec = ageSec,
                video = isVideo,
            )
        }
        cm.hasAnswer() -> manager.receivedAnswer(
            callId = cm.answer.id,
            senderAci = senderAci,
            senderDeviceId = senderDeviceId,
            opaque = cm.answer.opaque.toByteArray(),
        )
        cm.iceUpdateCount > 0 -> {
            // A batch shares one call id.
            val callId = cm.iceUpdateList.first().id
            manager.receivedIceCandidates(
                callId = callId,
                senderAci = senderAci,
                senderDeviceId = senderDeviceId,
                candidates = cm.iceUpdateList.map { it.opaque.toByteArray() },
            )
        }
        cm.hasHangup() -> {
            manager.receivedHangup(
                callId = cm.hangup.id,
                senderAci = senderAci,
                senderDeviceId = senderDeviceId,
                type = cm.hangup.type.toRingRtc(),
                deviceId = cm.hangup.deviceId,
            )
            _events.emit(SignalEvent.CallEnded(callId = cm.hangup.id.toString(), reason = "hangup"))
        }
        cm.hasBusy() -> {
            manager.receivedBusy(cm.busy.id, senderAci, senderDeviceId)
            _events.emit(SignalEvent.CallEnded(callId = cm.busy.id.toString(), reason = "busy"))
        }
        cm.hasOpaque() -> Log.i(TAG, "ignoring an opaque call message (group calling not implemented)")
    }
}

/**
 * Mirror RingRTC's state into the shared call registry, which is what the call screen and the system
 * call surface read. Registered lazily on the first state change so the controller is bound before the
 * UI can act on it.
 */
internal suspend fun SignalClient.publishCallState(
    aci: String,
    callId: Long,
    state: SignalCallManager.CallState,
    isVideo: Boolean,
) {
    val current = InAppCallRegistry.state.value
    if (current.phase == InAppCallPhase.Idle && state != SignalCallManager.CallState.Ended) {
        InAppCallRegistry.bind(
            CommunicateLine.Signal,
            signalCallController,
            CallCapabilities.AudioAndVideo,
        )
        InAppCallRegistry.videoController = signalVideoController
        InAppCallRegistry.onCallStarting(
            line = CommunicateLine.Signal,
            peerId = aci,
            peerName = displayNameFor(aci),
            isVideo = isVideo,
            // RingRTC reports Ringing for both directions; an inbound call already has an offer
            // recorded, which is how we tell them apart.
            incoming = pendingIncomingCallAci == aci,
            capabilities = CallCapabilities.AudioAndVideo,
        )
    }
    when (state) {
        SignalCallManager.CallState.Ringing ->
            if (pendingIncomingCallAci == aci) {
                InAppCallRegistry.onPhase(InAppCallPhase.Incoming)
            } else {
                InAppCallRegistry.onPhase(InAppCallPhase.Outgoing)
            }
        SignalCallManager.CallState.Connecting -> InAppCallRegistry.onPhase(InAppCallPhase.Connecting)
        SignalCallManager.CallState.Connected -> InAppCallRegistry.onPhase(InAppCallPhase.Active)
        SignalCallManager.CallState.Ended -> {
            InAppCallRegistry.onEnded(null)
            pendingIncomingCallAci = null
        }
    }
}

internal suspend fun SignalClient.emitCallState(
    aci: String,
    callId: Long,
    state: SignalCallManager.CallState,
    isVideo: Boolean,
) {
    val id = callId.toString()
    when (state) {
        SignalCallManager.CallState.Ringing ->
            _events.emit(SignalEvent.CallStateChanged(callId = id, phase = "ringing", isVideo = isVideo))
        SignalCallManager.CallState.Connecting ->
            _events.emit(SignalEvent.CallStateChanged(callId = id, phase = "connecting", isVideo = isVideo))
        SignalCallManager.CallState.Connected ->
            _events.emit(SignalEvent.CallStateChanged(callId = id, phase = "connected", isVideo = isVideo))
        SignalCallManager.CallState.Ended ->
            _events.emit(SignalEvent.CallEnded(callId = id, reason = "ended"))
    }
}

/**
 * Place a 1:1 call. Requires an established session with the recipient, since RingRTC needs both
 * identity keys to derive the SRTP keys.
 *
 * The app addresses conversations by phone number, so the destination is resolved the same way a send
 * is. A call needs an **ACI** specifically: a PNI-only contact cannot be called, because calling binds
 * to the ACI identity. Their ACI arrives with their first message.
 */
fun SignalClient.placeCall(conversationId: String, video: Boolean) {
    val localAci = authData?.aci?.takeIf { it.isNotEmpty() } ?: run {
        Log.w(TAG, "cannot call before registration")
        return
    }
    val manager = callManager ?: run {
        Log.w(TAG, "RingRTC unavailable, cannot place a call")
        return
    }
    scope.launch {
        val resolved = resolveDestinationAci(conversationId)
        if (resolved == null) {
            Log.w(TAG, "cannot call $conversationId: no Signal identity for it")
            _events.emit(SignalEvent.CallEnded(callId = "", reason = "not a Signal user"))
            return@launch
        }
        if (!ACI_REGEX.matches(resolved)) {
            // A PNI is enough to message but not to call.
            Log.w(TAG, "cannot call $resolved: calling needs an ACI, which arrives with their first message")
            _events.emit(SignalEvent.CallEnded(callId = "", reason = "cannot call this contact yet"))
            return@launch
        }
        val e = e2e
        if (e == null || (!e.hasSession(resolved, PRIMARY_DEVICE_ID) && !establishSession(e, resolved))) {
            Log.w(TAG, "no session with $resolved, cannot place a call")
            _events.emit(SignalEvent.CallEnded(callId = "", reason = "no session"))
            return@launch
        }
        manager.placeCall(localAci, authData?.deviceId ?: PRIMARY_DEVICE_ID, resolved, video)
        // Mirror the call into the system so it owns audio focus and routing.
        appContext?.let { ctx -> InAppCallTelecom.addOutgoing(ctx, resolved) }
    }
}

fun SignalClient.acceptCall(callId: String): Boolean {
    val id = callId.toLongOrNull() ?: return false
    return callManager?.accept(id) ?: false
}

suspend fun SignalClient.rejectCall(from: String, callId: String, creator: String): Boolean {
    // RingRTC turns this into the right hangup type and tells us what to send.
    val ok = callManager?.hangup() ?: false
    if (!ok) _events.emit(SignalEvent.CallEnded(callId = callId, reason = "rejected"))
    return true
}

fun SignalClient.endCall(): Boolean = callManager?.hangup() ?: false

fun SignalClient.setCallAudioEnabled(enabled: Boolean): Boolean =
    callManager?.setAudioEnabled(enabled) ?: false

/**
 * Start or join the group call for [conversationId].
 *
 * Needs the group's membership, which is why it refreshes first: RingRTC cannot identify participants
 * without every member's ACI ciphertext.
 */
suspend fun SignalClient.placeGroupCall(conversationId: String): Boolean {
    if (!SignalProtocol.isGroupConversation(conversationId)) return false
    val manager = groupCallManager ?: run {
        Log.w(TAG, "RingRTC unavailable, cannot place a group call")
        return false
    }
    val conversation = try { db?.conversationDao()?.getConversation(conversationId) } catch (_: Exception) { null }
    val masterKey = conversation?.groupMasterKey ?: run {
        Log.w(TAG, "no master key for $conversationId, cannot place a group call")
        return false
    }
    // Membership must be current or participants cannot be matched to people.
    refreshGroup(conversationId)
    val groupIdentifier = SignalGroups.groupIdentifierBytes(masterKey) ?: return false

    InAppCallRegistry.bind(CommunicateLine.Signal, signalCallController, CallCapabilities.AudioAndVideo)
    InAppCallRegistry.onCallStarting(
        line = CommunicateLine.Signal,
        peerId = conversationId,
        peerName = conversation.name ?: conversationId,
        isVideo = false,
        incoming = false,
        capabilities = CallCapabilities.AudioAndVideo,
    )
    if (!manager.connect(groupIdentifier)) {
        InAppCallRegistry.onEnded("could not start the group call")
        return false
    }
    manager.join()
    appContext?.let { ctx -> InAppCallTelecom.addOutgoing(ctx, conversationId) }
    return true
}
