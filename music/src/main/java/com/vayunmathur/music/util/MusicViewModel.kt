package com.vayunmathur.music.util

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.library.util.MatchingDao
import com.vayunmathur.music.R
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.AlbumDao
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.ArtistDao
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicDao
import com.vayunmathur.music.data.MusicDatabase
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.data.PlaylistDao
import com.vayunmathur.music.data.TYPE_ALBUM_ARTIST
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Music app.
 *
 * Owns:
 *  - direct DAO access for all four entities (Music, Album, Artist, Playlist)
 *  - playlist editing actions (create, rename, add track) routed through the
 *    relevant DAO + [MatchingDao]
 *
 * Mirrors (does not duplicate the source of truth):
 *  - [PlaybackManager] state and playback actions. The PlaybackManager remains
 *    the sole owner of player state; this VM just re-exposes its StateFlows
 *    and delegates actions so composables collect a single VM instead of
 *    grabbing the singleton.
 *
 * [PlaybackService] is intentionally not wrapped — it's an Android Service and
 * stays separate from the composable layer.
 */
class MusicViewModel(
    application: Application,
    private val musicDao: MusicDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val matchingDao: MatchingDao,
    private val playbackManager: PlaybackManager,
) : AndroidViewModel(application), MusicActions {

    // --- Entity StateFlows (replaces viewModel.data<X>()) ---
    val music: StateFlow<List<Music>> = musicDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val albums: StateFlow<List<Album>> = albumDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val artists: StateFlow<List<Artist>> = artistDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val playlists: StateFlow<List<Playlist>> = playlistDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Single subscription to the matchings table; everything that needs a
    // typed lookup derives from this.
    private val matchings: StateFlow<List<ManyManyMatching>> = matchingDao.flow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- PlaybackManager state mirror (read-only StateFlow pass-through) ---
    val isPlaying: StateFlow<Boolean> = playbackManager.isPlaying
    val currentPosition: StateFlow<Long> = playbackManager.currentPosition
    val duration: StateFlow<Long> = playbackManager.duration
    val shuffleMode: StateFlow<Boolean> = playbackManager.shuffleMode
    val repeatMode: StateFlow<Int> = playbackManager.repeatMode
    val currentMediaItem: StateFlow<MediaItem?> = playbackManager.currentMediaItem
    val currentSource: StateFlow<String?> = playbackManager.currentSource
    val currentSourceName: StateFlow<String?> = playbackManager.currentSourceName

    // --- PlaybackManager action delegates ([MusicActions]) ---
    // The defaults for sourceId/sourceName are inherited from MusicActions; an override
    // may not restate them.
    override fun playSong(songs: List<Music>, startWithIndex: Int, sourceId: String?, sourceName: String?) =
        playbackManager.playSong(songs, startWithIndex, sourceId, sourceName)

    override fun playShuffled(songs: List<Music>, sourceId: String?, sourceName: String?) =
        playbackManager.playShuffled(songs, sourceId, sourceName)

    // Block bodies: the PlaybackManager equivalents are `controller?.…` and so return
    // Unit?, which can't override a Unit-returning interface method.
    override fun togglePlayPause() { playbackManager.togglePlayPause() }
    override fun seekTo(pos: Long) = playbackManager.seekTo(pos)
    override fun skipNext() { playbackManager.skipNext() }
    override fun skipPrevious() { playbackManager.skipPrevious() }
    override fun toggleShuffle() = playbackManager.toggleShuffle()
    override fun toggleRepeat() = playbackManager.toggleRepeat()

    /**
     * The loaded track as a [NowPlayingUiState], or null when the queue is empty. Read as
     * Compose state rather than exposed as a plain `val` because every field behind it is
     * a StateFlow owned by [PlaybackManager].
     */
    @Composable
    fun nowPlayingState(): NowPlayingUiState? {
        val item by currentMediaItem.collectAsState()
        val playing by isPlaying.collectAsState()
        val position by currentPosition.collectAsState()
        val total by duration.collectAsState()
        val shuffle by shuffleMode.collectAsState()
        val repeat by repeatMode.collectAsState()
        val sourceId by currentSource.collectAsState()
        val sourceName by currentSourceName.collectAsState()
        val unknownTitle = stringResource(R.string.unknown_title)
        val unknownArtist = stringResource(R.string.unknown_artist)

        val metadata = item?.mediaMetadata ?: return null
        return NowPlayingUiState(
            title = metadata.title?.toString() ?: unknownTitle,
            artist = metadata.artist?.toString() ?: unknownArtist,
            artworkUri = metadata.artworkUri,
            isPlaying = playing,
            positionMs = position,
            durationMs = total,
            shuffle = shuffle,
            repeatMode = repeat,
            sourceId = sourceId,
            sourceName = sourceName,
        )
    }

    /** Id of the loaded track when it is playing from [sourceId], else null. */
    @Composable
    fun playingSongIdFrom(sourceId: String): Long? {
        val item by currentMediaItem.collectAsState()
        val source by currentSource.collectAsState()
        return item?.mediaId?.toLongOrNull()?.takeIf { source == sourceId }
    }

    // --- Per-entity "by id" State (replaces viewModel.getState<X>(id)) ---
    @Composable
    fun musicState(id: Long): State<Music?> {
        val list by music.collectAsState()
        return remember(id, list) {
            derivedStateOf {
                list.firstOrNull { it.id == id }
            }
        }
    }

    @Composable
    fun albumState(id: Long): State<Album?> {
        val list by albums.collectAsState()
        return remember(id, list) {
            derivedStateOf {
                list.firstOrNull { it.id == id }
            }
        }
    }

    @Composable
    fun artistState(id: Long): State<Artist?> {
        val list by artists.collectAsState()
        return remember(id, list) {
            derivedStateOf {
                list.firstOrNull { it.id == id }
            }
        }
    }

    @Composable
    fun playlistState(id: Long): State<Playlist?> {
        val list by playlists.collectAsState()
        return remember(id, list) {
            derivedStateOf {
                list.firstOrNull { it.id == id }
            }
        }
    }

    // --- Typed matching lookups (replace viewModel.getMatchesState<A,B>(id)) ---
    /** Album IDs matched to the given artist. (Album < Artist → artist is right side.) */
    @Composable
    fun matchedAlbumsForArtist(artistId: Long): State<List<Long>> {
        val all by matchings.collectAsState()
        return remember(artistId, all) {
            derivedStateOf {
                all.filter { it.rightID == artistId && it.type == TYPE_ALBUM_ARTIST }
                    .map { it.leftID }
            }
        }
    }

    /** Artist IDs matched to the given album. */
    @Composable
    fun matchedArtistsForAlbum(albumId: Long): State<List<Long>> {
        val all by matchings.collectAsState()
        return remember(albumId, all) {
            derivedStateOf {
                all.filter { it.leftID == albumId && it.type == TYPE_ALBUM_ARTIST }
                    .map { it.rightID }
            }
        }
    }

    /** Music IDs in the given playlist. (Music < Playlist → playlist is right side.) */
    @Composable
    fun matchedMusicForPlaylist(playlistId: Long): State<List<Long>> {
        val all by matchings.collectAsState()
        return remember(playlistId, all) {
            derivedStateOf {
                all.filter { it.rightID == playlistId && it.type == TYPE_MUSIC_PLAYLIST }
                    .map { it.leftID }
            }
        }
    }

    /** One-shot fetch of music IDs in a playlist (replaces suspend getMatches). */
    suspend fun getMusicInPlaylist(playlistId: Long): List<Long> =
        matchingDao.getFromRight(playlistId, TYPE_MUSIC_PLAYLIST)

    // --- Mutations ---
    /** Creates a new playlist with [name] off the main thread, invoking [onCreated] with the new id. */
    fun createPlaylist(name: String, onCreated: (Long) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = playlistDao.upsert(Playlist(name = name))
            onCreated(id)
        }
    }

    /** Persists a renamed [playlist] off the main thread. */
    fun renamePlaylist(playlist: Playlist, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playlistDao.upsert(playlist.copy(name = newName))
        }
    }

    /** Deletes [playlist] and clears its track matchings off the main thread, invoking [onDone] on completion. */
    fun deletePlaylist(playlist: Playlist, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            matchingDao.deleteFromRight(playlist.id, TYPE_MUSIC_PLAYLIST)
            playlistDao.deleteById(playlist.id)
            onDone()
        }
    }

    /** Adds [musicId] to [playlistId] off the main thread, invoking [onDone] on completion. */
    fun addMusicToPlaylist(playlistId: Long, musicId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            // Music index (0) < Playlist index (3) → music is left, playlist is right.
            matchingDao.upsert(ManyManyMatching(musicId, playlistId, TYPE_MUSIC_PLAYLIST))
            onDone()
        }
    }

    /** Removes [musicId] from [playlistId] off the main thread, invoking [onDone] on completion. */
    fun removeMusicFromPlaylist(playlistId: Long, musicId: Long, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            matchingDao.deleteMatch(musicId, playlistId, TYPE_MUSIC_PLAYLIST)
            onDone()
        }
    }
}

/** Factory for constructing [MusicViewModel] with shared dependencies. */
class MusicViewModelFactory(
    private val application: Application,
    private val musicDao: MusicDao,
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val playlistDao: PlaylistDao,
    private val matchingDao: MatchingDao,
    private val playbackManager: PlaybackManager,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(MusicViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return MusicViewModel(
            application,
            musicDao,
            albumDao,
            artistDao,
            playlistDao,
            matchingDao,
            playbackManager,
        ) as T
    }
}

/** Convenience factory wrapper that pulls DAOs straight from [MusicDatabase]. */
fun MusicViewModelFactory(
    application: Application,
    database: MusicDatabase,
    playbackManager: PlaybackManager,
): MusicViewModelFactory = MusicViewModelFactory(
    application,
    database.musicDao(),
    database.albumDao(),
    database.artistDao(),
    database.playlistDao(),
    database.matchingDao(),
    playbackManager,
)
