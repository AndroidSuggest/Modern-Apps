package com.vayunmathur.web.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "web-db"

@Dao
interface HistoryDao {
    @Query("SELECT * FROM HistoryEntry ORDER BY visitedAt DESC")
    fun allFlow(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM HistoryEntry WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY visitedAt DESC")
    fun searchFlow(query: String): Flow<List<HistoryEntry>>

    @Upsert
    suspend fun upsert(entry: HistoryEntry): Long

    @Query("DELETE FROM HistoryEntry")
    suspend fun clearAll()

    @Query("DELETE FROM HistoryEntry WHERE visitedAt < :before")
    suspend fun deleteBefore(before: Long)

    @Query("SELECT * FROM HistoryEntry ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<HistoryEntry>
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM Bookmark ORDER BY createdAt DESC")
    fun allFlow(): Flow<List<Bookmark>>

    @Query("SELECT * FROM Bookmark WHERE folderId = :folderId ORDER BY createdAt DESC")
    fun byFolderFlow(folderId: Long?): Flow<List<Bookmark>>

    @Query("SELECT * FROM Bookmark WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): Bookmark?

    @Query("SELECT * FROM Bookmark WHERE url = :url LIMIT 1")
    fun byUrlFlow(url: String): Flow<Bookmark?>

    @Upsert
    suspend fun upsert(entry: Bookmark): Long

    @Delete
    suspend fun delete(entry: Bookmark)

    @Query("SELECT * FROM BookmarkFolder ORDER BY name ASC")
    fun foldersFlow(): Flow<List<BookmarkFolder>>

    @Upsert
    suspend fun upsertFolder(folder: BookmarkFolder): Long

    @Delete
    suspend fun deleteFolder(folder: BookmarkFolder)

    @Query("DELETE FROM Bookmark WHERE folderId = :folderId")
    suspend fun deleteByFolder(folderId: Long)
}

@Database(
    entities = [HistoryEntry::class, Bookmark::class, BookmarkFolder::class],
    version = 1,
    exportSchema = false
)
abstract class WebDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
}
