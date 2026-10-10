package com.vayunmathur.everysync.data

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A personal E2EE vault this device owns or has joined: everything needed to
 * sync its record log and to invite others, without ever holding another
 * device's secrets.
 *
 * Key material ([keyB64]) is the AES vault key; for joined vaults it arrives
 * sealed in a [RelaySync.Invite](com.vayunmathur.everysync.network.remote.RelaySync.Invite).
 * [ownerKeyB64] is the owner's PQC public bundle, used to verify roster, key
 * epoch, ownership and title signatures client-side — the relay is a pure log.
 */
@Serializable
data class VaultMeta(
    /** Relay record-log id, e.g. `contacts:<uuid>` lives under [vaultId]. */
    val vaultId: String,
    /** One of `contacts`, `calendar`, `passwords`, `notes`. */
    val dataType: String,
    val title: String,
    val keyB64: String,
    val ownerDeviceId: String,
    val ownerKeyB64: String = "",
    val epoch: Int = 0,
    val isOwner: Boolean = true,
)

/** Persists owned + joined [VaultMeta]s as JSON in DataStore (mirrors office's docs index). */
object VaultStore {
    private const val KEY = "everysync_vaults"
    private val json = Json { ignoreUnknownKeys = true }

    fun getAll(context: Context): List<VaultMeta> {
        val raw = DataStoreUtils.getInstance(context).getString(KEY)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<VaultMeta>>(raw) }.getOrDefault(emptyList())
    }

    fun get(context: Context, vaultId: String): VaultMeta? =
        getAll(context).firstOrNull { it.vaultId == vaultId }

    suspend fun upsert(context: Context, meta: VaultMeta) {
        val current = getAll(context).filterNot { it.vaultId == meta.vaultId }
        DataStoreUtils.getInstance(context).setString(KEY, json.encodeToString(current + meta))
    }

    suspend fun remove(context: Context, vaultId: String) {
        val current = getAll(context).filterNot { it.vaultId == vaultId }
        DataStoreUtils.getInstance(context).setString(KEY, json.encodeToString(current))
    }

    suspend fun saveAll(context: Context, vaults: List<VaultMeta>) {
        DataStoreUtils.getInstance(context).setString(KEY, json.encodeToString(vaults))
    }
}
