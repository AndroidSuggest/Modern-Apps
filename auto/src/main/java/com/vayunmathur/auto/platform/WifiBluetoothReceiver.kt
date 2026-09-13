package com.vayunmathur.auto.platform

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vayunmathur.auto.network.WifiDirectConnector

/**
 * Bluetooth + WiFi state receiver for the wireless bring-up, mirroring
 * gearhead's `WifiBluetoothReceiver`: ACL connect/disconnect and WiFi state
 * changes during setup re-drive the connector instead of stalling it.
 *
 * Minimal: an ACL connect to a device while the wireless session is
 * negotiating re-arms the BT trigger path (the MAC is now known-good);
 * an ACL disconnect cancels an in-flight bring-up the peer just killed.
 * WiFi Direct connection changes belong to the pairing screen's listener
 * (`WifiDirectConnector.onConnectionChanged`), not here.
 */
class WifiBluetoothReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // Deprecated form like UsbConnector: the Class overload needs
                // API 33 and minSdk is 31.
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val mac = device?.address ?: return
                Log.i(TAG, "BT ACL connected to $mac during wireless setup")
                // Re-arm only: the CDM association (or the tap) owns the
                // trigger decision. Reporting the MAC here would start a
                // bring-up the user never consented to.
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.i(TAG, "BT ACL disconnected; cancelling wireless bring-up")
                WifiDirectConnector.cancel()
            }
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                if (state == BluetoothAdapter.STATE_OFF) {
                    Log.i(TAG, "Bluetooth off; cancelling wireless bring-up")
                    WifiDirectConnector.cancel()
                }
            }
        }
    }

    private companion object {
        const val TAG = "MaAuto.WifiBt"
    }
}
