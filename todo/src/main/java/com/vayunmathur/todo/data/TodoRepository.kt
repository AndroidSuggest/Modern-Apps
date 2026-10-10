package com.vayunmathur.todo.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

class TodoRepository private constructor(context: Context) :
    RoomRepository<TodoDatabase>(context, TodoDatabase::class, DB_NAME) {

    private val dao get() = db.todoDao()
    private val snapshotDao get() = db.syncSnapshotDao()

    val todos: Flow<List<Todo>> get() = dao.getAllFlow()
    fun todoByIdFlow(id: Long): Flow<Todo?> = dao.getByIdFlow(id)

    suspend fun getAll(): List<Todo> = dao.getAll()
    suspend fun getBySyncId(syncId: String): Todo? = dao.getBySyncId(syncId)
    suspend fun getUpdatedSince(since: Long): List<Todo> = dao.getUpdatedSince(since)

    /** Local write: bumps updatedAt so the change is picked up by sync. */
    suspend fun upsert(value: Todo): Long = dao.upsert(value)

    /** Writes [value] verbatim. Only the relay sync uses this, to preserve a pulled timestamp. */
    suspend fun upsertRaw(value: Todo): Long = dao.upsertRaw(value)
    suspend fun upsertAllRaw(values: List<Todo>) = dao.upsertAllRaw(values)

    /** Local delete: tombstones instead of hard-deleting so the relay sync can propagate it. */
    suspend fun delete(value: Todo): Int {
        dao.upsert(value.copy(deleted = true))
        return 1
    }

    suspend fun tombstoneBySyncId(syncId: String): Int {
        val existing = dao.getBySyncId(syncId) ?: return 0
        if (!existing.deleted) dao.upsert(existing.copy(deleted = true))
        return 1
    }

    suspend fun deleteBySyncId(syncId: String): Int = dao.deleteBySyncId(syncId)
    suspend fun upsertAll(values: List<Todo>) = dao.upsertAll(values)

    /** Removes every todo (including tombstones). Used when a relay vault is purged. */
    suspend fun purge() = dao.deleteAll()

    suspend fun getAllSnapshots(): List<SyncSnapshot> = snapshotDao.getAll()
    suspend fun upsertSnapshot(snapshot: SyncSnapshot) = snapshotDao.upsert(snapshot)
    suspend fun deleteSnapshotBySyncId(syncId: String) = snapshotDao.deleteBySyncId(syncId)
    suspend fun deleteAllSnapshots() = snapshotDao.deleteAll()

    companion object {
        @Volatile private var instance: TodoRepository? = null
        fun get(context: Context): TodoRepository =
            instance ?: synchronized(this) {
                instance ?: TodoRepository(context).also { instance = it }
            }
    }
}
