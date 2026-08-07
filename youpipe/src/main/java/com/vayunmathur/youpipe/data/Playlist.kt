package com.vayunmathur.youpipe.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.vayunmathur.library.util.ReorderableDatabaseItem
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * A user-created playlist. [mandatory] marks the seeded "Watch later" playlist,
 * which behaves like any other playlist except it can never be deleted.
 */
@Entity
data class Playlist(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    override val position: Double = 0.0,
    val mandatory: Boolean = false,
) : ReorderableDatabaseItem<Playlist> {
    override fun withPosition(position: Double) = copy(position = position)
}

/**
 * A single video's membership in a playlist. [id] is a distinct autogen row id so the same
 * [videoItem] (keyed by its embedded `videoID`) can live in multiple playlists at once.
 */
@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        )
    ]
)
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    @ColumnInfo(index = true) val playlistId: Long,
    @Embedded val videoItem: VideoInfo,
    override val position: Double = 0.0,
    val timestamp: Instant,
) : ReorderableDatabaseItem<PlaylistItem> {
    override fun withPosition(position: Double) = copy(position = position)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM Playlist")
    fun getAllFlow(): Flow<List<Playlist>>

    @Query("SELECT * FROM Playlist WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Playlist?>

    @Query("SELECT * FROM Playlist")
    suspend fun getAll(): List<Playlist>

    @Upsert
    suspend fun upsert(value: Playlist): Long

    @Upsert
    suspend fun upsertAll(values: List<Playlist>)

    @Delete
    suspend fun delete(value: Playlist): Int
}

@Dao
interface PlaylistItemDao {
    @Query("SELECT * FROM PlaylistItem")
    fun getAllFlow(): Flow<List<PlaylistItem>>

    @Query("SELECT * FROM PlaylistItem WHERE playlistId = :playlistId")
    fun getForPlaylistFlow(playlistId: Long): Flow<List<PlaylistItem>>

    @Query("SELECT * FROM PlaylistItem WHERE playlistId = :playlistId")
    suspend fun getForPlaylist(playlistId: Long): List<PlaylistItem>

    @Upsert
    suspend fun upsert(value: PlaylistItem): Long

    @Upsert
    suspend fun upsertAll(values: List<PlaylistItem>)

    @Delete
    suspend fun delete(value: PlaylistItem): Int

    @Query("DELETE FROM PlaylistItem WHERE playlistId = :playlistId AND videoID = :videoID")
    suspend fun deleteByVideo(playlistId: Long, videoID: Long)
}
