package com.vayunmathur.todo.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import com.vayunmathur.library.log.Log
import com.vayunmathur.todo.data.SyncSnapshot
import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Relay IPC surface for the todo vault, consumed by the everysync app's
 * TodoSink (same process signature, different app).
 *
 * Exposes `content://com.vayunmathur.todo.relay/vault/<id>/todos` and
 * `.../snapshots`. The vault id segment is accepted but ignored: this app
 * holds a single vault. Every row carries the relay columns
 * `syncId, updatedAt, deleted, payloadJson, contentHash`.
 *
 * `payloadJson` is [RelayTodoJson] — a field-for-field mirror of the relay
 * envelope (`RemoteTodo` in everysync): the bytes the relay log carries, also
 * hashed by the merge driver, so this provider emits and accepts that shape
 * rather than the native [Todo] JSON. This provider must not depend on
 * everysync, so the shape is duplicated here; keep the field names in sync
 * with `RemoteTodo` (both sides use `ignoreUnknownKeys`, but a renamed key
 * would silently drop data).
 *
 * Ops accept both the base table URIs and the op-suffixed URIs the sinks use
 * (`.../todos/querySince?since=<ms>`, `.../todos/upsertRaw`,
 * `.../todos/deleteByVault`, same for snapshots):
 *  - `querySince`: rows with `updatedAt` past the `since` query parameter
 *    (falling back to `selectionArgs[0]`), including tombstones.
 *  - `upsertRaw`: insert/update with `payloadJson`, preserving the pulled
 *    `updatedAt` and relay identity (`uid` -> `syncId`). A payload with
 *    `deleted = true` tombstones the row instead of hard-deleting it, so the
 *    deletion propagates via the next `querySince`.
 *  - single delete (`syncId = ?` selection): tombstones the row.
 *  - `deleteByVault` (or selection-less delete): purge — drops the whole
 *    table *and* its snapshots.
 */
class RelayTodoProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.vayunmathur.todo.relay"
        const val PERMISSION = "com.vayunmathur.todo.permission.RELAY"

        const val COL_SYNC_ID = "syncId"
        const val COL_UPDATED_AT = "updatedAt"
        const val COL_DELETED = "deleted"
        const val COL_PAYLOAD_JSON = "payloadJson"
        const val COL_CONTENT_HASH = "contentHash"

        val COLUMNS = arrayOf(COL_SYNC_ID, COL_UPDATED_AT, COL_DELETED, COL_PAYLOAD_JSON, COL_CONTENT_HASH)

        private const val PARAM_SINCE = "since"

        private const val CODE_TODOS = 1
        private const val CODE_SNAPSHOTS = 2
        private const val CODE_TODOS_QUERY = 11
        private const val CODE_TODOS_UPSERT = 21
        private const val CODE_SNAPSHOTS_UPSERT = 22
        private const val CODE_TODOS_PURGE = 31
        private const val CODE_SNAPSHOTS_PURGE = 32

        private const val TAG = "RelayTodoProvider"

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "vault/*/todos", CODE_TODOS)
            addURI(AUTHORITY, "vault/*/snapshots", CODE_SNAPSHOTS)
            addURI(AUTHORITY, "vault/*/todos/querySince", CODE_TODOS_QUERY)
            addURI(AUTHORITY, "vault/*/todos/upsertRaw", CODE_TODOS_UPSERT)
            addURI(AUTHORITY, "vault/*/snapshots/upsertRaw", CODE_SNAPSHOTS_UPSERT)
            addURI(AUTHORITY, "vault/*/todos/deleteByVault", CODE_TODOS_PURGE)
            addURI(AUTHORITY, "vault/*/snapshots/deleteByVault", CODE_SNAPSHOTS_PURGE)
        }

        private val json = Json { ignoreUnknownKeys = true }

        fun todosUri(vaultId: String): Uri =
            Uri.parse("content://$AUTHORITY/vault/$vaultId/todos")

        fun snapshotsUri(vaultId: String): Uri =
            Uri.parse("content://$AUTHORITY/vault/$vaultId/snapshots")
    }

    private fun repo(): TodoRepository {
        val ctx = context ?: error("RelayTodoProvider context null")
        return TodoRepository.get(ctx)
    }

    override fun onCreate(): Boolean = true

    // The signature permission only proves "signed by the Modern Apps key", which
    // every app in the repo is. Narrow that to the everysync package as well.
    private fun enforceRelayCaller() {
        if (Binder.getCallingUid() == Process.myUid()) return
        val caller = callingPackage
        if (caller != "com.vayunmathur.everysync") {
            throw SecurityException("RelayTodoProvider: caller $caller is not everysync")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        enforceRelayCaller()
        // querySince: `?since=<ms>` query parameter first, selectionArgs[0] as fallback.
        val since = uri.getQueryParameter(PARAM_SINCE)?.toLongOrNull()
            ?: selectionArgs?.firstOrNull()?.toLongOrNull()
            ?: 0L
        return runBlocking(Dispatchers.IO) {
            try {
                when (uriMatcher.match(uri)) {
                    CODE_TODOS, CODE_TODOS_QUERY -> cursorFromTodos(repo().getUpdatedSince(since))
                    CODE_SNAPSHOTS -> cursorFromSnapshots(repo().getAllSnapshots())
                    else -> null
                }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "query failed $uri", e)
                null
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "query failed $uri", e)
                null
            }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceRelayCaller()
        val v = values ?: return null
        return runBlocking(Dispatchers.IO) {
            try {
                when (uriMatcher.match(uri)) {
                    CODE_TODOS, CODE_TODOS_UPSERT -> applyRelayJson(v)
                    CODE_SNAPSHOTS, CODE_SNAPSHOTS_UPSERT -> upsertSnapshot(v)
                    else -> null
                }?.let { Uri.withAppendedPath(uri, it) }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "insert failed $uri", e)
                null
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "insert failed $uri", e)
                null
            }
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        enforceRelayCaller()
        val v = values ?: return 0
        return runBlocking(Dispatchers.IO) {
            try {
                when (uriMatcher.match(uri)) {
                    CODE_TODOS, CODE_TODOS_UPSERT -> if (applyRelayJson(v) != null) 1 else 0
                    CODE_SNAPSHOTS, CODE_SNAPSHOTS_UPSERT -> if (upsertSnapshot(v) != null) 1 else 0
                    else -> 0
                }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "update failed $uri", e)
                0
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "update failed $uri", e)
                0
            }
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceRelayCaller()
        return runBlocking(Dispatchers.IO) {
            try {
                val repo = repo()
                when (uriMatcher.match(uri)) {
                    CODE_TODOS_PURGE -> {
                        repo.purge()
                        repo.deleteAllSnapshots()
                        0
                    }
                    CODE_SNAPSHOTS_PURGE -> {
                        repo.deleteAllSnapshots()
                        0
                    }
                    // Single delete (`syncId = ?`): tombstone the row so the
                    // deletion propagates via querySince. Selection-less base
                    // delete is the legacy purge spelling.
                    CODE_TODOS -> {
                        val syncId = if (selection != null) selectionArgs?.firstOrNull() else null
                        if (syncId != null) {
                            repo.tombstoneBySyncId(syncId)
                        } else {
                            repo.purge()
                            repo.deleteAllSnapshots()
                            0
                        }
                    }
                    CODE_SNAPSHOTS -> {
                        val syncId = selectionArgs?.firstOrNull()
                        if (syncId != null) {
                            repo.deleteSnapshotBySyncId(syncId)
                            1
                        } else {
                            repo.deleteAllSnapshots()
                            0
                        }
                    }
                    else -> 0
                }
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "delete failed $uri", e)
                0
            } catch (e: android.database.SQLException) {
                Log.error(TAG, "delete failed $uri", e)
                0
            }
        }
    }

    override fun getType(uri: Uri): String? = when (uriMatcher.match(uri)) {
        CODE_TODOS, CODE_TODOS_QUERY -> "vnd.android.cursor.dir/vnd.com.vayunmathur.todo.todo"
        CODE_SNAPSHOTS -> "vnd.android.cursor.dir/vnd.com.vayunmathur.todo.snapshot"
        else -> null
    }

    /**
     * Applies one pulled relay record. `uid` is the sync identity. A `deleted`
     * payload tombstones the row (never hard-deletes) so the deletion
     * propagates. Rebinds onto the existing row id so a pull updates in place
     * instead of duplicating.
     */
    private suspend fun applyRelayJson(v: ContentValues): String? {
        val payload = v.getAsString(COL_PAYLOAD_JSON) ?: return null
        val remote = runCatching { json.decodeFromString<RelayTodoJson>(payload) }.getOrNull()
            ?: return null
        if (remote.uid.isBlank()) return null
        val repo = repo()
        val existing = repo.getBySyncId(remote.uid)
        repo.upsertRaw(remote.toTodo(existing?.id ?: 0L))
        return remote.uid
    }

    private suspend fun upsertSnapshot(v: ContentValues): String? {
        val syncId = v.getAsString(COL_SYNC_ID) ?: return null
        repo().upsertSnapshot(
            SyncSnapshot(
                syncId = syncId,
                kind = v.getAsString("kind").orEmpty(),
                contentHash = v.getAsString(COL_CONTENT_HASH).orEmpty(),
                localUpdatedAt = v.getAsLong(COL_UPDATED_AT) ?: 0L,
                remoteModified = v.getAsLong("remoteModified") ?: 0L,
                lastSyncedAt = System.currentTimeMillis(),
            ),
        )
        return syncId
    }

    private fun cursorFromTodos(todos: List<Todo>): MatrixCursor {
        val c = MatrixCursor(COLUMNS)
        for (t in todos) {
            val relay = t.toRelayJson()
            val payload = json.encodeToString(relay)
            c.addRow(
                arrayOf<Any?>(
                    t.syncId,
                    t.updatedAt,
                    if (t.deleted) 1 else 0,
                    payload,
                    relayContentHash(relay),
                ),
            )
        }
        return c
    }

    private fun cursorFromSnapshots(snapshots: List<SyncSnapshot>): MatrixCursor {
        val c = MatrixCursor(COLUMNS)
        for (s in snapshots) {
            c.addRow(arrayOf<Any?>(s.syncId, s.localUpdatedAt, 0, "", s.contentHash))
        }
        return c
    }

    /**
     * Merge-compatible content hash: canonical envelope (`uid`, `position`,
     * `updatedAt`, `deleted` stripped) plus the deleted flag, SHA-256 hexed —
     * the same bytes the relay merge driver hashes, so LWW tiebreaks agree on
     * both sides.
     */
    private fun relayContentHash(e: RelayTodoJson): String {
        val canonical = json.encodeToString(
            e.copy(uid = "", position = 0.0, updatedAt = 0L, deleted = false),
        ) + "\u0000deleted=${e.deleted}"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Relay envelope for one todo record. Field-for-field mirror of everysync's
 * `RemoteTodo` (the bytes the relay log carries): title / notes / done /
 * dueAt / position / createdAt.
 *
 * todo/ must not depend on everysync, so the shape is duplicated here.
 * Both sides decode with `ignoreUnknownKeys`, but keep the names in sync —
 * a renamed key here would silently drop data on the relay side.
 */
@Serializable
internal data class RelayTodoJson(
    /** Relay sync identity (opaque 32-char id); maps to [Todo.syncId]. */
    val uid: String = "",
    val title: String = "",
    val notes: String = "",
    val done: Boolean = false,
    /** Epoch ms the task is due, or null. */
    val dueAt: Long? = null,
    val position: Double = 0.0,
    /** Epoch ms the task was created; part of the adoption content key. */
    val createdAt: Long = 0L,
    val deleted: Boolean = false,
    /** Last local-or-remote modification (epoch ms); drives relay LWW. */
    val updatedAt: Long = 0L,
)

internal fun Todo.toRelayJson(): RelayTodoJson = RelayTodoJson(
    uid = syncId,
    title = title,
    done = done,
    dueAt = dueAt,
    position = position,
    deleted = deleted,
    updatedAt = updatedAt,
)

internal fun RelayTodoJson.toTodo(id: Long): Todo = Todo(
    id = id,
    title = title,
    done = done,
    position = position,
    dueAt = dueAt,
    syncId = uid,
    updatedAt = updatedAt,
    deleted = deleted,
)
