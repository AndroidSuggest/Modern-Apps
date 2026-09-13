package com.vayunmathur.launcher.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import com.vayunmathur.launcher.ui.components.VerticalSwipe

@Composable
internal fun rememberDrawerSwipe(
    drawer: MutableFloatState,
    drawerGridState: LazyGridState,
    rootBounds: () -> Rect,
    density: Density,
    canExpandShade: Boolean,
    onSettleDrawer: (Float) -> Unit,
    onTakeBackSettle: () -> Unit,
    onExpandShade: () -> Unit,
): VerticalSwipe {
    // Remembered, not rebuilt: the gesture owner is keyed on this, and a new instance every
    // recomposition would restart the `pointerInput` mid-gesture. State it needs from outside goes
    // through holders rather than being captured, or it would be reading the first composition's
    // values for the life of the screen.
    val shadeAvailable = remember { mutableStateOf(false) }
    SideEffect { shadeAvailable.value = canExpandShade }
    val flingThresholdPx = with(density) { DRAWER_FLING.toPx() }
    return remember {
        VerticalSwipe(
            claims = { travel ->
                val claimed = if (drawer.floatValue <= 0f) {
                    // Upwards from the workspace opens the drawer; downwards pulls the notification
                    // shade down, but only on a build that can actually do it.
                    travel < 0f || shadeAvailable.value
                } else {
                    // Downwards closes it, but only from the top of the list - anywhere else the
                    // swipe is the list's own scrolling, and claiming it here would take that away.
                    travel > 0f &&
                        drawerGridState.firstVisibleItemIndex == 0 &&
                        drawerGridState.firstVisibleItemScrollOffset == 0
                }
                // The finger takes the value back from any settle still running, and carries on from
                // wherever the drawer is now rather than from where the last swipe left it.
                if (claimed) onTakeBackSettle()
                claimed
            },
            onDrag = { delta ->
                // 1:1 with the finger over the drawer's whole travel, as Launcher3 tracks it: a slow
                // drag of a third of the screen does not open all-apps there either. What makes a
                // *short* swipe enough is the fling below, not a shortened range.
                val range = rootBounds().height
                if (range <= 0f) return@VerticalSwipe
                // Negative delta is upwards, which is more drawer. Written straight through, with no
                // coroutine and nothing to cancel, so no delta can be dropped.
                drawer.floatValue = (drawer.floatValue - delta / range).coerceIn(0f, 1f)
            },
            onRelease = { velocity ->
                val to = when {
                    velocity < -flingThresholdPx -> 1f
                    velocity > flingThresholdPx -> 0f
                    // Neither a throw nor a flick: wherever it was left decides.
                    else -> if (drawer.floatValue > DRAWER_COMMIT) 1f else 0f
                }
                // A downward swipe that never moved the drawer was for the shade. Decided on
                // release rather than on the first frame, so a swipe that changes its mind and goes
                // up still opens the drawer.
                if (to == 0f && drawer.floatValue <= 0f && velocity > 0f) {
                    onExpandShade()
                }
                onSettleDrawer(to)
            },
        )
    }
}
