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

    private const val KEY_ENABLED = "kdbx_sync_enabled"
    private const val KEY_URI = "kdbx_sync_document_uri"
    private const val KEY_LAST_SYNC = "kdbx_sync_last_time"
    private const val KEY_STATUS = "kdbx_sync_last_status"
    private const val KEY_ERROR = "kdbx_sync_last_error"

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
}
