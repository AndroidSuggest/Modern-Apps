package com.vayunmathur.youpipe.data

/**
 * Playlist and playlist-item writes, split from [SubscriptionRepository] (TooManyFunctions cap).
 *
 * Same package and module; behavior identical. Access via
 * [SubscriptionRepository.playlistStore].
 */
internal class PlaylistStore(
    private val playlistDao: PlaylistDao,
    private val playlistItemDao: PlaylistItemDao,
) {
    suspend fun getAllPlaylists(): List<Playlist> = playlistDao.getAll()

    suspend fun upsertPlaylist(value: Playlist): Long = playlistDao.upsert(value)

    suspend fun upsertPlaylists(values: List<Playlist>) = playlistDao.upsertAll(values)

    suspend fun deletePlaylist(value: Playlist): Int = playlistDao.delete(value)

    suspend fun getPlaylistItemsForPlaylist(playlistId: Long): List<PlaylistItem> =
        playlistItemDao.getForPlaylist(playlistId)

    suspend fun upsertPlaylistItem(value: PlaylistItem): Long = playlistItemDao.upsert(value)

    suspend fun upsertPlaylistItems(values: List<PlaylistItem>) = playlistItemDao.upsertAll(values)

    suspend fun deletePlaylistItem(value: PlaylistItem): Int = playlistItemDao.delete(value)

    suspend fun deletePlaylistItemByVideo(playlistId: Long, videoID: Long) =
        playlistItemDao.deleteByVideo(playlistId, videoID)
}
