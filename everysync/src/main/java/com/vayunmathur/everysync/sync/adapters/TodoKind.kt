package com.vayunmathur.everysync.sync.adapters

import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.sync.EntityKind
import com.vayunmathur.everysync.sync.relayContentHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Relay merge behaviour for todo tasks. Identity is the relay sync id
 * ([RemoteTodo.uid]); the envelope is the [RemoteTodo] JSON on the
 * `todos:<vaultId>` channel. The canonical form strips identity, ordering
 * ([RemoteTodo.position]) and timestamps so a sync id or a reorder never looks
 * like an edit.
 *
 * Contract for todo-builder: implement `TodoSink` (see `data/sink/TodoSink.kt`)
 * against the todo/ app's storage and drive it with
 * `RelayMerge.merge(TodoKind, local, remote, baseline, now)`.
 */
object TodoKind : EntityKind<RemoteTodo>() {
    override val kind = "todo"

    private val json = Json { ignoreUnknownKeys = true }

    /** Relay channel for a vault's task log. */
    fun channel(vaultId: String): String = "todos:$vaultId"

    override fun syncId(e: RemoteTodo) = e.uid
    override fun updatedAt(e: RemoteTodo) = e.updatedAt
    override fun isDeleted(e: RemoteTodo) = e.deleted

    override fun contentHash(e: RemoteTodo): String =
        relayContentHash(canonical(e) + "\u0000deleted=${e.deleted}")

    override fun contentKey(e: RemoteTodo): String = "todo\u0000${e.title}\u0000${e.createdAt}"

    override fun rebind(e: RemoteTodo, syncId: String) = e.copy(uid = syncId)
    override fun withDeleted(e: RemoteTodo, deleted: Boolean) = e.copy(deleted = deleted)
    override fun withUpdatedAt(e: RemoteTodo, updatedAt: Long) = e.copy(updatedAt = updatedAt)

    /** Canonical JSON with identity, ordering and timestamps stripped. */
    fun canonical(e: RemoteTodo): String = json.encodeToString(
        e.copy(uid = "", position = 0.0, updatedAt = 0L, deleted = false),
    )

    fun toJson(e: RemoteTodo): String = json.encodeToString(e)

    fun fromJson(raw: String): RemoteTodo? = runCatching {
        json.decodeFromString<RemoteTodo>(raw)
    }.getOrNull()
}
