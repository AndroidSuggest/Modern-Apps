package com.vayunmathur.everysync.data.sink

import com.vayunmathur.everysync.data.RemoteTodo
import com.vayunmathur.everysync.sync.adapters.TodoKind

/**
 * Contract for the todo/ vault sink (implemented by todo-builder against the
 * todo/ app's storage).
 *
 * Shape mirrors the contacts/calendar sinks but keyed on relay sync ids rather
 * than provider SOURCE_IDs: [RemoteTodo.uid] is the stable identity,
 * [TodoKind] drives the merge, and the `todos:<vaultId>` channel carries the
 * envelopes. tombstones ([RemoteTodo.deleted]) are real rows here, not
 * provider DELETED flags — [upsert] must persist them, [delete] is only for
 * garbage-collected ids the merge has fully acknowledged.
 */
interface TodoSink {
    /** All local todos for the vault, tombstones included. */
    suspend fun listLocal(): List<RemoteTodo>

    /** Idempotent upsert keyed on [RemoteTodo.uid]; persists tombstones too. */
    suspend fun upsert(items: List<RemoteTodo>)

    /** Hard-removes rows whose tombstones the merge has collected (ids only). */
    suspend fun delete(syncIds: List<String>)
}
