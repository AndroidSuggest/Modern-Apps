package com.vayunmathur.music.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.Route
import com.vayunmathur.music.platform.MusicViewModel

/** Binds [NowPlayingScreen] to the ViewModel; renders nothing while the queue is empty. */
@Composable
fun SongScreen(backStack: NavBackStack<Route>, musicViewModel: MusicViewModel) {
    val state = musicViewModel.nowPlayingState() ?: return
    NowPlayingScreen(
        state = state,
        actions = musicViewModel,
        backStack = backStack,
        // Expanded queue panel: the upcoming songs from the same origin the player
        // is playing from, so tapping one jumps within the queue. Tapping the
        // currently-playing row is a no-op (it is already showing).
        queue = { queueModifier ->
            NowPlayingQueue(
                musicViewModel = musicViewModel,
                modifier = queueModifier,
            )
        },
    )
}
