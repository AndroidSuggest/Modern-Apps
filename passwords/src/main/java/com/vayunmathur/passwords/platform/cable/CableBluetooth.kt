package com.vayunmathur.passwords.platform.cable

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * Pre-flight Bluetooth state for the caBLE flow.
 *
 * [CableActivity] already owns the BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT / POST_NOTIFICATIONS
 * runtime-permission state; these helpers only read the adapter so the activity can prompt to
 * enable Bluetooth *before* starting [CableService] instead of failing silently in its
 * notification.
 */
object CableBluetooth {

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    /** False when the device has no Bluetooth adapter at all. */
    fun isSupported(context: Context): Boolean = adapter(context) != null

    /**
     * True when Bluetooth is on. Returns true when the state cannot be read (e.g. the
     * BLUETOOTH_CONNECT runtime permission was denied) so callers fall through to the existing
     * session behavior instead of blocking on an unknown state.
     */
    @SuppressLint("MissingPermission")
    fun isEnabled(context: Context): Boolean =
        try {
            adapter(context)?.isEnabled == true
        } catch (expected: SecurityException) {
            true
        }
}
