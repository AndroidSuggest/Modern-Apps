package com.vayunmathur.everysync

import android.app.Application
import com.vayunmathur.everysync.sync.SyncScheduler
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle

/**
 * Ensures the periodic background sync is scheduled whenever the process starts —
 * including when WorkManager or the sync framework spins the app up in the
 * background — so syncing keeps running without the user reopening the UI.
 */
class EverySyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // EXTENDED covers Google GTS (accounts.google.com, apis) + Apple roots (caldav.icloud.com)
        NetworkClient.init(this, TrustBundle.EXTENDED)
        SyncScheduler.schedulePeriodic(this)
    }
}
