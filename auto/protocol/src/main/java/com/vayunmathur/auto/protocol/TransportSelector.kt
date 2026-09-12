package com.vayunmathur.auto.protocol

/**
 * Picks which transport a session runs on when more than one is available.
 *
 * Priority is physical first: USB beats wireless beats the TCP dev fallback, because a
 * cable is unambiguous proof of which car the user means, while wireless needs the
 * Bluetooth association to disambiguate. The loopback fallback exists only in dev builds
 * so a release phone never projects to whatever happens to listen on localhost.
 *
 * Pure policy: the drivers report readiness, this answers with an order. It owns no
 * retry or backoff — that stays with the reconnect owner in `ProjectionService`.
 */
object TransportSelector {

    /**
     * @param usbReady a granted, opened USB accessory is waiting.
     * @param wirelessReady a WiFi link with negotiated socket params is up.
     * @param devBuild whether the TCP loopback fallback is allowed ([BuildConfig.DEV_BUILD]).
     * @return the winning transport, or null when nothing may be used yet.
     */
    fun select(usbReady: Boolean, wirelessReady: Boolean, devBuild: Boolean): TransportKind? =
        when {
            usbReady -> TransportKind.USB
            wirelessReady -> TransportKind.WIRELESS
            devBuild -> TransportKind.TCP_LOOPBACK
            else -> null
        }
}
