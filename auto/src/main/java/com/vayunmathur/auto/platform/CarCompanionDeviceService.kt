package com.vayunmathur.auto.platform

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.vayunmathur.auto.network.WifiDirectConnector

/**
 * The companion-device trigger for wireless projection, mirroring gearhead's
 * `CarProcessCompanionDeviceService` (CDM, `:car`): when the platform
 * associates this phone with the car's head unit (automotive-projection
 * device profile), the association fires the wireless bring-up instead of
 * the pairing screen's manual tap.
 *
 * Wiring: [onDeviceAppeared] hands the associated BT MAC to
 * `WifiDirectConnector.startFromAssociation`, which arms the BT trigger path
 * (RFCOMM fallback, per-stage timeouts) and proceeds down the same WiFi
 * Direct group flow the tap uses. [onDeviceDisappeared] cancels an
 * in-flight bring-up. Until an association exists, the pairing-screen tap
 * stays the stand-in trigger (`WifiDirectConnector.start`) and this service
 * idles -- presence callbacks never fire without a CDM profile grant.
 *
 * Requires API 33+ for [onDeviceAppeared] (the service base exists on 31+,
 * presence callbacks do not); guarded, never called below.
 */
class CarCompanionDeviceService : CompanionDeviceService() {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        // The CDM association names the head unit: its MAC arms the wireless
        // trigger path. Display name is for the trace only -- the MAC is the
        // identity the RFCOMM fallback dials.
        val mac = associationInfo.deviceMacAddress?.toString()
        Log.i(TAG, "companion head unit appeared: ${associationInfo.displayName} ($mac)")
        if (mac.isNullOrBlank()) {
            Log.w(TAG, "companion association without a MAC; wireless trigger skipped")
            return
        }
        WifiDirectConnector.startFromAssociation(this, mac)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i(TAG, "companion head unit disappeared: ${associationInfo.displayName}")
        WifiDirectConnector.cancel()
    }

    private companion object {
        const val TAG = "MaAuto.Cdm"
    }
}
