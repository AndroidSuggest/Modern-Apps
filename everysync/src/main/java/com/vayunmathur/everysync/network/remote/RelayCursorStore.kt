package com.vayunmathur.everysync.network.remote

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils

/**
 * Per-channel `since` cursors for relay vaults. Persisted in DataStore so a restart
 * resumes pulling where it left off instead of replaying (and re-merging) history.
 *
 * Key layout: `relay_cursor:<dataType>:<vaultId>:<channel>` → seq (Long).
 */
object RelayCursorStore {
    private fun key(dataType: String, vaultId: String, channel: String): String =
        "relay_cursor:$dataType:$vaultId:$channel"

    suspend fun get(context: Context, dataType: String, vaultId: String, channel: String): Int {
        val v = DataStoreUtils.getInstance(context).getLong(key(dataType, vaultId, channel))
        return v?.toInt() ?: 0
    }

    suspend fun set(context: Context, dataType: String, vaultId: String, channel: String, seq: Int) {
        DataStoreUtils.getInstance(context).setLong(key(dataType, vaultId, channel), seq.toLong())
    }

    /** Advances the cursor only forward; returns true when it moved. */
    suspend fun advance(
        context: Context,
        dataType: String,
        vaultId: String,
        channel: String,
        seq: Int,
    ): Boolean {
        val ds = DataStoreUtils.getInstance(context)
        val k = key(dataType, vaultId, channel)
        val cur = ds.getLong(k)?.toInt() ?: 0
        if (seq <= cur) return false
        ds.setLong(k, seq.toLong())
        return true
    }

    suspend fun clearVault(context: Context, dataType: String, vaultId: String, channels: List<String>) {
        val ds = DataStoreUtils.getInstance(context)
        ds.removeKeys(channels.map { key(dataType, vaultId, it) })
    }
}
