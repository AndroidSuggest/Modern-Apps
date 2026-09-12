package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconRepeat
import com.vayunmathur.library.ui.IconRepeatOne
import com.vayunmathur.library.ui.IconShuffle
import com.vayunmathur.library.ui.IconSkipNext
import com.vayunmathur.library.ui.IconSkipPrevious
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SwappedContent
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.AlbumArt
import com.vayunmathur.music.platform.Lyrics
import com.vayunmathur.music.platform.MusicActions
import com.vayunmathur.music.platform.NowPlayingUiState
import com.vayunmathur.music.platform.formatDuration

/**
 * The phone player column: artwork/lyrics, song info, progress, controls.
 * Shared by the single-pane body and the Expanded player slot.
 *
 * Extracted from [NowPlayingScreen.kt] to keep that file under the length
 * limit. Behavior identical — only moved.
 */
@Composable
internal fun PlayerColumn(
    state: NowPlayingUiState,
    actions: MusicActions,
    backStack: NavBackStack<Route>,
    songId: Long?,
    leaving: Boolean,
    lyrics: Lyrics,
    currentIndex: Int,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
            // Toggleable Album Art / Lyrics Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onToggleLyrics() }
            ) {
                SwappedContent(showLyrics) { isShowingLyrics ->
                    if (isShowingLyrics) {
                        LyricsView(lyrics, currentIndex)
                    } else {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(12.dp)
                        ) {
                            AlbumArt(
                                state.artworkUri,
                                Modifier.fillMaxSize().then(
                                    if (songId == null) Modifier
                                    else Modifier.sharedContainer("music-song-art-$songId")
                                ),
                            )
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
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (songId == null) Modifier
                        else Modifier.sharedText("music-song-title-$songId"),
                    )
                    val artistId = state.artistId
                    Text(
                        state.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (artistId != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Two roles, and which one applies depends on where this screen is headed.
                        // Leaving for the artist page it is the origin of the artist morph; the rest
                        // of the time it is the song's artist line arriving from, or returning to,
                        // the list and the mini-player.
                        modifier = when {
                            artistId != null && leaving ->
                                Modifier
                                    .clickable { backStack.add(Route.ArtistDetail(artistId)) }
                                    .sharedText("music-artist-name-$artistId")
                            artistId != null ->
                                Modifier
                                    .clickable { backStack.add(Route.ArtistDetail(artistId)) }
                                    .then(
                                        if (songId == null) Modifier
                                        else Modifier.sharedText("music-song-artist-$songId")
                                    )
                            songId != null -> Modifier.sharedText("music-song-artist-$songId")
                            else -> Modifier
                        },
                    )
                    val albumId = state.albumId
                    if (state.album.isNotEmpty()) {
                        Text(
                            state.album,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (albumId != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (albumId != null) {
                                Modifier
                                    .clickable { backStack.add(Route.AlbumDetail(albumId)) }
                                    .then(
                                        if (leaving) Modifier.sharedText("music-album-title-$albumId")
                                        else Modifier
                                    )
                            } else {
                                Modifier
                            },
                        )
                    }
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
