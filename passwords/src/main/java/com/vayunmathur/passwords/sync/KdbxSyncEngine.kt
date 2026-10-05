package com.vayunmathur.passwords.sync

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.room3.withWriteTransaction
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordDao
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.data.SyncSnapshot
import com.vayunmathur.passwords.data.SyncSnapshotDao
import com.vayunmathur.passwords.data.newSyncId
import com.vayunmathur.passwords.util.KdbxNative
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

sealed interface KdbxSyncResult {
    data class Success(
        val pushed: Int,
        val pulled: Int,
        val deletedLocal: Int,
        val deletedRemote: Int,
    ) : KdbxSyncResult

    data object NotConfigured : KdbxSyncResult
    data object FileMissing : KdbxSyncResult
    data object WrongPassword : KdbxSyncResult
    data object VerifyFailed : KdbxSyncResult
    data class Error(val message: String) : KdbxSyncResult
}

/** Short machine-readable reason, stored in settings and rendered by the settings screen. */
fun KdbxSyncResult.errorCode(): String = when (this) {
    KdbxSyncResult.FileMissing -> "file_missing"
    KdbxSyncResult.WrongPassword -> "wrong_password"
    KdbxSyncResult.VerifyFailed -> "verify_failed"
    is KdbxSyncResult.Error -> message
    else -> ""
}

/** The kdbx file. Abstracted so the engine can be exercised without SAF. */
interface KdbxDocument {
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}

/** kdbx (de)serialisation. Abstracted so the engine can be exercised without JNI. */
interface KdbxCodec {
    /** Returns null when the password is wrong or the payload is not a vault. */
    fun read(password: String, bytes: ByteArray): List<Map<String, String>>?
    fun write(password: String, entries: List<Map<String, String>>): ByteArray?
}

/**
 * Bidirectional sync between the encrypted Room database and one kdbx file.
 *
 * The whole merge is computed in memory. Nothing is mutated until the remote has been
 * read, decrypted, merged, re-encoded and verified — so a missing, unreadable or
 * wrongly-keyed file can never wipe the local vault.
 */
class KdbxSyncEngine(
    private val passwordDao: PasswordDao,
    private val passkeyDao: PasskeyDao,
    private val snapshotDao: SyncSnapshotDao,
    private val codec: KdbxCodec,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    constructor(db: PasswordDatabase, codec: KdbxCodec) : this(
        db.passwordDao(),
        db.passkeyDao(),
        db.syncSnapshotDao(),
        codec,
        { block -> db.withWriteTransaction { block() } },
    )

    constructor(repository: PasswordRepository, codec: KdbxCodec) : this(
        passwordDao = object : PasswordDao() {
            override fun getAllFlow(): Flow<List<Password>> = repository.passwords
            override suspend fun getAll(): List<Password> = repository.getAllPasswords()
            override fun getByIdFlow(id: Long): Flow<Password?> = repository.getPasswordByIdFlow(id)
            override suspend fun getById(id: Long): Password? = repository.getPasswordById(id)
            override suspend fun upsertRaw(value: Password): Long = repository.upsertPasswordRaw(value)
            override suspend fun delete(value: Password): Int = repository.deletePassword(value)
        },
        passkeyDao = object : PasskeyDao() {
            override fun getAllFlow(): Flow<List<Passkey>> = repository.passkeys
            override suspend fun getAll(): List<Passkey> = repository.getAllPasskeys()
            override suspend fun getByRpId(rpId: String): List<Passkey> = repository.getPasskeysByRpId(rpId)
            override suspend fun getByCredentialId(credentialId: String): Passkey? =
                repository.getPasskeyByCredentialId(credentialId)
            override suspend fun upsertRaw(passkey: Passkey): Long = repository.upsertPasskeyRaw(passkey)
            override suspend fun delete(passkey: Passkey): Int = repository.deletePasskey(passkey)
        },
        snapshotDao = object : SyncSnapshotDao {
            override suspend fun getAll(): List<SyncSnapshot> = repository.getAllSnapshots()
            override suspend fun upsert(snapshot: SyncSnapshot) = repository.upsertSnapshot(snapshot)
            override suspend fun deleteBySyncId(syncId: String) = repository.deleteSnapshotBySyncId(syncId)
            override suspend fun deleteAll() = repository.deleteAllSnapshots()
        },
        codec = codec,
        inTransaction = { repository.withTransaction(it) },
    )

    private val passwordKind = object : EntityKind<Password>() {
        override val kind = SyncSnapshot.KIND_PASSWORD
        override fun syncId(e: Password) = e.syncId
        override fun updatedAt(e: Password) = e.updatedAt
        override fun rowId(e: Password) = e.id
        override fun fields(e: Password) = EntryMapper.toFields(e)
        override fun entity(fields: Map<String, String>) = EntryMapper.toPassword(fields)
        override fun contentKey(e: Password) = EntryMapper.contentKey(e)
        override fun rebind(e: Password, rowId: Long, syncId: String) = e.copy(id = rowId, syncId = syncId)
        override suspend fun persist(e: Password) { passwordDao.upsertRaw(e) }
        override suspend fun delete(e: Password) { passwordDao.delete(e) }
    }

    private val passkeyKind = object : EntityKind<Passkey>() {
        override val kind = SyncSnapshot.KIND_PASSKEY
        override fun syncId(e: Passkey) = e.syncId
        override fun updatedAt(e: Passkey) = e.updatedAt
        override fun rowId(e: Passkey) = e.id
        override fun fields(e: Passkey) = EntryMapper.toFields(e)
        override fun entity(fields: Map<String, String>) = EntryMapper.toPasskey(fields)
        override fun contentKey(e: Passkey) = EntryMapper.contentKey(e)
        override fun rebind(e: Passkey, rowId: Long, syncId: String) = e.copy(id = rowId, syncId = syncId)
        override suspend fun persist(e: Passkey) { passkeyDao.upsertRaw(e) }
        override suspend fun delete(e: Passkey) { passkeyDao.delete(e) }
    }

    suspend fun sync(
        document: KdbxDocument,
        vaultPassword: String,
        onBackup: (ByteArray) -> Unit = {},
    ): KdbxSyncResult {
        val remoteBytes = try {
            document.read()
        } catch (_: FileNotFoundException) {
            return KdbxSyncResult.FileMissing
        }
        // A brand new SAF document is zero bytes; that is an empty vault, not a failure.
        val remoteEntries = if (remoteBytes.isEmpty()) {
            emptyList()
        } else {
            codec.read(vaultPassword, remoteBytes) ?: return KdbxSyncResult.WrongPassword
        }

        val now = System.currentTimeMillis()
        val baseline = snapshotDao.getAll().associateBy { it.syncId }
        val (remotePasskeys, remotePasswords) = remoteEntries.partition { EntryMapper.isPasskeyEntry(it) }

        val passwordPlan = merge(
            passwordKind,
            passwordDao.getAll(),
            remotePasswords,
            baseline.filterValues { it.kind == SyncSnapshot.KIND_PASSWORD },
            now,
        )
        val passkeyPlan = merge(
            passkeyKind,
            passkeyDao.getAll(),
            remotePasskeys,
            baseline.filterValues { it.kind == SyncSnapshot.KIND_PASSKEY },
            now,
        )

        if (passwordPlan.remoteChanged || passkeyPlan.remoteChanged) {
            val merged = passwordPlan.remoteFields + passkeyPlan.remoteFields
            val bytes = codec.write(vaultPassword, merged)
                ?: return KdbxSyncResult.Error("Failed to encode KDBX")
            // A SAF single-document write truncates, so prove the payload parses first.
            val verified = codec.read(vaultPassword, bytes)
            if (verified == null || verified.size != merged.size) return KdbxSyncResult.VerifyFailed
            if (remoteBytes.isNotEmpty()) onBackup(remoteBytes)
            try {
                document.write(bytes)
            } catch (expected: IOException) {
                return KdbxSyncResult.Error(expected.message ?: "Failed to write KDBX file")
            }
        }

        inTransaction {
            applyPlan(passwordKind, passwordPlan)
            applyPlan(passkeyKind, passkeyPlan)
        }

        return KdbxSyncResult.Success(
            pushed = passwordPlan.pushed + passkeyPlan.pushed,
            pulled = passwordPlan.pulled + passkeyPlan.pulled,
            deletedLocal = passwordPlan.deletedLocal + passkeyPlan.deletedLocal,
            deletedRemote = passwordPlan.deletedRemote + passkeyPlan.deletedRemote,
        )
    }

    private suspend fun <T> applyPlan(kind: EntityKind<T>, plan: Plan<T>) {
        for (entity in plan.localDeletes) kind.delete(entity)
        for (entity in plan.localUpserts) kind.persist(entity)
        for (syncId in plan.removedSyncIds) snapshotDao.deleteBySyncId(syncId)
        for (snapshot in plan.snapshots) snapshotDao.upsert(snapshot)
    }

    private fun <T> merge(
        kind: EntityKind<T>,
        localEntities: List<T>,
        remoteEntries: List<Map<String, String>>,
        baseline: Map<String, SyncSnapshot>,
        now: Long,
    ): Plan<T> {
        val accumulator = MergeAccumulator<T>()
        val local = indexLocal(kind, localEntities, accumulator)
        val remote = indexRemote(kind, local, remoteEntries, accumulator)
        reconcileAll(kind, local, remote, baseline, now, accumulator)
        return accumulator.toPlan()
    }

    private class MergeAccumulator<T> {
        val localUpserts = mutableListOf<T>()
        val localDeletes = mutableListOf<T>()
        val remoteOut = mutableListOf<Map<String, String>>()
        val snapshots = mutableListOf<SyncSnapshot>()
        val removed = mutableListOf<String>()
        val freshlyIdentified = mutableSetOf<String>()
        var remoteChanged = false
        var pushed = 0
        var pulled = 0
        var deletedLocal = 0
        var deletedRemote = 0

        fun toPlan(): Plan<T> = Plan(
            localUpserts = localUpserts,
            localDeletes = localDeletes,
            remoteFields = remoteOut,
            snapshots = snapshots,
            removedSyncIds = removed,
            remoteChanged = remoteChanged,
            pushed = pushed,
            pulled = pulled,
            deletedLocal = deletedLocal,
            deletedRemote = deletedRemote,
        )
    }

    private fun <T> indexLocal(
        kind: EntityKind<T>,
        localEntities: List<T>,
        accumulator: MergeAccumulator<T>,
    ): LinkedHashMap<String, T> {
        // Rows written before the sync columns existed have no identity yet.
        val local = LinkedHashMap<String, T>()
        val freshlyIdentified = mutableSetOf<String>()
        for (raw in localEntities) {
            val entity = if (kind.syncId(raw).isBlank()) {
                kind.rebind(raw, kind.rowId(raw), newSyncId())
                    .also { freshlyIdentified += kind.syncId(it) }
            } else {
                raw
            }
            local[kind.syncId(entity)] = entity
        }
        accumulator.freshlyIdentified.addAll(freshlyIdentified)
        return local
    }

    private fun <T> indexRemote(
        kind: EntityKind<T>,
        local: Map<String, T>,
        remoteEntries: List<Map<String, String>>,
        accumulator: MergeAccumulator<T>,
    ): LinkedHashMap<String, Map<String, String>> {
        val remote = LinkedHashMap<String, Map<String, String>>()
        val unidentified = mutableListOf<Map<String, String>>()
        for (entry in remoteEntries) {
            storeIdentified(remote, unidentified, entry)
        }
        adoptUnidentified(kind, local, remote, unidentified, accumulator)
        return remote
    }

    private fun storeIdentified(
        remote: MutableMap<String, Map<String, String>>,
        unidentified: MutableList<Map<String, String>>,
        entry: Map<String, String>,
    ) {
        val id = entry[EntryMapper.FIELD_SYNC_ID]?.takeIf { it.isNotBlank() }
        if (id != null && id !in remote) remote[id] = entry else unidentified += entry
    }

    private fun <T> adoptUnidentified(
        kind: EntityKind<T>,
        local: Map<String, T>,
        remote: LinkedHashMap<String, Map<String, String>>,
        unidentified: List<Map<String, String>>,
        accumulator: MergeAccumulator<T>,
    ) {
        // Entries written by another client carry no _SyncId. Adopt the identity of the
        // local row they describe, otherwise pointing the app at an existing KeePassXC
        // vault would duplicate every entry in it.
        val adoptableByContentKey = local.values.filter { kind.syncId(it) !in remote }
            .associateBy({ kind.contentKey(it) }, { kind.syncId(it) })
        val claimed = mutableSetOf<String>()
        for (entry in unidentified) {
            val match = adoptableByContentKey[kind.contentKey(kind.entity(entry))]
            val id = match?.takeIf { it !in claimed && it !in remote } ?: newSyncId()
            claimed += id
            remote[id] = entry + (EntryMapper.FIELD_SYNC_ID to id)
            accumulator.remoteChanged = true
        }
    }

    private fun <T> reconcileAll(
        kind: EntityKind<T>,
        local: Map<String, T>,
        remote: Map<String, Map<String, String>>,
        baseline: Map<String, SyncSnapshot>,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        for (id in local.keys + remote.keys) {
            reconcileOne(kind, id, local[id], remote[id], baseline[id], now, accumulator)
        }
    }

    private fun <T> reconcileOne(
        kind: EntityKind<T>,
        id: String,
        localEntity: T?,
        remoteEntry: Map<String, String>?,
        base: SyncSnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        if (localEntity != null && remoteEntry != null) {
            reconcilePresent(kind, id, localEntity, remoteEntry, base, now, accumulator)
            return
        }
        if (localEntity != null) {
            reconcileLocalOnly(kind, id, localEntity, base, now, accumulator)
            return
        }
        reconcileRemoteOnly(kind, id, remoteEntry!!, base, now, accumulator)
    }

    private fun <T> reconcilePresent(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        remoteEntry: Map<String, String>,
        base: SyncSnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        val localFields = kind.fields(localEntity)
        val remoteEntity = kind.entity(remoteEntry)
        val remoteFields = kind.fields(remoteEntity)
        val localHash = EntryMapper.contentHash(localFields)
        val remoteHash = EntryMapper.contentHash(remoteFields)

        if (localHash == remoteHash) {
            accumulator.remoteOut += remoteEntry
            if (id in accumulator.freshlyIdentified) accumulator.localUpserts += localEntity
            accumulator.snapshots += kind.snapshot(
                id,
                localHash,
                kind.updatedAt(localEntity),
                kind.updatedAt(remoteEntity),
                now,
            )
            return
        }
        reconcileDiverged(kind, id, localEntity, remoteEntity, remoteEntry, localFields, base, now, accumulator)
    }

    private fun <T> reconcileDiverged(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        remoteEntity: T,
        remoteEntry: Map<String, String>,
        localFields: Map<String, String>,
        base: SyncSnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        val localHash = EntryMapper.contentHash(localFields)
        val remoteHash = EntryMapper.contentHash(kind.fields(remoteEntity))
        val localDirty = base == null || localHash != base.contentHash
        val remoteDirty = base == null || remoteHash != base.contentHash
        val localWins = when {
            localDirty && remoteDirty -> kind.updatedAt(localEntity) >= kind.updatedAt(remoteEntity)
            else -> localDirty
        }
        if (localWins) {
            applyLocalWins(kind, id, localEntity, remoteEntry, localFields, localHash, now, accumulator)
        } else {
            applyRemoteWins(kind, id, localEntity, remoteEntity, remoteEntry, remoteHash, now, accumulator)
        }
    }

    private fun <T> applyLocalWins(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        remoteEntry: Map<String, String>,
        localFields: Map<String, String>,
        localHash: String,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        // Keep fields no client of ours understands (Notes, Tags, ...).
        accumulator.remoteOut += remoteEntry.filterKeys { it !in EntryMapper.OWNED_KEYS } + localFields
        accumulator.remoteChanged = true
        accumulator.pushed++
        if (id in accumulator.freshlyIdentified) accumulator.localUpserts += localEntity
        val stamp = kind.updatedAt(localEntity)
        accumulator.snapshots += kind.snapshot(id, localHash, stamp, stamp, now)
    }

    private fun <T> applyRemoteWins(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        remoteEntity: T,
        remoteEntry: Map<String, String>,
        remoteHash: String,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        // Pulled rows keep the remote timestamp, so the next cycle sees them
        // as clean rather than bouncing the change back.
        accumulator.localUpserts += kind.rebind(remoteEntity, kind.rowId(localEntity), id)
        accumulator.remoteOut += remoteEntry
        accumulator.pulled++
        val stamp = kind.updatedAt(remoteEntity)
        accumulator.snapshots += kind.snapshot(id, remoteHash, stamp, stamp, now)
    }

    private fun <T> reconcileLocalOnly(
        kind: EntityKind<T>,
        id: String,
        localEntity: T,
        base: SyncSnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        if (base != null) {
            accumulator.localDeletes += localEntity
            accumulator.removed += id
            accumulator.deletedLocal++
            return
        }
        val localFields = kind.fields(localEntity)
        accumulator.remoteOut += localFields
        accumulator.remoteChanged = true
        accumulator.pushed++
        if (id in accumulator.freshlyIdentified) accumulator.localUpserts += localEntity
        val stamp = kind.updatedAt(localEntity)
        accumulator.snapshots += kind.snapshot(
            id,
            EntryMapper.contentHash(localFields),
            stamp,
            stamp,
            now,
        )
    }

    private fun <T> reconcileRemoteOnly(
        kind: EntityKind<T>,
        id: String,
        remoteEntry: Map<String, String>,
        base: SyncSnapshot?,
        now: Long,
        accumulator: MergeAccumulator<T>,
    ) {
        if (base != null) {
            accumulator.removed += id
            accumulator.deletedRemote++
            accumulator.remoteChanged = true
            return
        }
        val remoteEntity = kind.entity(remoteEntry)
        accumulator.localUpserts += kind.rebind(remoteEntity, 0, id)
        accumulator.remoteOut += remoteEntry
        accumulator.pulled++
        val stamp = kind.updatedAt(remoteEntity)
        accumulator.snapshots += kind.snapshot(
            id,
            EntryMapper.contentHash(kind.fields(remoteEntity)),
            stamp,
            stamp,
            now,
        )
    }
}

private abstract class EntityKind<T> {
    abstract val kind: String
    abstract fun syncId(e: T): String
    abstract fun updatedAt(e: T): Long
    abstract fun rowId(e: T): Long
    abstract fun fields(e: T): Map<String, String>
    abstract fun entity(fields: Map<String, String>): T
    abstract fun contentKey(e: T): String
    abstract fun rebind(e: T, rowId: Long, syncId: String): T
    abstract suspend fun persist(e: T)
    abstract suspend fun delete(e: T)

    fun snapshot(syncId: String, hash: String, localUpdatedAt: Long, remoteModified: Long, now: Long) =
        SyncSnapshot(syncId, kind, hash, localUpdatedAt, remoteModified, now)
}

private class Plan<T>(
    val localUpserts: List<T>,
    val localDeletes: List<T>,
    val remoteFields: List<Map<String, String>>,
    val snapshots: List<SyncSnapshot>,
    val removedSyncIds: List<String>,
    val remoteChanged: Boolean,
    val pushed: Int,
    val pulled: Int,
    val deletedLocal: Int,
    val deletedRemote: Int,
)

object NativeKdbxCodec : KdbxCodec {
    override fun read(password: String, bytes: ByteArray): List<Map<String, String>>? {
        val json = KdbxNative.nativeImport(password, bytes) ?: return null
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val entry = array.getJSONObject(i)
            buildMap { for (key in entry.keys()) put(key, entry.getString(key)) }
        }
    }

    override fun write(password: String, entries: List<Map<String, String>>): ByteArray? {
        val array = JSONArray()
        for (entry in entries) array.put(JSONObject(entry as Map<*, *>))
        return KdbxNative.nativeExport(password, array.toString())
    }
}

class SafKdbxDocument(private val context: Context, private val uri: Uri) : KdbxDocument {
    override fun read(): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("Cannot open $uri")

    override fun write(bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException("Cannot write $uri")
    }
}

/** Runs a sync from the persisted settings, recording the outcome for the settings screen. */
suspend fun runKdbxSync(context: Context, db: PasswordDatabase): KdbxSyncResult {
    if (!KdbxSyncSettings.enabled(context)) return KdbxSyncResult.NotConfigured
    val uri = KdbxSyncSettings.documentUri(context) ?: return KdbxSyncResult.NotConfigured
    val passwordHelper = KdbxPasswordHelper(context)
    if (!passwordHelper.isKeyGenerated()) return KdbxSyncResult.NotConfigured

    val result = runSyncCatching {
        KdbxSyncEngine(db, NativeKdbxCodec).sync(
            document = SafKdbxDocument(context, uri.toUri()),
            vaultPassword = passwordHelper.getPassphrase(),
            onBackup = { previous -> File(context.filesDir, BACKUP_FILE_NAME).writeBytes(previous) },
        )
    }

    when (result) {
        is KdbxSyncResult.Success -> KdbxSyncSettings.recordSuccess(context)
        KdbxSyncResult.NotConfigured -> Unit
        else -> KdbxSyncSettings.recordFailure(context, result.errorCode())
    }
    return result
}

private suspend fun runSyncCatching(block: suspend () -> KdbxSyncResult): KdbxSyncResult {
    return try {
        block()
    } catch (expected: IllegalStateException) {
        KdbxSyncResult.Error(expected.message ?: expected.javaClass.simpleName)
    }
}

/** Repository-based overload — preferred; avoids direct PasswordDatabase access. */
suspend fun runKdbxSync(context: Context, repository: PasswordRepository): KdbxSyncResult {
    if (!KdbxSyncSettings.enabled(context)) return KdbxSyncResult.NotConfigured
    val uri = KdbxSyncSettings.documentUri(context) ?: return KdbxSyncResult.NotConfigured
    val passwordHelper = KdbxPasswordHelper(context)
    if (!passwordHelper.isKeyGenerated()) return KdbxSyncResult.NotConfigured

    val result = runSyncCatching {
        KdbxSyncEngine(repository, NativeKdbxCodec).sync(
            document = SafKdbxDocument(context, uri.toUri()),
            vaultPassword = passwordHelper.getPassphrase(),
            onBackup = { previous -> File(context.filesDir, BACKUP_FILE_NAME).writeBytes(previous) },
        )
    }

    when (result) {
        is KdbxSyncResult.Success -> KdbxSyncSettings.recordSuccess(context)
        KdbxSyncResult.NotConfigured -> Unit
        else -> KdbxSyncSettings.recordFailure(context, result.errorCode())
    }
    return result
}

private const val BACKUP_FILE_NAME = "kdbx-sync-backup.kdbx"
