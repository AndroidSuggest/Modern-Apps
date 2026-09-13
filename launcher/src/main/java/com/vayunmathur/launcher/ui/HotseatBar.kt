package com.vayunmathur.launcher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.LauncherTuning
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.HomeUiState
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.DragPayload
import com.vayunmathur.launcher.ui.components.LauncherItemIcon
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.launcher.ui.components.cell
import com.vayunmathur.launcher.ui.components.dragSource
import com.vayunmathur.launcher.ui.components.dropTarget
import com.vayunmathur.launcher.ui.components.onAppWindowBounds
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.animatedFloat

@Composable
internal fun Hotseat(
    state: HomeUiState,
    actions: HomeActions,
    draggable: Boolean,
    onOpenFolder: (Long, Rect) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HOTSEAT_HEIGHT)
            .padding(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(state.grid.hotseatSlots) { slot ->
            val item = state.hotseat.getOrNull(slot)
            var slotBounds by remember { mutableStateOf(Rect.Zero) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .onAppWindowBounds { slotBounds = it }
                    .dropTarget(
                        key = "hotseat-$slot",
                        priority = HOTSEAT_PRIORITY,
                        // A widget has a span and the hotseat has none to give it.
                        accepts = { it.type != LauncherItemType.APPWIDGET },
                        onDrop = { payload, _ ->
                            val id = payload.itemId
                            if (id == null) {
                                // Straight from the drawer into a hotseat slot.
                                val key = payload.key
                                if (key == null) {
                                    null
                                } else {
                                    actions.addPendingToHotseat(key, slot)
                                    slotBounds
                                }
                            } else {
                                actions.commitMove(
                                    id,
                                    ContainerRef.Hotseat,
                                    0,
                                    CellRect(slot, 0),
                                    rank = slot,
                                )
                                slotBounds
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (item != null) {
                    key(item.id) { HotseatCell(item, state, actions, draggable, onOpenFolder) }
                }
            }
        }
    }
}

@Composable
internal fun HotseatCell(
    item: WorkspaceItem,
    state: HomeUiState,
    actions: HomeActions,
    draggable: Boolean,
    onOpenFolder: (Long, Rect) -> Unit,
) {
    val drag = LocalLauncherDrag.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val dim = animatedFloat(
        if (drag.payload?.itemId == item.id) LauncherTuning.DraggedAlpha else 1f,
        Motion.reorder(),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onAppWindowBounds { bounds = it }
            .dragSource(key = item.id, enabled = draggable) {
                DragPayload(
                    itemId = item.id,
                    type = item.type,
                    label = item.label,
                    key = item.key,
                    canUninstall = item.canUninstall,
                    // The hotseat's "cell" is its slot.
                    rect = CellRect(item.rank, 0),
                    origin = ContainerRef.Hotseat,
                    sourceBounds = bounds,
                )
            }
            .alpha(dim),
        contentAlignment = Alignment.Center,
    ) {
        LauncherItemIcon(
            item = item,
            scale = state.iconScale,
            // Labels are dropped in the hotseat: the slots are narrow, and these are the icons
            // seen often enough not to need naming.
            showLabel = false,
            modifier = Modifier.clickable {
                if (item.type == LauncherItemType.FOLDER) {
                    onOpenFolder(item.id, bounds)
                } else {
                    actions.launchFrom(item, bounds)
                }
            },
        )
    }
}
