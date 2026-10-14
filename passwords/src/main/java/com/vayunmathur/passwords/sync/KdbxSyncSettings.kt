package com.vayunmathur.passwords.sync

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow

/**
 * User-facing state of the kdbx sync. The getters are suspend because the background
 * worker may run before DataStore has hydrated; the flows back the settings screen.
 */
object KdbxSyncSettings {
    const val STATUS_OK = "ok"
    const val STATUS_ERROR = "error"
    const val STATUS_QUARANTINED = "quarantined"

    const val QUARANTINE_FOREIGN_DELETE = "foreign_delete"

    private const val KEY_ENABLED = "kdbx_sync_enabled"
    private const val KEY_URI = "kdbx_sync_document_uri"
    private const val KEY_LAST_SYNC = "kdbx_sync_last_time"
    private const val KEY_STATUS = "kdbx_sync_last_status"
    private const val KEY_ERROR = "kdbx_sync_last_error"
    private const val KEY_QUARANTINE = "kdbx_sync_quarantine"
    private const val KEY_QUARANTINE_AT = "kdbx_sync_quarantine_at"

    private fun store(context: Context) = DataStoreUtils.getInstance(context)

    suspend fun enabled(context: Context): Boolean = store(context).getBooleanAwait(KEY_ENABLED)

    suspend fun setEnabled(context: Context, value: Boolean) =
        store(context).setBoolean(KEY_ENABLED, value)

    fun enabledFlow(context: Context): Flow<Boolean> = store(context).booleanFlow(KEY_ENABLED)

    suspend fun documentUri(context: Context): String? =
        store(context).getStringAwait(KEY_URI)?.takeIf { it.isNotBlank() }

    suspend fun setDocumentUri(context: Context, uri: String) =
        store(context).setString(KEY_URI, uri)

    fun documentUriFlow(context: Context): Flow<String> = store(context).stringFlow(KEY_URI)

    fun lastSyncFlow(context: Context): Flow<Long> = store(context).longFlow(KEY_LAST_SYNC, 0L)

    fun statusFlow(context: Context): Flow<String> = store(context).stringFlow(KEY_STATUS)

    fun errorFlow(context: Context): Flow<String> = store(context).stringFlow(KEY_ERROR)

    suspend fun recordSuccess(context: Context) {
        store(context).setLong(KEY_LAST_SYNC, System.currentTimeMillis())
        store(context).setString(KEY_STATUS, STATUS_OK)
        store(context).setString(KEY_ERROR, "")
    }

    suspend fun recordFailure(context: Context, error: String) {
        store(context).setString(KEY_STATUS, STATUS_ERROR)
        store(context).setString(KEY_ERROR, error)
    }

    /**
     * Enters quarantine: a foreign client appears to have deleted baseline
     * entries from the file, which is ambiguous by nature of the KDBX format
     * (a foreign client cannot write tombstones). While quarantined the sync
     * changes nothing on either side until the user reviews and explicitly
     * resolves it from the settings screen.
     */
    suspend fun recordQuarantine(context: Context, reason: String) {
        store(context).setString(KEY_STATUS, STATUS_QUARANTINED)
        store(context).setString(KEY_QUARANTINE, reason)
        store(context).setLong(KEY_QUARANTINE_AT, System.currentTimeMillis())
    }

    suspend fun clearQuarantine(context: Context) {
        store(context).setString(KEY_QUARANTINE, "")
        store(context).setLong(KEY_QUARANTINE_AT, 0L)
        if (quarantineStatus(context) == STATUS_QUARANTINED) {
            store(context).setString(KEY_STATUS, "")
        }
    }

    private suspend fun quarantineStatus(context: Context): String =
        store(context).getStringAwait(KEY_STATUS).orEmpty()

    fun quarantineFlow(context: Context): Flow<String> = store(context).stringFlow(KEY_QUARANTINE)

    fun quarantineAtFlow(context: Context): Flow<Long> = store(context).longFlow(KEY_QUARANTINE_AT, 0L)
}
