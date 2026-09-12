package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Expanded layout for Now Playing: player beside the queue.
 *
 * Phone stacks artwork/info/controls vertically with no queue on screen
 * ([NowPlayingScreen]). On Expanded the player keeps the left fraction and the
 * queue list takes the right fraction. Both widths are fractional (weights) so
 * the split scales with the window.
 *
 * Slots only: the caller reuses the player column and a queue list unchanged.
 */
@Composable
fun NowPlayingWideLayout(
    player: @Composable (Modifier) -> Unit,
    queue: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize()) {
        Column(Modifier.weight(0.55f).fillMaxHeight()) {
            player(Modifier.fillMaxSize())
        }
        Column(Modifier.weight(0.45f).fillMaxHeight()) {
            queue(Modifier.fillMaxSize())
        }
    }
}
