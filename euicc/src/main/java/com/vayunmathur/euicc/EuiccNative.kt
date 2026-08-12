package com.vayunmathur.euicc

/**
 * JNI entry points into the native SGP.22 core (libeuicc.so).
 *
 * The Rust side owns the ASN.1 + ES9+/ES10 protocol logic and calls back into
 * [transmitApdu] to drive the eUICC over the telephony logical channel. Only
 * marshalling lives here; see euicc/src/main/rust/.
 */
object EuiccNative {
    init {
        System.loadLibrary("euicc")
    }

    /** Returns the native core's version string. Stub sanity check for Phase 1. */
    external fun nativeVersion(): String
}
