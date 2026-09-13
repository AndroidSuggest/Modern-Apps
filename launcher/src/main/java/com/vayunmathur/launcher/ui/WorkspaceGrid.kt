package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHostView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.DropPlan
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.LauncherTuning
import com.vayunmathur.launcher.domain.ReorderDwell
import com.vayunmathur.launcher.domain.WidgetResize
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.HomeUiState
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.CellMetrics
import com.vayunmathur.launcher.ui.components.DragPayload
import com.vayunmathur.launcher.ui.components.HostedWidget
import com.vayunmathur.launcher.ui.components.LauncherIconSize
import com.vayunmathur.launcher.ui.components.LauncherItemIcon
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.launcher.ui.components.MergeRing
import com.vayunmathur.launcher.ui.components.MissingWidget
import com.vayunmathur.launcher.ui.components.WidgetResizeFrame
import com.vayunmathur.launcher.ui.components.dragSource
import com.vayunmathur.launcher.ui.components.onAppWindowBounds
import com.vayunmathur.launcher.ui.components.reorderPreview
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.animatedFloat


@Composable
internal fun WorkspaceDragPreview(
    page: Int,
    items: List<WorkspaceItem>,
    placed: Map<Long, CellRect>,
    spec: GridSpec,
    bounds: Rect,
    metrics: CellMetrics,
    iconSizePx: Float,
    previewed: MutableState<DropPlan?>,
    merge: MutableState<MergeCandidate?>,
) {
    val drag = LocalLauncherDrag.current
    val payload = drag.payload?.takeIf { !drag.isSettling && bounds.contains(drag.position) }

    // Where the finger is close enough to an icon's centre to fold into it. A rival to the reorder
    // below rather than a drop target of its own: the cell's middle is a folder and the rest of it
    // is a hole in the grid, so one hit test decides which, and leaving the circle restarts the
    // reorder clock.
    val candidateMerge = payload?.let {
        mergeTargetAt(it, drag.position, bounds, metrics, items, iconSizePx)
    }
    merge.value = candidateMerge

    val candidate = payload
        ?.takeIf { candidateMerge == null }
        ?.let { planDrop(it, drag.position, bounds, metrics, spec, placed, page) }

    // The rearrangement is only *committed* after the dwell, which is what stops a drag crossing the
    // page from rearranging every cell it passes over. The committed plan is also what the drop
    // writes: with a dwell the release point is not necessarily the dwelt point.
    val dwell = remember { ReorderDwell(Motion.ReorderTimeoutMillis) }
    LaunchedEffect(candidate, drag.isDragging) {
        if (!drag.isDragging) {
            dwell.reset()
            previewed.value = null
            return@LaunchedEffect
        }
        // One iteration per frame until the dwell is satisfied, so the clock is the same one the
        // animation runs on rather than a wall clock nothing else uses.
        while (!dwell.update(withFrameMillis { it }, candidate)) {
            if (candidate == null) return@LaunchedEffect
        }
        previewed.value = dwell.committed
    }
}

@Composable
internal fun WorkspaceCell(
    item: WorkspaceItem,
    rect: CellRect,
    state: HomeUiState,
    actions: HomeActions,
    page: Int,
    metrics: CellMetrics,
    isResizing: Boolean,
    mergeProgress: () -> Float,
    iconSizePx: Float,
    draggable: Boolean,
    onOpenFolder: (Long, Rect) -> Unit,
    onResizeStep: (CellRect, WidgetResize.Edge) -> Boolean,
    onResizeRelease: () -> Unit,
    widgetView: (Int) -> AppWidgetHostView?,
    updateWidgetSize: (AppWidgetHostView, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val drag = LocalLauncherDrag.current
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val beingDragged = drag.payload?.itemId == item.id
    // Animated, and held dimmed for the whole settle: the drag layer is still flying towards this
    // cell, and two solid copies of one icon is the artefact that would replace the jump.
    val dim = animatedFloat(
        if (beingDragged) LauncherTuning.DraggedAlpha else 1f,
        Motion.reorder(),
    )

    Box(
        modifier = modifier
            // Pulsing while it stands somewhere provisionally, which is what says the arrangement
            // has not been committed yet. Does nothing once the two rects agree.
            .reorderPreview(from = item.rect, to = rect, iconSizePx = iconSizePx)
            .onAppWindowBounds { bounds = it }
            .dragSource(
                key = item.id,
                // A widget showing its resize frame is still movable: its handles sit on the
                // boundary and claim their own touches on the Main pass, so the interior stays the
                // gesture owner's. Disabling the source here is what made every move-drag on a
                // selected widget resize it instead.
                enabled = draggable,
            ) {
                DragPayload(
                    itemId = item.id,
                    type = item.type,
                    label = item.label,
                    key = item.key,
                    canUninstall = item.canUninstall,
                    appWidgetId = item.appWidgetId,
                    rect = item.rect,
                    origin = item.container,
                    originScreen = page,
                    sourceBounds = bounds,
                )
            }
            // Dimmed while it is the thing being dragged, since the drag layer is already drawing
            // a copy of it under the finger.
            .alpha(dim),
        // Centred, not top-aligned: the icon-and-label group is smaller than the cell it sits in,
        // and a grid of icons pinned to the top of taller cells reads as misaligned.
        contentAlignment = Alignment.Center,
    ) {
        // Always composed, never conditionally: it draws nothing at zero progress, and composing it
        // only when a merge is in flight would mean reading that progress here - which is the read
        // this page goes out of its way not to make.
        MergeRing(scale = state.iconScale, progress = mergeProgress)

        when (item.type) {
            LauncherItemType.APPWIDGET -> {
                val widgetId = item.appWidgetId
                if (widgetId == null) {
                    MissingWidget(item.label)
                } else {
                    HostedWidget(
                        appWidgetId = widgetId,
                        widthDp = with(density) {
                            (metrics.cellWidthPx * rect.spanX).toDp().value.toInt()
                        },
                        heightDp = with(density) {
                            (metrics.cellHeightPx * rect.spanY).toDp().value.toInt()
                        },
                        createView = widgetView,
                        updateSize = updateWidgetSize,
                    )
                }
                if (isResizing) {
                    WidgetResizeFrame(
                        // The previewed rect, not the saved one, so each step measures from the
                        // geometry the user is looking at.
                        rect = rect,
                        cellWidthPx = metrics.cellWidthPx,
                        cellHeightPx = metrics.cellHeightPx,
                        onStep = onResizeStep,
                        onRelease = onResizeRelease,
                        // Sized to the widget; the frame grows itself past these bounds.
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            LauncherItemType.FOLDER -> LauncherItemIcon(
                item = item,
                scale = state.iconScale,
                showLabel = state.showLabels,
                modifier = Modifier.clickable { onOpenFolder(item.id, bounds) },
            )

            else -> LauncherItemIcon(
                item = item,
                scale = state.iconScale,
                showLabel = state.showLabels,
                modifier = Modifier.clickable { actions.launchFrom(item, bounds) },
            )
        }
    }
}
