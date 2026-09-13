package com.vayunmathur.launcher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.DropPlan
import com.vayunmathur.launcher.domain.FolderMerge
import com.vayunmathur.launcher.domain.FolderRules
import com.vayunmathur.launcher.domain.GridPreview
import com.vayunmathur.launcher.domain.GridReorder
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.CellLayout
import com.vayunmathur.launcher.ui.components.CellMetrics
import com.vayunmathur.launcher.ui.components.DragPayload

internal fun commitDrop(
    actions: HomeActions,
    payload: DragPayload,
    page: Int,
    plan: DropPlan?,
    bounds: Rect,
    metrics: CellMetrics,
): Rect? {
    val target = plan?.target ?: return null
    val landing = cellBounds(target, bounds, metrics) ?: return null
    val id = payload.itemId

    if (id == null) {
        // Not on the workspace yet: this came out of the drawer or a popup, so the drop creates the
        // row rather than moving one.
        val shortcut = payload.shortcut
        val key = payload.key
        return when {
            shortcut != null -> {
                actions.addPendingShortcutToHome(shortcut, page, target, plan.displaced)
                landing
            }
            key != null -> {
                actions.addPendingToHome(key, page, target, plan.displaced)
                landing
            }
            else -> null
        }
    }

    val unchanged = payload.origin is ContainerRef.Desktop &&
        payload.originScreen == page &&
        payload.rect == target &&
        plan.displaced.isEmpty()
    if (unchanged) return landing

    // The displaced neighbours go in the same commit, so the page never renders a state the
    // preview did not already show.
    actions.commitMove(id, ContainerRef.Desktop, page, target, 0, plan.displaced)
    return landing
}

internal fun planDrop(
    payload: DragPayload,
    at: Offset,
    pageBounds: Rect,
    metrics: CellMetrics,
    spec: GridSpec,
    placed: Map<Long, CellRect>,
    page: Int,
): DropPlan? {
    if (metrics.cellWidthPx == 0 || metrics.cellHeightPx == 0) return null
    val (cellX, cellY) = metrics.cellAt(at.x - pageBounds.left, at.y - pageBounds.top)
    val wanted = CellRect(cellX, cellY, payload.rect.spanX, payload.rect.spanY)
    val fromHere = payload.origin is ContainerRef.Desktop && payload.originScreen == page
    val direction = if (fromHere) GridReorder.directionOf(payload.rect, wanted) else null
    return GridPreview.plan(spec, placed, payload.itemId, wanted, direction)
}

internal fun mergeTargetAt(
    payload: DragPayload,
    at: Offset,
    pageBounds: Rect,
    metrics: CellMetrics,
    items: List<WorkspaceItem>,
    iconSizePx: Float,
): MergeCandidate? {
    // An app straight out of the drawer has no row to fold in yet, so it lands on the grid.
    if (payload.itemId == null) return null
    if (metrics.cellWidthPx == 0 || metrics.cellHeightPx == 0) return null

    var best: MergeCandidate? = null
    for (item in items) {
        if (item.id == payload.itemId) continue
        if (!FolderRules.canMerge(payload.type, item.type)) continue
        val centre = cellBounds(item.rect, pageBounds, metrics)?.center ?: continue
        val progress = FolderMerge.mergeProgress(
            dx = at.x - centre.x,
            dy = at.y - centre.y,
            iconSizePx = iconSizePx,
            cellWidthPx = metrics.cellWidthPx.toFloat(),
            cellHeightPx = metrics.cellHeightPx.toFloat(),
        )
        if (progress > (best?.progress ?: 0f)) best = MergeCandidate(item.id, progress)
    }
    return best
}

/** The icon a drag is close enough to fold into, and how close - which is what the ring shows. */
internal data class MergeCandidate(val id: Long, val progress: Float)

/**
 * Where a cell is on screen, so a dropped item has somewhere to fly to.
 *
 * The leftover pixels [CellLayout] spreads over its leading cells are ignored here: only this
 * rect's centre is used, and the difference is a couple of pixels at the far edge of the grid.
 */
internal fun cellBounds(rect: CellRect, pageBounds: Rect, metrics: CellMetrics): Rect? {
    if (metrics.cellWidthPx == 0 || metrics.cellHeightPx == 0) return null
    return Rect(
        left = pageBounds.left + rect.cellX * metrics.cellWidthPx.toFloat(),
        top = pageBounds.top + rect.cellY * metrics.cellHeightPx.toFloat(),
        right = pageBounds.left + rect.right * metrics.cellWidthPx.toFloat(),
        bottom = pageBounds.top + rect.bottom * metrics.cellHeightPx.toFloat(),
    )
}
