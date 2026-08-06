package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.BottomAppBar
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconShuffle
import com.vayunmathur.library.ui.IconSkipNext
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.ListPage
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicActions
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.util.NowPlayingUiState
import com.vayunmathur.music.util.SongsUiState
import com.vayunmathur.music.util.AddToPlaylistButton
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.data.Music

/** Queue id the songs tab plays under; [SOURCE_ALL_SONGS_NAME] is its display label. */
private const val SOURCE_ALL_SONGS = "all_songs"
private const val SOURCE_ALL_SONGS_NAME = "All Songs"

/** Binds [SongsScreen] to the ViewModel. */
@Composable
fun HomeTabContent(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val music by musicViewModel.music.collectAsState()

    SongsScreen(
        state = SongsUiState(
            songs = music,
            playingSongId = musicViewModel.playingSongIdFrom(SOURCE_ALL_SONGS),
        ),
        actions = musicViewModel,
        backStack = backStack,
    )
}

/**
 * Songs tab content. No Scaffold / no BottomNavBar — those live in the
 * surrounding [MusicTabsScreen]. ListPage's own Scaffold is kept (it owns
 * the TopAppBar with the embedded search bar and the shuffle FAB).
 */
@Composable
fun SongsScreen(state: SongsUiState, actions: MusicActions, backStack: NavBackStack<Route>) {
    ListPage<Music, Route, Route.Song>(backStack, state.songs, stringResource(R.string.page_title_music), { song ->
        val isPlaying = song.id == state.playingSongId
        Text(
            text = song.title,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.Unspecified,
            fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal
        )
    }, {
        Text(it.artist)
    }, { toPlay ->
        val allSongs = state.songs
        val toPlayIndex = allSongs.indexOfFirst { it.id == toPlay }
        actions.playSong(allSongs, toPlayIndex, sourceId = SOURCE_ALL_SONGS, sourceName = SOURCE_ALL_SONGS_NAME)
        Route.Song
    }, leadingContent = { song ->
        val isPlaying = song.id == state.playingSongId
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isPlaying) {
                IconPlay(modifier = Modifier.size(24.dp).padding(end = 8.dp))
            }
            AlbumArt(song.uri.toUri(), Modifier.size(40.dp))
        }
    }, trailingContent = { song ->
        AddToPlaylistButton(backStack, song)
    }, itemModifier = { Modifier.clip(RoundedCornerShape(12.dp)) },
    itemColors = { song ->
        if (song.id == state.playingSongId) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                supportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            ListItemDefaults.colors()
        }
    }, searchEnabled = true, fab = {
        ShufflePlayFab(state.songs) {
            actions.playShuffled(state.songs, sourceId = SOURCE_ALL_SONGS, sourceName = SOURCE_ALL_SONGS_NAME)
        }
    }, sortOrder = Comparator.comparing { it.title })
}

@Composable
fun ShufflePlayFab(musicViewModel: MusicViewModel) {
    val allSongs by musicViewModel.music.collectAsState()

    ShufflePlayFab(allSongs) {
        musicViewModel.playShuffled(allSongs, sourceId = SOURCE_ALL_SONGS, sourceName = SOURCE_ALL_SONGS_NAME)
    }
}

/** Shuffle-everything FAB. Hidden while the library is still empty. */
@Composable
fun ShufflePlayFab(songs: List<Music>, onShuffle: () -> Unit) {
    if (songs.isNotEmpty()) {
        FloatingActionButton(onShuffle) {
            IconShuffle()
        }
    }
}


/** Binds [NowPlayingBar] to the ViewModel; renders nothing while the queue is empty. */
@Composable
fun PlayingBottomBar(
    musicViewModel: MusicViewModel,
    backStack: NavBackStack<Route>
) {
    val state = musicViewModel.nowPlayingState() ?: return
    NowPlayingBar(state, musicViewModel) { backStack.add(Route.Song) }
}

/** The mini player docked above the tab bar. Tapping anywhere opens the full player. */
@Composable
fun NowPlayingBar(
    state: NowPlayingUiState,
    actions: MusicActions,
    onOpen: () -> Unit,
) {
    val progressFactor =
        if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs.toFloat() else 0f

    BottomAppBar(
        Modifier.height(100.dp).invisibleClickable(onOpen)
    ) {
        Column {
            // Progress bar pinned to the top of the bar
            LinearProgressIndicator(
                progress = { progressFactor },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )

            ListItem(
                modifier = Modifier.fillMaxWidth(),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                content = {
                    Text(
                        text = state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = {
                    Text(
                        text = state.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = {
                    AlbumArt(state.artworkUri, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { actions.togglePlayPause() }) {
                            if (state.isPlaying) IconPause() else IconPlay()
                        }
                        IconButton(onClick = { actions.skipNext() }) {
                            IconSkipNext()
                        }
                    }
                }
            )
        }
    }
}
