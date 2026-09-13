package com.vayunmathur.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.vayunmathur.launcher.ui.components.DragPayload
import com.vayunmathur.launcher.ui.components.FastScrollStrip
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.VerticalSwipe
import com.vayunmathur.launcher.ui.components.launcherDragInput

/**
 * The one gesture owner.
 *
 * The drawer and an open folder are overlays inside this root rather than nav destinations,
 * because a drag can only cross from the drawer to the grid, or out of a folder onto the grid,
 * if all three are inside the composition that carries [launcherDragInput]. Launcher3 arranges
 * its `DragLayer` the same way and for the same reason.
 *
 * Everything below relies on it consuming nothing until a long press actually fires, or a swipe
 * turns out to be vertical.
 */
@Composable
internal fun HomeGestureRoot(
    drag: LauncherDragController,
    verticalSwipe: VerticalSwipe,
    fastScroll: FastScrollStrip,
    popupOpen: () -> Boolean,
    onDragStart: (DragPayload) -> Unit,
    onLongPressItem: (DragPayload, Rect) -> Unit,
    onLongPressEmpty: (Offset) -> Unit,
    onDismissPopup: () -> Unit,
    onBounds: (Rect) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { onBounds(it.boundsInWindow()) }
            .launcherDragInput(
                controller = drag,
                onDragStart = onDragStart,
                onLongPressItem = onLongPressItem,
                onLongPressEmpty = onLongPressEmpty,
                popupOpen = popupOpen,
                onDismissPopup = onDismissPopup,
                fastScroll = fastScroll,
                verticalSwipe = verticalSwipe,
            ),
        content = content,
    )
}
