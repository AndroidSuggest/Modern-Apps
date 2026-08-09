package com.vayunmathur.networklocation

/**
 * Plain domain types shared across the network-location pipeline (scan → gs-loc
 * query → cache → position estimate). Kept free of Android/proto types so the
 * pieces compose cleanly.
 */

/** A radio beacon we can ask Apple to locate: a WiFi access point or a cell tower. */
sealed interface BeaconId {
    data class Wifi(val bssid: String) : BeaconId

    data class Cell(
        val mcc: Int,
        val mnc: Int,
        val cellId: Int,
        val tacOrLac: Int,
    ) : BeaconId
}

/**
 * A beacon whose coordinates are known (from gs-loc or the local cache).
 * [accuracyMeters] is the beacon's own horizontal accuracy radius.
 */
data class BeaconFix(
    val id: BeaconId,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)

/** An estimated device position: the output of the Rust weighted-centroid solver. */
data class DevicePosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)

/** Apple encodes coordinates as fixed-point integers of degrees * 1e8. */
const val APPLE_COORD_SCALE: Double = 1e8

/** gs-loc returns this sentinel accuracy for beacons it has no fix for. */
const val UNKNOWN_ACCURACY: Int = -1
