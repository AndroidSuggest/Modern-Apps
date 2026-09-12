package com.vayunmathur.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.DrawerActions
import com.vayunmathur.launcher.platform.DrawerApp
import com.vayunmathur.launcher.platform.DrawerUiState
import com.vayunmathur.launcher.ui.components.DragPayload
import com.vayunmathur.launcher.ui.components.LauncherAppIcon
import com.vayunmathur.launcher.ui.components.LauncherIconSize
import com.vayunmathur.launcher.ui.components.dragSource
import com.vayunmathur.launcher.ui.components.onAppWindowBounds
import com.vayunmathur.library.ui.Badge
import com.vayunmathur.library.ui.BadgedBox
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text

/**
 * The handful of apps most likely to be wanted, along the top.
 *
 * From a local launch count rather than from the system's `AppPredictionManager`, which is
 * system-only. See [com.vayunmathur.launcher.platform.LauncherViewModel]: the counts live in the
 * DataStore this module already uses, so there is no permission to ask for and nothing to migrate.
 */
@Composable
internal fun PredictionsRow(state: DrawerUiState, actions: DrawerActions, draggable: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        state.predictions.forEach { app ->
            Box(modifier = Modifier.weight(1f)) {
                DrawerCell(
                    app = app,
                    state = state,
                    actions = actions,
                    draggable = draggable,
                    // Keyed apart from the same app in the grid below, or the two register as one
                    // drag source and the grid's bounds win.
                    keyPrefix = "prediction",
                )
            }
        }
    }
}

@Composable
internal fun DrawerCell(
    app: DrawerApp,
    state: DrawerUiState,
    actions: DrawerActions,
    draggable: Boolean,
    modifier: Modifier = Modifier,
    keyPrefix: String = "drawer",
    listLayout: Boolean = false,
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val icon = @Composable {
        LauncherAppIcon(
            key = app.key,
            label = app.label,
            scale = state.iconScale,
            // A row whose name sits beside the icon would otherwise carry it twice. The name is
            // always drawn in list mode: an unlabelled list of icons is just a narrow grid.
            showLabel = state.showLabels && !listLayout,
        )
    }
    val badgedIcon = @Composable {
        if (app.isWorkProfile) {
            // The badge the system draws on a work icon is part of the icon bitmap, which the
            // drawer's own list cannot rely on when an icon fails to rasterise - so the profile is
            // marked here too, where it cannot be lost.
            BadgedBox(badge = { Badge() }) { icon() }
        } else {
            icon()
        }
    }

    Box(
        modifier = modifier
            .onAppWindowBounds { bounds = it }
            // A null itemId marks this as an app rather than a workspace item, so a drop creates
            // a row instead of moving one. Long-press is not handled here at all - the home
            // screen's single gesture owner picks this up by hit-testing the registered bounds.
            .dragSource(
                key = "$keyPrefix-${app.key.componentName.flattenToShortString()}-${app.key.profileSerial}",
                enabled = draggable,
            ) {
                DragPayload(
                    itemId = null,
                    type = LauncherItemType.APPLICATION,
                    label = app.label,
                    key = app.key,
                    rect = CellRect(0, 0),
                    sourceBounds = bounds,
                )
            }
            .clickable {
                actions.launchApp(
                    app.key,
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.right.toInt(),
                    bounds.bottom.toInt(),
                )
            }
            .padding(vertical = Spacing.sm),
    ) {
        if (listLayout) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(LauncherIconSize * state.iconScale)) { badgedIcon() }
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Spacing.lg),
                )
            }
        } else {
            badgedIcon()
        }
    }
}
