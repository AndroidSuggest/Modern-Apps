package com.vayunmathur.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import com.vayunmathur.launcher.platform.DrawerActions
import com.vayunmathur.launcher.platform.DrawerUiState
import com.vayunmathur.launcher.platform.FolderActions
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.DragFeedback
import com.vayunmathur.launcher.ui.components.DragLayer
import com.vayunmathur.launcher.ui.components.FastScrollStrip
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.library.ui.scrim

@Composable
internal fun BoxScope.HomeOverlays(
    popupOpen: Boolean,
    popup: Animatable<Float, AnimationVector1D>,
    drawerVisible: Boolean,
    drawerState: DrawerUiState,
    drawerActions: DrawerActions,
    drawerGridState: LazyGridState,
    drawerShown: Boolean,
    fastScroll: FastScrollStrip,
    drawer: MutableFloatState,
    onDismissDrawer: () -> Unit,
    openFolder: WorkspaceItem?,
    folderActions: FolderActions,
    showLabels: Boolean,
    iconScale: Float,
    folderAnchor: Rect,
    folder: Animatable<Float, AnimationVector1D>,
    onOpenMenu: (Long, Rect) -> Unit,
    onCloseFolder: () -> Unit,
    drag: LauncherDragController,
) {
    // Drawn, not touched: dismissal is the gesture owner's, so a scrim that swallowed touches
    // would be a second thing arbitrating them.
    if (popupOpen) {
        Box(modifier = Modifier.fillMaxSize().scrim { popup.value * POPUP_SCRIM_ALPHA })
    }

    if (drawerVisible) {
        DrawerContent(
            state = drawerState,
            actions = drawerActions,
            gridState = drawerGridState,
            draggable = drawerShown,
            strip = fastScroll,
            onDismiss = onDismissDrawer,
            // Rising with the swipe and fading in with it, so the wallpaper is still there
            // behind a drawer that is only half up.
            modifier = Modifier.graphicsLayer {
                translationY = size.height * (1f - drawer.floatValue)
                alpha = drawer.floatValue
            },
        )
    }

    if (openFolder != null) {
        FolderContent(
            folder = openFolder,
            actions = folderActions,
            showLabels = showLabels,
            iconScale = iconScale,
            anchor = folderAnchor,
            progress = { folder.value },
            onOpenItemMenu = onOpenMenu,
            onDragLeft = onCloseFolder,
            onDismiss = onCloseFolder,
        )
    }

    // Last, so the dragged item floats above the overlays it may have come out of.
    DragLayer(drag, iconScale)
    DragFeedback(drag)
}
