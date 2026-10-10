package com.vayunmathur.everysync.sync

import android.content.Context
import com.vayunmathur.everysync.network.remote.RelayCursorStore
import com.vayunmathur.everysync.network.remote.RelaySync
import com.vayunmathur.library.log.Log
import java.io.File
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Note-image blob sync over the encrypted relay.
 *
 * Note bodies ([com.vayunmathur.everysync.data.RemoteNote]) reference images by
 * file name only; the bytes themselves ride this side channel so the record log
 * stays small and mergeable. The channel shares the notes vault key — it is a
 * separate log (`notes-blobs:<vaultId>`) purely so blob envelopes never enter
 * the [RelayMerge] record fold.
 *
 * Each envelope is one JSON [BlobEnvelope]; pull folds the log to the latest
 * envelope per name (LWW by [BlobEnvelope.updatedAt]) and mirrors it to
 * `filesDir/relay_blobs/<vaultId>/<name>`.
 */
object RelayBlobStore {
    private const val TAG = "RelayBlobStore"
    private val json = Json { ignoreUnknownKeys = true }

    /** Relay channel for a vault's image blobs; encrypted with the notes vault key. */
    fun blobChannel(vaultId: String): String = "notes-blobs:$vaultId"

    @Serializable
    data class BlobEnvelope(
        val name: String,
        val sha256: String = "",
        /** Base64 of the raw bytes; empty when [deleted]. */
        val bytesB64: String = "",
        val updatedAt: Long = 0L,
        val deleted: Boolean = false,
    )

    data class BlobSyncResult(
        val pulled: Int,
        val pushed: Int,
        val deleted: Int,
    )

    fun fileFor(context: Context, vaultId: String, name: String): File =
        File(File(context.filesDir, "relay_blobs/$vaultId").apply { mkdirs() }, name)

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun readLocal(context: Context, vaultId: String, name: String): ByteArray? {
        val f = fileFor(context, vaultId, name)
        return if (f.exists()) runCatching { f.readBytes() }.getOrNull() else null
    }

    fun writeLocal(context: Context, vaultId: String, name: String, bytes: ByteArray) {
        runCatching { fileFor(context, vaultId, name).writeBytes(bytes) }
    }

    fun deleteLocal(context: Context, vaultId: String, name: String) {
        runCatching { fileFor(context, vaultId, name).delete() }
    }

    fun encode(name: String, bytes: ByteArray, updatedAt: Long = System.currentTimeMillis()): String =
        json.encodeToString(BlobEnvelope(name, sha256(bytes), Base64.encode(bytes), updatedAt))

    fun encodeDelete(name: String, updatedAt: Long = System.currentTimeMillis()): String =
        json.encodeToString(BlobEnvelope(name, "", "", updatedAt, true))

    fun decode(raw: String): BlobEnvelope? = runCatching {
        json.decodeFromString<BlobEnvelope>(raw)
    }.getOrNull()

    /**
     * Pulls blob envelopes since the persisted cursor, folds to latest per
     * name, and mirrors them into local files. Advances the cursor past the
     * pulled sequence. Returns the envelopes that changed something locally.
     */
    suspend fun pull(
        context: Context,
        vaultId: String,
        vaultKey: ByteArray,
    ): List<BlobEnvelope> {
        val channel = blobChannel(vaultId)
        val since = RelayCursorStore.get(context, "notes", vaultId, channel)
        val result = RelaySync.pullRecords(channel, vaultKey, since)
        if (result.items.isEmpty()) {
            RelayCursorStore.advance(context, "notes", vaultId, channel, result.seq)
            return emptyList()
        }
        val latest = LinkedHashMap<String, BlobEnvelope>()
        for (raw in result.items) {
            val env = decode(raw) ?: continue
            if (env.name.isBlank()) continue
            val prev = latest[env.name]
            if (prev == null || env.updatedAt >= prev.updatedAt) latest[env.name] = env
        }
        val changed = mutableListOf<BlobEnvelope>()
        for (env in latest.values) {
            if (env.deleted) {
                val f = fileFor(context, vaultId, env.name)
                if (f.exists()) {
                    f.delete()
                    changed += env
                }
                continue
            }
            val bytes = runCatching { Base64.decode(env.bytesB64) }.getOrNull() ?: continue
            if (env.sha256.isNotBlank() && sha256(bytes) != env.sha256) {
                Log.status(TAG, "sha mismatch for blob ${env.name}, skipping")
                continue
            }
            val existing = readLocal(context, vaultId, env.name)
            if (existing == null || sha256(existing) != sha256(bytes)) {
                writeLocal(context, vaultId, env.name, bytes)
                changed += env
            }
        }
        RelayCursorStore.advance(context, "notes", vaultId, channel, result.seq)
        return changed
    }

    /**
     * Pushes [blobs] (name to bytes) as new envelopes. Deletions go through
     * [pushDeletes]. Returns the new sequence, or null when the append failed.
     */
    suspend fun push(
        vaultId: String,
        vaultKey: ByteArray,
        blobs: List<Pair<String, ByteArray>>,
        updatedAt: Long = System.currentTimeMillis(),
    ): Int? {
        if (blobs.isEmpty()) return null
        val items = blobs.map { (name, bytes) -> encode(name, bytes, updatedAt) }
        return RelaySync.appendRecords(blobChannel(vaultId), vaultKey, items)
    }

    suspend fun pushDeletes(
        vaultId: String,
        vaultKey: ByteArray,
        names: List<String>,
        updatedAt: Long = System.currentTimeMillis(),
    ): Int? {
        if (names.isEmpty()) return null
        val items = names.map { encodeDelete(it, updatedAt) }
        return RelaySync.appendRecords(blobChannel(vaultId), vaultKey, items)
    }
}
