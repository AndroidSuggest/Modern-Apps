package com.vayunmathur.auto.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Head-unit compositor bind target, mirroring gearhead's
 * `CarSystemUiControllerService` (exported): the head unit binds here to
 * drive system-UI surfaces it composites.
 *
 * Minimal safe handlers: the bind is accepted (exported, like gearhead's)
 * but every entry point is an explicit no-op-with-comment -- the MA car
 * surface is the `CarDisplay` virtual display rendered into the ch2 video
 * stream, not HU-composited windows, so there is no system-UI controller to
 * hand over. A head-unit bind must be safely rejected, never crash, and
 * never open a privileged surface by accident.
 */
class CarSystemUiControllerService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        // Explicit no-op-with-comment: the HU compositor bind is safely
        // rejected (null) because MA renders CarDisplay into ch2 video
        // rather than hosting HU-composited system-UI windows.
        Log.i(TAG, "system-ui controller bind rejected (rendered-surface model)")
        return null
    }

    private companion object {
        const val TAG = "MaAuto.SysUi"
    }
}
