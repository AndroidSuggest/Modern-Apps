package com.vayunmathur.communicate.data.signal.call

import org.webrtc.PeerConnection

/** Identity key pair for SRTP binding. */
data class IdentityKeyPairBytes(val local: ByteArray, val remote: ByteArray)

/** How call signaling reaches the network, and how call state reaches the app. */
interface SignalCallSignaling {
    /** Send one `CallMessage` to [aci]. [deviceId] is null when the message should be broadcast. */
    suspend fun sendCallMessage(
        aci: String,
        deviceId: Int?,
        message: SignalCallMessage,
        urgent: Boolean,
    ): Boolean

    /** The identity keys RingRTC binds the SRTP key derivation to. */
    suspend fun identityKeys(aci: String): IdentityKeyPairBytes?

    /** The peer turned their camera on or off. */
    fun onRemoteVideo(enabled: Boolean)

    /** The peer started or stopped sharing their screen. */
    fun onRemoteScreenShare(enabled: Boolean)

    /**
     * TURN/STUN relays for the call. Without them only host candidates are available, so a call fails
     * behind NAT.
     */
    suspend fun iceServers(): List<PeerConnection.IceServer>

    fun onCallStateChanged(aci: String, callId: Long, state: SignalCallManager.CallState, isVideo: Boolean)
}
