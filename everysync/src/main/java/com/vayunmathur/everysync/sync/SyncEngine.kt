package com.vayunmathur.everysync.sync

import android.content.Context
import android.util.Log
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.provider.ProviderRegistry
import com.vayunmathur.everysync.provider.SyncDirection
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * Runs a sync pass for an account: resolve its provider, pull remote changes into
 * the on-device sinks and (two-way) push local DIRTY/DELETED rows back, then
 * record the outcome on the [com.vayunmathur.everysync.auth.AccountConfig].
 */
object SyncEngine {
    private const val TAG = "SyncEngine"

    // One lock per account so overlapping syncs (repeated taps, worker + sync
    // adapter firing together) don't race and, e.g., create duplicate calendars.
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun syncAccount(
        context: Context,
        accountName: String,
        direction: SyncDirection = SyncDirection.BOTH,
    ) {
        val mutex = locks.getOrPut(accountName) { Mutex() }
        if (!mutex.tryLock()) return // a sync for this account is already running
        try {
            val store = AccountStore.getInstance(context)
            val config = store.get(accountName) ?: return
            val provider = ProviderRegistry.get(config.providerId) ?: return
            SyncStatus.begin(accountName)
            try {
                provider.sync(context, config, direction)
                store.upsert(
                    config.copy(
                        lastSyncEpochMs = System.currentTimeMillis(),
                        lastSyncError = null,
                    ),
                )
            } catch (expected: Exception) {
                Log.e(TAG, "Sync failed for $accountName", expected)
                store.upsert(
                    config.copy(
                        lastSyncEpochMs = System.currentTimeMillis(),
                        lastSyncError = expected.message ?: "Sync failed",
                    ),
                )
            } finally {
                SyncStatus.finish(accountName)
            }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun syncAll(context: Context, direction: SyncDirection = SyncDirection.BOTH) {
        AccountStore.getInstance(context).getAll().forEach {
            syncAccount(context, it.accountName, direction)
        }
    }
}
