package com.vayunmathur.appstore.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "appstore-db"

@Entity
data class RepoEntity(
    @PrimaryKey val url: String,
    val name: String,
    val enabled: Boolean = true,
    /** SHA-256 of the certificate the repo's index JAR must be signed with. */
    val fingerprint: String? = null,
    val lastSync: Long = 0L
)

@Entity
data class CachedAppEntity(
    @PrimaryKey val packageName: String,
    val source: String, // MODERN_APPS, FDROID, PLAYSTORE
    val name: String,
    val summary: String,
    val description: String,
    val iconUrl: String?,
    val author: String?,
    val categories: String, // comma joined
    val versionName: String?,
    val versionCode: Long,
    val sizeBytes: Long,
    val apkUrl: String?,
    val targetSdk: Int?,
    val repoUrl: String?,
    val lastUpdated: Long,
    /** Comma-joined SHA-256 signing-certificate fingerprints from an authenticated index. */
    val expectedSigners: String? = null,
    /** SHA-256 of the APK itself, from an authenticated index. */
    val apkSha256: String? = null,
    val license: String? = null,
    val website: String? = null,
    val sourceCode: String? = null
)

fun UnifiedApp.toEntity(): CachedAppEntity = CachedAppEntity(
    packageName = packageName,
    source = source.name,
    name = name,
    summary = summary,
    description = description,
    iconUrl = iconUrl,
    author = author,
    categories = categories.joinToString(","),
    versionName = versionName,
    versionCode = versionCode,
    sizeBytes = sizeBytes,
    apkUrl = apkUrl,
    targetSdk = targetSdk,
    repoUrl = repoUrl?.removeSuffix("/") ?: DefaultRepos.FDROID_MAIN,
    lastUpdated = lastUpdated,
    expectedSigners = expectedSigners.joinToString(",").ifBlank { null },
    apkSha256 = apkSha256,
    license = license,
    website = website,
    sourceCode = sourceCode,
)

fun CachedAppEntity.toUnifiedApp(): UnifiedApp = UnifiedApp(
    packageName = packageName,
    source = try { AppSource.valueOf(source) } catch (_: Exception) { AppSource.FDROID },
    name = name,
    summary = summary,
    description = description,
    iconUrl = iconUrl,
    author = author,
    categories = categories.split(",").filter { it.isNotBlank() },
    versionName = versionName,
    versionCode = versionCode,
    sizeBytes = sizeBytes,
    apkUrl = apkUrl,
    targetSdk = targetSdk,
    repoUrl = repoUrl,
    lastUpdated = lastUpdated,
    expectedSigners = expectedSigners?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    apkSha256 = apkSha256,
    license = license,
    website = website,
    sourceCode = sourceCode,
)

/**
 * Trust-on-first-use pin of a package's APK source-stamp certificate.
 *
 * Only meaningful for Play, where the APK signing key belongs to Google and so cannot
 * identify the publisher. The stamp survives Play's re-signing, so pinning it detects a
 * change of publisher identity across updates. See
 * [com.vayunmathur.appstore.data.security.SourceStamp] for what this does and does not
 * prove.
 */
@Entity
data class PinnedStampEntity(
    @PrimaryKey val packageName: String,
    val stampSha256: String,
    val firstSeen: Long,
)

@Dao
interface PinnedStampDao {
    @Query("SELECT * FROM PinnedStampEntity WHERE packageName = :pkg LIMIT 1")
    suspend fun byPackage(pkg: String): PinnedStampEntity?

    @Upsert
    suspend fun upsert(pin: PinnedStampEntity)

    @Query("DELETE FROM PinnedStampEntity WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)
}

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

    @Query("SELECT * FROM CachedAppEntity")
    suspend fun all(): List<CachedAppEntity>

    @Query("SELECT * FROM CachedAppEntity WHERE packageName = :pkg LIMIT 1")
    suspend fun byPackage(pkg: String): CachedAppEntity?

    @Query(
        "SELECT * FROM CachedAppEntity WHERE source = :source AND (" +
            "packageName LIKE '%' || :q || '%' OR name LIKE '%' || :q || '%' " +
            "OR summary LIKE '%' || :q || '%') ORDER BY name ASC LIMIT 40"
    )
    suspend fun search(source: String, q: String): List<CachedAppEntity>

    @Upsert
    suspend fun upsertAll(apps: List<CachedAppEntity>)

    @Upsert
    suspend fun upsert(app: CachedAppEntity)

    @Query("DELETE FROM CachedAppEntity WHERE repoUrl = :repoUrl")
    suspend fun deleteByRepo(repoUrl: String)

    @Query("DELETE FROM CachedAppEntity")
    suspend fun clearAll()
}

@Database(
    entities = [RepoEntity::class, CachedAppEntity::class, PinnedStampEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao
    abstract fun cachedAppDao(): CachedAppDao
    abstract fun pinnedStampDao(): PinnedStampDao

    companion object {
        val migrations = listOf(
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS FavoriteEntity")
                }
            },
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN targetSdk INTEGER")
                }
            },
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN expectedSigners TEXT")
                    db.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN apkSha256 TEXT")
                    db.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN license TEXT")
                    db.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN website TEXT")
                    db.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN sourceCode TEXT")
                    // Rows cached before this version carry no signer or hash, and the
                    // reproducible-only filter had not run yet. Drop them so nothing
                    // unverifiable survives the upgrade; the next sync repopulates.
                    db.execSQL("DELETE FROM CachedAppEntity")
                    db.execSQL("DELETE FROM RepoEntity")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS PinnedStampEntity (" +
                            "packageName TEXT NOT NULL PRIMARY KEY, " +
                            "stampSha256 TEXT NOT NULL, " +
                            "firstSeen INTEGER NOT NULL)"
                    )
                }
            }
        )
    }
}
