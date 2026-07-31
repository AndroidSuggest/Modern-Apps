package com.vayunmathur.appstore.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "appstore-db"

@Entity
data class RepoEntity(
    @PrimaryKey val url: String,
    val name: String,
    val enabled: Boolean = true,
    val fingerprint: String? = null,
    val lastSync: Long = 0L
)

@Entity
data class CachedAppEntity(
    @PrimaryKey val packageName: String,
    val source: String, // FDROID, PLAYSTORE
    val name: String,
    val summary: String,
    val description: String,
    val iconUrl: String?,
    val author: String?,
    val categories: String, // json joined
    val versionName: String?,
    val versionCode: Long,
    val sizeBytes: Long,
    val apkUrl: String?,
    val repoUrl: String?,
    val lastUpdated: Long
)

@Entity
data class FavoriteEntity(
    @PrimaryKey val packageName: String,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface RepoDao {
    @Query("SELECT * FROM RepoEntity ORDER BY name ASC")
    fun allFlow(): Flow<List<RepoEntity>>

    @Query("SELECT * FROM RepoEntity")
    suspend fun all(): List<RepoEntity>

    @Upsert
    suspend fun upsert(repo: RepoEntity)

    @Delete
    suspend fun delete(repo: RepoEntity)

    @Query("DELETE FROM RepoEntity WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}

@Dao
interface CachedAppDao {
    @Query("SELECT * FROM CachedAppEntity ORDER BY name ASC")
    fun allFlow(): Flow<List<CachedAppEntity>>

    @Query("SELECT * FROM CachedAppEntity WHERE packageName LIKE '%' || :q || '%' OR name LIKE '%' || :q || '%' ORDER BY name ASC")
    fun searchFlow(q: String): Flow<List<CachedAppEntity>>

    @Query("SELECT * FROM CachedAppEntity WHERE packageName = :pkg LIMIT 1")
    suspend fun byPackage(pkg: String): CachedAppEntity?

    @Upsert
    suspend fun upsertAll(apps: List<CachedAppEntity>)

    @Upsert
    suspend fun upsert(app: CachedAppEntity)

    @Query("DELETE FROM CachedAppEntity WHERE repoUrl = :repoUrl")
    suspend fun deleteByRepo(repoUrl: String)

    @Query("DELETE FROM CachedAppEntity")
    suspend fun clearAll()
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM FavoriteEntity ORDER BY addedAt DESC")
    fun allFlow(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM FavoriteEntity WHERE packageName = :pkg)")
    fun isFavFlow(pkg: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(fav: FavoriteEntity)

    @Query("DELETE FROM FavoriteEntity WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)
}

@Database(
    entities = [RepoEntity::class, CachedAppEntity::class, FavoriteEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao
    abstract fun cachedAppDao(): CachedAppDao
    abstract fun favoriteDao(): FavoriteDao
}
