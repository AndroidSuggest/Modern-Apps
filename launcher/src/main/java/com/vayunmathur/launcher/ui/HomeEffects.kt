package com.vayunmathur.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.geometry.Rect
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.PageEdgeDwell
import com.vayunmathur.library.ui.Motion

/**
 * The screen-level effects: the drawer settle, the edge dwell, and the lifecycle cleanup.
 *
 * Home is the workspace, always, on every fresh arrival. Launcher3 does this explicitly - it
 * sends itself to NORMAL when re-entered - and without the reset the drawer outlives the trip:
 * a swipe up into recents is the same upward flick that opens the drawer, so the drawer opens
 * behind the recents screen and is what you land on when you come back.
 *
 * A drag in flight when the home screen goes away has nowhere to be dropped, and leaving the
 * payload set would show a stale drag layer on the way back in.
 *
 * The popups' windows are not focusable, so back never reaches them - which is what leaves the
 * back handling here, with the rest of the dismissal logic.
 */
@Composable
internal fun HomeScreenEffects(
    drag: LauncherDragController,
    pagerState: PagerState,
    rootWidth: Float,
    drawer: MutableFloatState,
    drawerSettle: MutableState<Float?>,
    popupOpen: Boolean,
    onBack: () -> Unit,
) {
    // The settle, and the only thing that writes `drawer` other than the finger. Restarting it with
    // null is how the finger takes the value back mid-animation.
    LaunchedEffect(drawerSettle.value) {
        val to = drawerSettle.value ?: return@LaunchedEffect
        val from = drawer.floatValue
        animate(
            initialValue = from,
            targetValue = to,
            animationSpec = if (to > from) {
                Motion.open(Motion.DrawerOpenMillis)
            } else {
                Motion.close(Motion.DrawerCloseMillis)
            },
        ) { value, _ -> drawer.floatValue = value }
        drawerSettle.value = null
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        drawerSettle.value = null
        drawer.floatValue = 0f
    }

    PageEdgeDwell(drag, pagerState, rootWidth) { pagerState.animateScrollToPage(it) }

    BackHandler(enabled = popupOpen) { onBack() }

    DisposableEffect(drag) {
        onDispose { drag.cancel() }
    }
}
