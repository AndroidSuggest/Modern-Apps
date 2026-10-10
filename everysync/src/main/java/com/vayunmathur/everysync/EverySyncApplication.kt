package com.vayunmathur.everysync

import android.app.Application
import com.vayunmathur.everysync.sync.SyncScheduler
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle

/**
 * Ensures the periodic background sync is scheduled whenever the process starts —
 * including when WorkManager spins the app up in the background — so syncing
 * keeps running without the user reopening the UI.
 */
class EverySyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.init(BuildConfig.DEV_BUILD)
        // FIRST_PARTY covers the relay host (same bundle the office twin uses).
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        SyncScheduler.schedulePeriodic(this)
    }
}
