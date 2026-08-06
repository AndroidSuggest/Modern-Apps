package com.vayunmathur.music.ui

import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.music.util.AlbumArt
import com.vayunmathur.music.util.MusicActions
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.util.NowPlayingUiState
import com.vayunmathur.music.util.PlaybackSource
import com.vayunmathur.music.util.formatDuration
import com.vayunmathur.music.R
import com.vayunmathur.music.Route

// Data class to hold parsed lyric lines
data class LyricLine(val timestamp: Long, val text: String)

/** Binds [NowPlayingScreen] to the ViewModel; renders nothing while the queue is empty. */
@Composable
fun SongScreen(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val state = musicViewModel.nowPlayingState() ?: return
    NowPlayingScreen(state, musicViewModel, backStack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    state: NowPlayingUiState,
    actions: MusicActions,
    backStack: NavBackStack<Route>,
) {
    // UI States
    var showLyrics by remember { mutableStateOf(false) }
    // Embedded lyrics removed: jaudiotagger dep eliminated (supply-chain mitigation).
    // Lyrics overlay now shows "no lyrics" gracefully.
    val rawLyrics by remember { mutableStateOf("") }

    val parsedLyrics = remember(rawLyrics) { parseLyrics(rawLyrics) }
    val currentLyricIndex = remember(parsedLyrics, state.positionMs) {
        parsedLyrics.indexOfLast { it.timestamp <= state.positionMs }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = { IconNavigation(backStack) },
                actions = {
                    val sourceName = state.sourceName
                    if (state.sourceId != null && sourceName != null) {
                        TextButton(onClick = {
                            when (val src = PlaybackSource.parse(state.sourceId)) {
                                PlaybackSource.AllSongs -> backStack.reset(Route.Home)
                                is PlaybackSource.Album -> backStack.reset(Route.Home, Route.AlbumDetail(src.albumId))
                                is PlaybackSource.Playlist -> backStack.reset(Route.Home, Route.PlaylistDetail(src.playlistId))
                                is PlaybackSource.Artist -> backStack.reset(Route.Home, Route.ArtistDetail(src.artistId))
                                null -> {}
                            }
                        }) {
                            Text(
                                stringResource(R.string.go_to_source, sourceName),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Toggleable Album Art / Lyrics Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { showLyrics = !showLyrics }
            ) {
                Crossfade(targetState = showLyrics, label = "LyricsToggle") { isShowingLyrics ->
                    if (isShowingLyrics) {
                        LyricsView(parsedLyrics, currentLyricIndex)
                    } else {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(12.dp)
                        ) {
                            AlbumArt(state.artworkUri, Modifier.fillMaxSize())
                        }
                    }
                }
            }

            // Song Info
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        state.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {}) {
                    IconMoreVert(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Progress Slider
            Column {
                Slider(
                    value = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs.toFloat() else 0f,
                    onValueChange = { actions.seekTo((it * state.durationMs).toLong()) }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(state.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(formatDuration(state.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Controls
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { actions.toggleRepeat() }) {
                    val repeatTint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> IconRepeatOne(tint = repeatTint)
                        else -> IconRepeat(tint = repeatTint)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { actions.skipPrevious() }) {
                        IconSkipPrevious(Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.width(16.dp))
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable { actions.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        val tint = MaterialTheme.colorScheme.onPrimaryContainer
                        if (state.isPlaying) IconPause(tint = tint) else IconPlay(tint = tint)
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { actions.skipNext() }) {
                        IconSkipNext(Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                IconButton(onClick = { actions.toggleShuffle() }) {
                    IconShuffle(tint = if (state.shuffle) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun LyricsView(lyrics: List<LyricLine>, currentIndex: Int) {
    val listState = rememberLazyListState()

    // Auto-scroll to current lyric
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp)
    ) {
        if (lyrics.isEmpty()) {
            Text(
                stringResource(R.string.no_lyrics_available),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 40.dp)
            ) {
                itemsIndexed(lyrics) { index, line ->
                    val isCurrent = index == currentIndex
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            fontSize = if (isCurrent) 22.sp else 18.sp
                        ),
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

fun parseLyrics(lrcContent: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    // Regex to match [mm:ss.xx] text
    val lyricPattern = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})](.*)")

    lrcContent.lines().forEach { line ->
        try {
            val match = lyricPattern.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val ms = match.groupValues[3].toLong()
                val text = match.groupValues[4].trim()

                // Convert to total milliseconds
                val timestamp = (min * 60 * 1000) + (sec * 1000) + (if (match.groupValues[3].length == 2) ms * 10 else ms)
                if (text.isNotEmpty()) {
                    lines.add(LyricLine(timestamp, text))
                }
            }
        } catch (e: Exception) {
            Log.e("SongScreen", "Error parsing lyric line: $line", e)
        }
    }
    return lines.sortedBy { it.timestamp }
}
