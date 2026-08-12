package com.vayunmathur.euicc

/**
 * JNI entry points into the native SGP.22 core (libeuicc.so).
 *
 * The Rust side owns the ASN.1 + ES10 protocol logic and drives the eUICC by
 * calling back into [transmitApdu], which forwards each command APDU over the
 * telephony logical channel currently opened by [com.vayunmathur.euicc.telephony.EuiccChannelManager].
 * Only marshalling lives here; see euicc/src/main/rust/.
 *
 * The native `nativeXxx` operations are only valid while a channel is open, i.e.
 * inside `EuiccChannelManager.withIsdrChannel { ... }`, which installs
 * [activeChannel] for the duration of the block.
 */
object EuiccNative {
    init {
        System.loadLibrary("euicc")
    }

    /**
     * The transmit function for the currently open ISD-R logical channel, or
     * null when no channel is open. Set by `EuiccChannelManager.withIsdrChannel`.
     */
    @Volatile
    @JvmStatic
    var activeChannel: ((ByteArray) -> ByteArray)? = null

    /**
     * Called by the native core to send one command APDU to the eUICC. Returns
     * the response bytes (response data followed by the two status bytes).
     */
    @JvmStatic
    fun transmitApdu(command: ByteArray): ByteArray =
        (activeChannel ?: error("no active eUICC channel")).invoke(command)

    /** Returns the native core's version string. */
    external fun nativeVersion(): String

    /** Returns the 32-hex-digit EID, or null on error. */
    external fun nativeGetEid(): String?

    /** Returns the EUICCInfo1 subset as a JSON string, or null on error. */
    external fun nativeGetEuiccInfo(): String?
}
