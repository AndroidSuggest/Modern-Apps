package com.vayunmathur.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.paneContentMargin
import com.vayunmathur.music.R
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.PlaybackSource

/**
 * The upcoming-songs queue for the Expanded Now Playing side panel.
 *
 * Resolves the queue from the same origin the player is playing from
 * ([PlaybackSource.parse] of the now-playing `sourceId`): all songs, an
 * album's tracks, a playlist's songs, or an artist's songs. Only songs after
 * the currently-playing one are shown — the player itself already shows what
 * is playing. Tapping a row jumps the queue to that song via the existing
 * `playSong` action, so no new playback API was needed.
 *
 * Stateless rendering of a plain list (no scaffold of its own): the wide
 * layout owns the pane chrome, and previews drive this with literal songs.
 */
@Composable
fun NowPlayingQueue(
    musicViewModel: MusicViewModel,
    modifier: Modifier = Modifier,
) {
    val state = musicViewModel.nowPlayingState() ?: return
    val allMusic by musicViewModel.music.collectAsState()
    // Playlist membership lives in a separate table, so it is collected only
    // when the player is actually playing from a playlist.
    val playlistId = (PlaybackSource.parse(state.sourceId) as? PlaybackSource.Playlist)?.playlistId
    val matchedIds by musicViewModel.matchedMusicForPlaylist(playlistId ?: -1L)
    val origin = remember(allMusic, matchedIds, state.sourceId, state.songId) {
        queueOriginSongs(allMusic, matchedIds.toSet(), state.sourceId, state.songId)
    }
    QueueList(
        songs = origin.songs,
        playingSongId = state.songId,
        title = origin.title,
        onJump = { index ->
            musicViewModel.playSong(origin.songs, index, state.sourceId, state.sourceName)
        },
        modifier = modifier,
    )
}

/** The songs the queue panel shows, and the title above them. */
private data class QueueOrigin(
    val songs: List<Music>,
    val title: String,
)

/**
 * Upcoming songs after [currentSongId] from the player's origin, or everything
 * when the current song is unknown (stale state): an empty panel would read as
 * "no queue" when the queue is merely unresolved.
 */
private fun queueOriginSongs(
    allMusic: List<Music>,
    playlistMemberIds: Set<Long>,
    sourceId: String?,
    currentSongId: Long?,
): QueueOrigin {
    val origin = when (val source = PlaybackSource.parse(sourceId)) {
        is PlaybackSource.Album -> allMusic
            .filter { it.albumId == source.albumId }
            .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }))
        is PlaybackSource.Playlist -> allMusic.filter { it.id in playlistMemberIds }
        is PlaybackSource.Artist -> allMusic.filter { it.artistId == source.artistId }
        else -> allMusic.sortedBy { it.title }
    }
    val upcoming = currentSongId?.let { id ->
        origin.dropWhile { it.id != id }.drop(1)
    }.orEmpty()
    return QueueOrigin(
        songs = upcoming.ifEmpty { origin },
        title = "",
    )
}

@Composable
internal fun QueueList(
    songs: List<Music>,
    playingSongId: Long?,
    title: String,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = paneContentMargin(),
            vertical = Spacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (title.isNotEmpty()) {
            item(key = "queue-title") {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = Spacing.sm,
                        vertical = Spacing.xs,
                    ),
                )
            }
        }
        items(songs, key = { it.id }) { song ->
            val isPlaying = song.id == playingSongId
            ListItem(
                headlineContent = {
                    Text(
                        text = song.title.ifEmpty { stringResource(R.string.unknown_title) },
                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal,
                        color = if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                },
                supportingContent = { Text(song.artist) },
                modifier = Modifier.clickable { onJump(songs.indexOf(song)) },
            )
        }
    }
}
