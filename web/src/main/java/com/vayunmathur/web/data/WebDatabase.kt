package com.vayunmathur.web.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

// Bumped to new file name to avoid old migration crash — fresh install uses this DB.
const val DB_NAME = "web-browser-db"

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

@Dao
interface SitePermissionDao {
    @Query("SELECT * FROM SitePermission ORDER BY origin ASC")
    fun allFlow(): Flow<List<SitePermission>>

    @Query("SELECT * FROM SitePermission WHERE origin = :origin LIMIT 1")
    suspend fun byOrigin(origin: String): SitePermission?

    @Query("SELECT * FROM SitePermission WHERE origin = :origin LIMIT 1")
    fun byOriginFlow(origin: String): Flow<SitePermission?>

    @Upsert
    suspend fun upsert(p: SitePermission): Long

    @Delete
    suspend fun delete(p: SitePermission)

    @Query("DELETE FROM SitePermission")
    suspend fun clearAll()

    @Query("DELETE FROM SitePermission WHERE origin = :origin")
    suspend fun deleteOrigin(origin: String)
}

@Dao
interface StorageInfoDao {
    @Query("SELECT * FROM StorageInfo ORDER BY lastSeen DESC")
    fun allFlow(): Flow<List<StorageInfo>>

    @Query("SELECT * FROM StorageInfo WHERE origin = :origin LIMIT 1")
    suspend fun byOrigin(origin: String): StorageInfo?

    @Upsert
    suspend fun upsert(info: StorageInfo): Long

    @Delete
    suspend fun delete(info: StorageInfo)

    @Query("DELETE FROM StorageInfo WHERE origin = :origin")
    suspend fun deleteOrigin(origin: String)

    @Query("DELETE FROM StorageInfo")
    suspend fun clearAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM DownloadEntry ORDER BY startedAt DESC")
    fun allFlow(): Flow<List<DownloadEntry>>

    @Upsert
    suspend fun upsert(d: DownloadEntry): Long

    @Query("DELETE FROM DownloadEntry WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM DownloadEntry")
    suspend fun clearAll()
}

@Dao
interface InstalledSiteDao {
    @Query("SELECT * FROM InstalledSite ORDER BY installedAt DESC")
    fun allFlow(): Flow<List<InstalledSite>>

    @Query("SELECT * FROM InstalledSite WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): InstalledSite?

    @Query("SELECT * FROM InstalledSite WHERE origin = :origin LIMIT 1")
    suspend fun byOrigin(origin: String): InstalledSite?

    @Upsert
    suspend fun upsert(site: InstalledSite)

    @Query("DELETE FROM InstalledSite WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM InstalledSite")
    suspend fun clearAll()
}

@Database(
    entities = [
        HistoryEntry::class,
        Bookmark::class,
        BookmarkFolder::class,
        SitePermission::class,
        StorageInfo::class,
        DownloadEntry::class,
        InstalledSite::class,
    ],
    version = 2,
    exportSchema = false
)
abstract class WebDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun sitePermissionDao(): SitePermissionDao
    abstract fun storageInfoDao(): StorageInfoDao
    abstract fun downloadDao(): DownloadDao
    abstract fun installedSiteDao(): InstalledSiteDao

    companion object : DatabaseMigrations {
        override val migrations = listOf(MIGRATION_1_2)
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `InstalledSite` (
                `id` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `shortName` TEXT NOT NULL,
                `iconUrl` TEXT,
                `faviconUrl` TEXT,
                `themeColor` TEXT,
                `backgroundColor` TEXT,
                `displayMode` TEXT NOT NULL,
                `startUrl` TEXT NOT NULL,
                `origin` TEXT NOT NULL,
                `installedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )"""
        )
    }
}
