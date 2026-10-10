package com.vayunmathur.everysync.sync

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists per-vault [RelaySnapshot] baselines in everysync's DataStore.
 *
 * The merge needs a "last agreed state" per record so "present locally,
 * absent remotely" stays decidable from "created locally, not pushed yet".
 * The app-side snapshot tables (passwords/notes/todo `SyncSnapshot`) belong
 * to the app processes and their schemas; the merge driver here keeps its
 * own copy keyed by (vault, kind) so baselines survive app reinstalls of the
 * *other* app and never depend on cross-process reads for correctness.
 *
 * Key layout: `relay_baseline:<vaultId>:<kind>` → JSON list of snapshots.
 */
object VaultBaseline {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredSnapshot(
        val syncId: String,
        val kind: String,
        val contentHash: String,
        val localUpdatedAt: Long,
        val remoteModified: Long,
        val lastSyncedAt: Long,
        val deleted: Boolean = false,
    )

    private fun key(vaultId: String, kind: String) = "relay_baseline:$vaultId:$kind"

    fun load(context: Context, vaultId: String, kind: String): Map<String, RelaySnapshot> {
        val raw = DataStoreUtils.getInstance(context).getString(key(vaultId, kind))
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<List<StoredSnapshot>>(raw)
        }.getOrDefault(emptyList())
            .associate { it.syncId to it.toRelay() }
    }

    suspend fun save(context: Context, vaultId: String, kind: String, snapshots: List<RelaySnapshot>) {
        if (snapshots.isEmpty()) return
        val ds = DataStoreUtils.getInstance(context)
        val current = load(context, vaultId, kind).toMutableMap()
        for (snapshot in snapshots) {
            current[snapshot.syncId] = snapshot
        }
        ds.setString(key(vaultId, kind), json.encodeToString(current.values.map { it.toStored() }))
    }

    suspend fun remove(context: Context, vaultId: String, kind: String, syncIds: List<String>) {
        if (syncIds.isEmpty()) return
        val ds = DataStoreUtils.getInstance(context)
        val current = load(context, vaultId, kind).toMutableMap()
        var changed = false
        for (id in syncIds) {
            if (current.remove(id) != null) changed = true
        }
        if (changed) {
            ds.setString(key(vaultId, kind), json.encodeToString(current.values.map { it.toStored() }))
        }
    }

    suspend fun clearVault(context: Context, vaultId: String, kinds: List<String>) {
        val ds = DataStoreUtils.getInstance(context)
        ds.removeKeys(kinds.map { key(vaultId, it) })
    }

    private fun StoredSnapshot.toRelay() = RelaySnapshot(
        syncId = syncId,
        kind = kind,
        contentHash = contentHash,
        localUpdatedAt = localUpdatedAt,
        remoteModified = remoteModified,
        lastSyncedAt = lastSyncedAt,
        deleted = deleted,
    )

    private fun RelaySnapshot.toStored() = StoredSnapshot(
        syncId = syncId,
        kind = kind,
        contentHash = contentHash,
        localUpdatedAt = localUpdatedAt,
        remoteModified = remoteModified,
        lastSyncedAt = lastSyncedAt,
        deleted = deleted,
    )
}
