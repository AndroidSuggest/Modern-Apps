package com.vayunmathur.findfamily.tracker

import java.util.UUID

/**
 * BLE contract shared between the app and the tracker firmware (ESP32/nRF52 +
 * Qorvo DW3110). Documented here so the embedded side has an exact spec.
 *
 * ## Advertising (crowd-finding)
 * A provisioned tracker advertises [SERVICE_UUID] with **service data** laid out as:
 * ```
 * [16B epochId][1B battery%]
 * ```
 * where `epochId = TrackerProtocol.epochId(secret, currentEpoch)` and `battery` is
 * 0..100. The id rotates every [TrackerProtocol.EPOCH_SECONDS]; no static id is ever
 * broadcast.
 *
 * ## Binding (GATT)
 * An unprovisioned tracker advertises [UNPROVISIONED_SERVICE_UUID] and exposes a
 * GATT server with:
 *  - [PROVISION_CHARACTERISTIC_UUID] (write): the phone writes the provisioning blob
 *    ```
 *    [8B trackerUserId BE][32B beaconSecret]
 *    ```
 *    After a successful write the tracker persists these, stops advertising the
 *    unprovisioned service, and begins the rotating beacon above.
 *  - [UWB_SESSION_CHARACTERISTIC_UUID] (write/notify): per-find FiRa session params
 *    for phone-native UWB precision finding (see [TrackerUwbGatt]).
 */
object TrackerBle {
    /** Service a provisioned tracker advertises its rotating beacon under. */
    val SERVICE_UUID: UUID = UUID.fromString("6b1d2f00-4b3a-4c7e-9a10-1f2e3d4c5b6a")

    /** Service an unprovisioned (pairing-mode) tracker advertises. */
    val UNPROVISIONED_SERVICE_UUID: UUID = UUID.fromString("6b1d2f01-4b3a-4c7e-9a10-1f2e3d4c5b6a")

    /** GATT characteristic the phone writes the provisioning blob to. */
    val PROVISION_CHARACTERISTIC_UUID: UUID = UUID.fromString("6b1d2f02-4b3a-4c7e-9a10-1f2e3d4c5b6a")

    /** GATT characteristic carrying per-find FiRa session params for UWB ranging. */
    val UWB_SESSION_CHARACTERISTIC_UUID: UUID = UUID.fromString("6b1d2f03-4b3a-4c7e-9a10-1f2e3d4c5b6a")
}

/** One crowd-finding beacon sighting heard by a finder phone. */
data class TrackerSighting(
    /** The 16-byte rotating id from the advertisement; used verbatim as the report key. */
    val epochId: ByteArray,
    /** Battery percent from the advertisement, or -1 when absent. */
    val battery: Int,
    /** Received signal strength (dBm) — a coarse proximity hint. */
    val rssi: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is TrackerSighting && epochId.contentEquals(other.epochId) &&
            battery == other.battery && rssi == other.rssi

    override fun hashCode(): Int = epochId.contentHashCode() * 31 + battery * 31 + rssi
}
