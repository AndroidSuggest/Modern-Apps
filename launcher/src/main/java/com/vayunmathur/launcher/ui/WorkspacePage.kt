package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.DropPlan
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.WidgetResize
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.HomeUiState
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.CellLayout
import com.vayunmathur.launcher.ui.components.CellMetrics
import com.vayunmathur.launcher.ui.components.LauncherIconSize
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.launcher.ui.components.cell
import com.vayunmathur.launcher.ui.components.dropTarget
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing

@Composable
internal fun WorkspacePage(
    state: HomeUiState,
    actions: HomeActions,
    page: Int,
    items: List<WorkspaceItem>,
    resizingId: Long?,
    draggable: Boolean,
    onOpenFolder: (Long, Rect) -> Unit,
    onEndResize: () -> Unit,
    onWidgetDropped: (Long) -> Unit,
    widgetView: (Int) -> AppWidgetHostView?,
    updateWidgetSize: (AppWidgetHostView, Int, Int) -> Unit,
) {
    val drag = LocalLauncherDrag.current
    val density = LocalDensity.current
    var metrics by remember { mutableStateOf(CellMetrics.Empty) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val placed = remember(items) { items.associate { it.id to it.rect } }
    val iconSizePx = with(density) { (LauncherIconSize * state.iconScale).toPx() }

    // A resize in progress, previewed exactly as a drag is: the widget's own new rect plus whatever
    // it shoved aside, held here until the handle is released. Launcher3 commits on release too,
    // and it matters more than it sounds - a write per whole-cell step re-emits the workspace and
    // visibly reloads every hosted widget on the page.
    var resizeRect by remember { mutableStateOf<CellRect?>(null) }
    var resizePushed by remember { mutableStateOf<Map<Long, CellRect>>(emptyMap()) }
    LaunchedEffect(resizingId) {
        if (resizingId == null) {
            resizeRect = null
            resizePushed = emptyMap()
        }
    }

    // What a drop would do, and what the finger is close enough to fold into. Both are computed from
    // the controller's live position, so both are recomputed on every frame of a drag - which is why
    // they are computed in a child that draws nothing rather than here. Read here, they would
    // recompose this page and all twenty of its cells sixty times a second.
    val previewed = remember { mutableStateOf<DropPlan?>(null) }
    val merge = remember { mutableStateOf<MergeCandidate?>(null) }
    WorkspaceDragPreview(
        page = page,
        items = items,
        placed = placed,
        spec = state.grid,
        bounds = bounds,
        metrics = metrics,
        iconSizePx = iconSizePx,
        previewed = previewed,
        merge = merge,
    )

    CellLayout(
        spec = state.grid,
        onMetrics = { metrics = it },
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .dropTarget(
                key = "page-$page",
                priority = PAGE_PRIORITY,
                onDrop = { dropped, at ->
                    val mergeInto = mergeTargetAt(dropped, at, bounds, metrics, items, iconSizePx)
                    val draggedId = dropped.itemId
                    // Asked before the commit, because the answer needs the hosted view and this is
                    // the last point the payload is in hand. `RESIZE_NONE` widgets get no frame -
                    // offering handles that cannot move is worse than offering none.
                    val landedResizable = dropped.type == LauncherItemType.APPWIDGET &&
                        mergeInto == null &&
                        dropped.appWidgetId?.let { id ->
                            widgetView(id)?.appWidgetInfo?.resizeMode != AppWidgetProviderInfo.RESIZE_NONE
                        } == true
                    val landing = when {
                        mergeInto != null && draggedId != null -> {
                            actions.mergeIntoFolder(mergeInto.id, draggedId)
                            // Into the icon it is joining, which is what that folder looks like.
                            placed[mergeInto.id]?.let { cellBounds(it, bounds, metrics) }
                        }
                        else -> commitDrop(
                            actions = actions,
                            payload = dropped,
                            page = page,
                            // The dwelt plan, not one recomputed here: the user watched this
                            // arrangement happen, and it is the one that must be written. Only a
                            // drop that landed before the dwell's first frame has nothing to
                            // commit, and refusing that would send the item flying back for no
                            // reason - so it is planned on the spot instead.
                            plan = previewed.value
                                ?: planDrop(dropped, at, bounds, metrics, state.grid, placed, page),
                            bounds = bounds,
                            metrics = metrics,
                        )
                    }
                    if (landedResizable && draggedId != null) onWidgetDropped(draggedId)
                    landing
                },
            ),
    ) {
        // The bare page. A plain clickable rather than another pointerInput: the root gesture owner
        // bows out when nothing draggable is under the finger, so this only ever sees taps on empty
        // wallpaper - and the long press on it is the gesture owner's, not this one's.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onEndResize,
                ),
        )

        // An outline, and only for a widget. Launcher3 draws no target marker for an icon at all:
        // the neighbours sliding out of the way already say where it is going, and a filled
        // rectangle under a moving icon reads as a second copy of it. No need to exclude a merge
        // here - a widget can never be folded into anything.
        previewed.value
            ?.takeIf { drag.payload?.type == LauncherItemType.APPWIDGET }
            ?.let { plan ->
                Box(
                    modifier = Modifier
                        .cell(plan.target)
                        .padding(Spacing.xs)
                        .border(
                            PREVIEW_STROKE,
                            MaterialTheme.colorScheme.onSurface.copy(alpha = PREVIEW_ALPHA),
                            RoundedCornerShape(12.dp),
                        ),
                )
            }

        items.forEach { item ->
            // Keyed, or a workspace re-emit reuses one item's node - and its hosted
            // AppWidgetHostView - for another item.
            key(item.id) {
                val isResizing = resizingId == item.id
                val rect = when {
                    isResizing -> resizeRect ?: item.rect
                    else -> resizePushed[item.id]
                        ?: previewed.value?.displaced?.get(item.id)
                        ?: item.rect
                }
                WorkspaceCell(
                    item = item,
                    rect = rect,
                    state = state,
                    actions = actions,
                    page = page,
                    metrics = metrics,
                    isResizing = isResizing,
                    // A lambda, read inside the ring's draw block: merge progress changes on every
                    // frame the finger is near an icon, and passing the value would recompose every
                    // cell on the page for each of those frames.
                    mergeProgress = { merge.value?.takeIf { it.id == item.id }?.progress ?: 0f },
                    iconSizePx = iconSizePx,
                    draggable = draggable,
                    onOpenFolder = onOpenFolder,
                    onResizeStep = { candidateRect, edge ->
                        val pushed = WidgetResize.resizeWithPush(
                            spec = state.grid,
                            candidate = candidateRect,
                            others = placed
                                .filterKeys { it != item.id }
                                .mapValues { (id, at) -> resizePushed[id] ?: at },
                            direction = WidgetResize.pushDirection(edge),
                        )
                        if (pushed == null) {
                            false
                        } else {
                            resizeRect = candidateRect
                            resizePushed = resizePushed + pushed
                            true
                        }
                    },
                    onResizeRelease = {
                        resizeRect?.let { actions.resizeItem(item.id, it, resizePushed) }
                    },
                    widgetView = widgetView,
                    updateWidgetSize = updateWidgetSize,
                    modifier = Modifier.cell(rect),
                )
            }
        }
    }
}
