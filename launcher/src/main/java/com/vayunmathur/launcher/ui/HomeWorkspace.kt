package com.vayunmathur.launcher.ui

import android.appwidget.AppWidgetHostView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import com.vayunmathur.launcher.domain.LauncherTuning
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.HomeUiState
import com.vayunmathur.launcher.ui.components.DropBar
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.PageIndicator
import com.vayunmathur.library.ui.Motion

/**
 * The workspace half of the home screen: the drop bar, the pager of grids, the dots and the
 * hotseat.
 *
 * Sinking away as the drawer rises, as Launcher3 does. The progress is read inside the layer
 * block rather than outside it, so a frame of the swipe redraws without recomposing the whole
 * workspace.
 */
@Composable
internal fun HomeWorkspaceColumn(
    state: HomeUiState,
    actions: HomeActions,
    pagerState: PagerState,
    pageCount: Int,
    overlayOpen: Boolean,
    drawer: MutableFloatState,
    resizingId: Long?,
    drag: LauncherDragController,
    onOpenFolder: (Long, Rect) -> Unit,
    onEndResize: () -> Unit,
    onWidgetDropped: (Long) -> Unit,
    onRemoveItem: (Long) -> Unit,
    widgetView: (Int) -> AppWidgetHostView?,
    updateWidgetSize: (AppWidgetHostView, Int, Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .graphicsLayer {
                val scale = 1f -
                    (1f - LauncherTuning.WorkspaceScaleBehindDrawer) * drawer.floatValue
                scaleX = scale
                scaleY = scale
                alpha = 1f - drawer.floatValue
            },
    ) {
        DropBar(
            controller = drag,
            onRemove = onRemoveItem,
            onUninstall = actions::uninstallItem,
            onAppInfo = actions::openItemInfo,
        )

        HorizontalPager(
            state = pagerState,
            // Disabled for the duration of a drag; page changes come from the edge dwell
            // instead. Leaving it on means the pager and the dragged icon fight over the same
            // horizontal movement.
            userScrollEnabled = !drag.isDragging,
            // The pager's default snap is a spring, which overshoots slightly. A workspace
            // page has weight and does not bounce, so this is `PagedView`'s own curve instead.
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                snapAnimationSpec = Motion.pageSnap(),
            ),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) { page ->
            WorkspacePage(
                state = state,
                actions = actions,
                page = page,
                items = state.pages[page].orEmpty(),
                resizingId = resizingId,
                draggable = !overlayOpen,
                onOpenFolder = onOpenFolder,
                onEndResize = onEndResize,
                onWidgetDropped = onWidgetDropped,
                widgetView = widgetView,
                updateWidgetSize = updateWidgetSize,
            )
        }

        PageIndicator(
            pageCount = pageCount,
            // A lambda, so a fling redraws the dots without recomposing this column.
            scrollProgress = {
                pagerState.currentPage + pagerState.currentPageOffsetFraction
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Hotseat(
            state = state,
            actions = actions,
            draggable = !overlayOpen,
            onOpenFolder = onOpenFolder,
        )
    }
}
