package com.vayunmathur.findfamily.tracker

/**
 * Phone-native **FiRa UWB precision finding** to a bound tracker, over BLE GATT.
 *
 * This is the owner-only "point me to it" last-few-meters step. The design reuses
 * the existing FiRa ranging stack unchanged (`UwbController.openController()` mints
 * the params; `UwbController.stream()` yields distance + AoA); the ONLY thing that
 * differs from the phone-to-phone flow is the transport of the handshake: params go
 * to the tracker over [TrackerBle.UWB_SESSION_CHARACTERISTIC_UUID] instead of the
 * WebSocket `UwbEnvelope`.
 *
 * The wire encoding below is complete and testable; [startRanging] is a documented
 * seam (like `UwbAccessoryProtocol`) because the GATT write + the firmware's FiRa
 * responder session can only be exercised against real DW3110 hardware.
 */
object TrackerUwbGatt {

    /** FiRa session params handed to the tracker for one ranging session. */
    data class SessionParams(
        val localAddress: ByteArray,   // phone (controller) 2-byte MAC
        val sessionId: Int,
        val channelNumber: Int,
        val preambleIndex: Int,
        // The STS/session key is derived from the bind-time beacon secret on both
        // ends, so it is NOT sent over the air per-find (only channel/slot are).
    )

    /**
     * Encode the per-find params written to the tracker's UWB session characteristic:
     * `[2B localAddress][4B sessionId BE][1B channel][1B preamble]`.
     */
    fun encodeSessionParams(p: SessionParams): ByteArray {
        require(p.localAddress.size == 2) { "localAddress must be 2 bytes" }
        val out = ByteArray(2 + 4 + 1 + 1)
        p.localAddress.copyInto(out, 0)
        out[2] = (p.sessionId ushr 24).toByte()
        out[3] = (p.sessionId ushr 16).toByte()
        out[4] = (p.sessionId ushr 8).toByte()
        out[5] = p.sessionId.toByte()
        out[6] = p.channelNumber.toByte()
        out[7] = p.preambleIndex.toByte()
        return out
    }

    /**
     * Begin a UWB ranging session to a bound tracker. Requires: (a) writing
     * [encodeSessionParams] to the tracker over GATT, and (b) the DW3110 firmware
     * starting a matching FiRa responder session. Both need hardware, so this is a
     * seam for the on-device implementation phase.
     */
    fun startRanging(): Nothing = throw NotImplementedError(
        "Phone↔tracker FiRa ranging over BLE GATT is not wired yet. " +
            "See TrackerUwbGatt.kt and the DW3110 firmware FiRa-responder contract."
    )
}
