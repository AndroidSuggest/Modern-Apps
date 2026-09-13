package com.vayunmathur.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import com.vayunmathur.launcher.platform.ItemMenuActions
import com.vayunmathur.launcher.platform.ItemMenuUiState
import com.vayunmathur.launcher.platform.WidgetPickerActions
import com.vayunmathur.launcher.platform.WidgetPickerUiState
import com.vayunmathur.launcher.ui.components.LauncherPopup
import com.vayunmathur.library.ui.ModalBottomSheet

@Composable
internal fun HomePopups(
    menuItemId: Long?,
    menuAnchor: Rect,
    itemMenuState: ItemMenuUiState,
    itemMenuActions: ItemMenuActions,
    popup: Animatable<Float, AnimationVector1D>,
    onClosePopup: () -> Unit,
    optionsAnchor: Rect?,
    onPickWallpaper: () -> Unit,
    widgetPickerActions: WidgetPickerActions,
    onOpenSettings: () -> Unit,
    settleDrawer: (Float) -> Unit,
    closePopup: () -> Unit,
    widgetPickerState: WidgetPickerUiState,
) {
    // The three windows, all outside the gesture-owning root above. Each hosts its content in a
    // window of its own, which is exactly what keeps its rows tappable while the finger that opened
    // it is still down.
    val menuId = menuItemId
    if (menuId != null) {
        LauncherPopup(anchor = menuAnchor) { placement ->
            ItemMenuContent(
                state = itemMenuState,
                actions = itemMenuActions,
                placement = placement,
                progress = { popup.value },
                onDismiss = onClosePopup,
            )
        }
    }

    optionsAnchor?.let { anchor ->
        LauncherPopup(anchor = anchor) { placement ->
            WorkspaceOptionsContent(
                placement = placement,
                progress = { popup.value },
                onPickWallpaper = {
                    closePopup()
                    onPickWallpaper()
                },
                onOpenWidgets = {
                    closePopup()
                    widgetPickerActions.openWidgetPicker()
                },
                onOpenAppsList = {
                    closePopup()
                    settleDrawer(1f)
                },
                onOpenSettings = {
                    closePopup()
                    onOpenSettings()
                },
            )
        }
    }

    if (widgetPickerState.open) {
        ModalBottomSheet(onDismissRequest = widgetPickerActions::closeWidgetPicker) {
            WidgetPickerContent(state = widgetPickerState, actions = widgetPickerActions)
        }
    }
}
