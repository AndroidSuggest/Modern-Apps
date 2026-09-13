package com.vayunmathur.auto.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.vayunmathur.auto.network.UsbConnector

/**
 * Receives the USB permission answer and accessory detach while no activity is up.
 *
 * The permission round-trip outlives the tap that started it — the user may grant from
 * the system dialog while the app is backgrounded — so the answer lands here rather
 * than in `MainActivity`. Both outcomes funnel into [UsbConnector], which owns the
 * [com.vayunmathur.auto.protocol.UsbSession] and mirrors it to the pairing UI.
 * The force-start chain action (`CarStartupService` BT_START chain) funnels there too.
 */
class UsbReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbConnector.ACTION_USB_PERMISSION &&
            intent.action != UsbManager.ACTION_USB_ACCESSORY_DETACHED &&
            intent.action != UsbConnector.ACTION_USB_ACCESSORY_FORCE_START
        ) {
            return
        }
        if (!UsbConnector.onPermissionResult(context, intent)) {
            UsbConnector.onAccessoryIntent(context, intent)
        }
    }
}
