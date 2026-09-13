package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.util.VelocityTracker

/**
 * How a press ended: with the long-press timeout, or with the finger moving or leaving first.
 *
 * The travel and the velocity so far come with it, so a swipe that grows out of the press does not
 * restart its axis test from wherever the finger happened to be when the timeout expired.
 */
internal class Press(
    val longPressed: Boolean,
    val moved: Boolean,
    val velocity: VelocityTracker,
    val totalDx: Float,
    val totalDy: Float,
)

@Composable
fun LauncherDragInputSection() {
}
