package com.vayunmathur.passwords.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncSnapshotDao {
    @Query("SELECT * FROM SyncSnapshot")
    suspend fun getAll(): List<SyncSnapshot>

    @Upsert
    suspend fun upsert(snapshot: SyncSnapshot)

    @Query("DELETE FROM SyncSnapshot WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    @Query("DELETE FROM SyncSnapshot")
    suspend fun deleteAll()
}
