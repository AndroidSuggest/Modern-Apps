package com.vayunmathur.auto.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Car-side wireless-setup half, mirroring gearhead's `WirelessSetup`
 * CarService: the head unit binds here for the car half of the wireless
 * handshake (credentials, band, group intent).
 *
 * Minimal safe handlers: the handshake credentials arrive over the CDM /
 * RFCOMM path (see `WifiDirectConnector`), not over a bound binder, so the
 * bind is explicitly rejected with a comment -- never crash, never claim a
 * handshake the phone completes elsewhere. `WirelessSetupSharedService`
 * owns the phone half.
 */
class WirelessSetupCarService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        // Explicit no-op-with-comment: the HU wireless-handshake bind is
        // safely rejected (null) -- credentials ride the CDM/RFCOMM path,
        // not a bound binder (see WirelessSetupSharedService).
        Log.i(TAG, "wireless car-service bind rejected (CDM/RFCOMM model)")
        return null
    }

    private companion object {
        const val TAG = "MaAuto.WirelessCar"
    }
}
