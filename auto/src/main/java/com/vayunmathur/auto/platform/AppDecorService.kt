package com.vayunmathur.auto.platform

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * App-decor bind target, mirroring gearhead's `AppDecorService` (exported):
 * the head unit binds here for app-decor (header, drawer, search) surfaces.
 *
 * Minimal safe handlers: the bind is accepted (exported, like gearhead's)
 * but every entry point is an explicit no-op-with-comment -- the MA car UI
 * draws its own drawer/launcher in `CarDisplay` with no AppDecor template
 * host, so there is no decor service to hand over. A head-unit bind must be
 * safely rejected, never crash, and never open a privileged surface by
 * accident.
 */
class AppDecorService : Service() {

    override fun onBind(intent: Intent?): IBinder? {
        // Explicit no-op-with-comment: the HU decor bind is safely rejected
        // (null) because MA draws CarDisplay drawer/launcher itself rather
        // than hosting AppDecor templates.
        Log.i(TAG, "app-decor bind rejected (rendered-surface model)")
        return null
    }

    private companion object {
        const val TAG = "MaAuto.AppDecor"
    }
}
