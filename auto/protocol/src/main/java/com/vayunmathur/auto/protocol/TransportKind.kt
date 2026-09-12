package com.vayunmathur.auto.protocol

/**
 * How the GAL bytes reach the head unit.
 *
 * Every transport reduces to a [GalTransport]: USB is a `UsbAccessory` opened as a
 * `ParcelFileDescriptor` and read as an ordinary file descriptor
 * (`carsetup/setup/UsbConnectionHelper.java`), wireless is a TCP socket after the
 * Bluetooth RFCOMM bootstrap hands over the credentials, and TCP loopback is the
 * Desktop Head Unit over `adb forward`. Nothing above [GalConnection] knows which it has —
 * version negotiation, TLS and the whole bring-up are transport-agnostic.
 */
enum class TransportKind {
    /** Android Open Accessory Protocol over a USB cable. */
    USB,

    /** WiFi Direct (or AP) link after the Bluetooth bootstrap; TLS is reused unchanged. */
    WIRELESS,

    /** TCP loopback on [HeadUnitServer.DHU_PORT][com.vayunmathur.auto.network.HeadUnitServer.DHU_PORT].
     * Dev fallback only: the Desktop Head Unit over an adb forward. */
    TCP_LOOPBACK,
}
