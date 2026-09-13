package com.vayunmathur.auto.platform

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.vayunmathur.auto.service.ProjectionService

/**
 * Wireless first-run activity, mirroring gearhead's
 * `WirelessStartupActivity`: the entry point a wireless head unit (or its
 * deep link) lands on before the setup chain exists.
 *
 * Minimal: no UI -- it enters the `BT_START` chain (`CarStartupService`)
 * and finishes in `onCreate`, so back never returns here. A launch without
 * a wireless action still starts the projection listener (harmless when
 * already running) rather than showing a dead screen.
 */
class WirelessStartupActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "wireless startup; entering the BT_START chain")
        startForegroundService(
            Intent(this, CarStartupService::class.java)
                .setAction(CarStartupService.ACTION_BT_START),
        )
        ProjectionService.start(this)
        finish()
    }

    private companion object {
        const val TAG = "MaAuto.WirelessStartup"
    }
}
