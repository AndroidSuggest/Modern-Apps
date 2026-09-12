package com.vayunmathur.library.ui

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A bottom sheet over full-bleed content that rests at **any** height the user
 * drags or flings it to, rather than snapping to a peek/expanded pair.
 *
 * Drop-in shaped like Material 3's `BottomSheetScaffold` so a screen can move
 * across without restructuring, but it is not built on it: `SheetValue` is a
 * closed enum with no custom-anchor API, and its `PartiallyExpanded` is pinned to a
 * fixed peek height.
 *
 * **The whole sheet is the drag surface.** A vertical drag anywhere on it moves it,
 * not just on the handle. Where that drag lands on something scrollable, the two are
 * bridged by [sheetNestedScroll] rather than fought over — see its documentation for
 * the handover rule.
 *
 * There are three ways to say how tall the peek is, and they mirror the three Compose
 * already has for height — `wrapContentHeight`, `fillMaxHeight(fraction)`, `height(dp)`:
 *
 * - [sheetHeader], the preferred one. The peek is *measured*: a sheet whose header is a
 *   title and an action row peeks tall enough for exactly those, and one with no action
 *   row peeks shorter, on every density and every font scale, which is the thing a dp
 *   cannot do. Use this whenever the resting height is a statement about the sheet's own
 *   content.
 * - [sheetPeekFraction], for when it is a statement about the *host* instead — "leave
 *   half the map visible" is spatial, not typographic, so it should scale with the window
 *   and should not move when the user changes font size.
 * - [sheetPeekHeight], the fixed fallback, for sheets that have not been given either.
 *
 * They resolve in that order, most specific first.
 *
 * The sheet is **measured** at its current height rather than offset into place.
 * Offsetting alone would leave the sheet's content — typically ending in a
 * `LazyColumn` — measured at full height, so its scroll extent would be wrong and
 * the nested-scroll edge detection below would misfire.
 *
 * The height is a maximum, not an exact size: content shorter than the sheet's
 * current height shrinks the sheet to fit rather than leaving blank surface below
 * it, and that shorter height also becomes the full height a drag can reach.
 *
 * @param sheetHeader the part of the sheet that stays put — what the peek must be
 *   tall enough to show, and all it is tall enough to show. Drawn above
 *   [sheetContent] and draggable along with the handle, because at the peek it is
 *   the only part of the sheet on screen and it would otherwise be dead to the
 *   gesture that expands it. It gets no horizontal padding of its own, so it should
 *   carry the same insets as [sheetContent].
 * @param sheetPeekFraction peek height as a fraction of the room the sheet has — the
 *   window less the status bar, the same quantity that bounds the full height — so
 *   `0.5f` rests at half and leaves the other half of the host on screen. Counts the
 *   navigation bar, unlike [sheetPeekHeight]: a proportion of the window is a
 *   statement about the whole sheet, not about how much content sits above the inset.
 *   Ignored once a [sheetHeader] has measured.
 * @param sheetPeekHeight peek height for a sheet that passes neither of the above,
 *   above the navigation bar.
 * @param contentKey identifies what [sheetContent] is currently showing. When it
 *   changes, the learned content-height ceiling is discarded so taller content is
 *   not trapped at the previous content's height.
 */
@Composable
fun FreeHeightBottomSheetScaffold(
    sheetContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    state: FreeHeightSheetState = rememberFreeHeightSheetState(),
    sheetPeekHeight: Dp = BottomSheetDefaults.SheetPeekHeight,
    sheetHeader: (@Composable ColumnScope.() -> Unit)? = null,
    sheetPeekFraction: Float? = null,
    sheetContainerColor: Color = BottomSheetDefaults.ContainerColor,
    contentKey: Any? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    // The window insets are read as values, not applied as modifiers: the peek has
    // to clear the navigation bar and the expanded sheet must stop below the status
    // bar, or its drag handle ends up untappable underneath it.
    val navBarPx = WindowInsets.navigationBars.getBottom(density)
    val statusBarPx = WindowInsets.statusBars.getTop(density)
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var sheetContentHeightPx by remember { mutableIntStateOf(0) }
    var peekRegionPx by remember { mutableIntStateOf(0) }
    var headerHeightPx by remember { mutableIntStateOf(0) }

    // Keyed off the header rather than the region: the drag handle is always there and
    // always measures, so a region height alone cannot tell a sheet with an empty header
    // slot from one that has yet to fill it, and such a sheet would peek at a bare handle.
    val availablePx = (containerHeightPx - statusBarPx).coerceAtLeast(0)
    val peekPx = when {
        headerHeightPx > 0 -> peekRegionPx + navBarPx
        // Guarded on having measured: before the first layout pass a fraction of nothing
        // is nothing, and a peek of zero reads as a sheet that failed to open.
        sheetPeekFraction != null && availablePx > 0 ->
            (availablePx * sheetPeekFraction).roundToInt()
        else -> with(density) { sheetPeekHeight.roundToPx() } + navBarPx
    }
    LaunchedEffect(containerHeightPx, peekPx, statusBarPx) {
        if (containerHeightPx > 0) {
            state.onMeasured(peekPx.toFloat(), availablePx.toFloat())
        }
    }

    // New content may well be taller than whatever the last content settled at, so
    // the learned ceiling cannot carry over.
    LaunchedEffect(contentKey, peekPx) { state.resetContentCap(peekPx.toFloat()) }

    val scope = rememberCoroutineScope()
    val nestedScroll = remember(state, scope) { sheetNestedScroll(state, scope) }
    val peekDp = with(density) { peekPx.toDp() }
    val contentPadding = remember(peekDp) { PaddingValues(bottom = peekDp) }
    val navBarDp = with(density) { navBarPx.toDp() }

    Layout(
        modifier = modifier.fillMaxSize().onSizeChanged { containerHeightPx = it.height },
        content = {
            // Exactly two children, so the measure block below can index them.
            Box { content(contentPadding) }
            Surface(color = sheetContainerColor, shape = BottomSheetDefaults.ExpandedShape) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .nestedScroll(nestedScroll)
                        // On the whole sheet, not just the handle: a vertical drag anywhere
                        // moves it. Where that drag starts on something scrollable the child
                        // claims it first — pointer input resolves leaf-to-root — and the
                        // delta comes back up through `nestedScroll` above instead, which is
                        // the handover. So this only ever fires on the parts with nothing
                        // scrollable under the finger: the header, the action row, the tab
                        // row, the chips, the navigation-bar gutter.
                        //
                        // Vertical orientation, so the horizontally-scrolling photo strip is
                        // not hijacked: this never claims a horizontal gesture.
                        .draggable(
                            state = rememberDraggableState { delta ->
                                // Dragging down is a positive delta but shrinks the sheet.
                                state.growBy(-delta, scope)
                            },
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity -> state.flingBy(-velocity) },
                        )
                ) {
                    SheetPeekRegion(
                        onHeights = { region, header ->
                            peekRegionPx = region
                            headerHeightPx = header
                        },
                        modifier = Modifier.fillMaxWidth().clipToBounds(),
                        header = sheetHeader,
                    )
                    Column(Modifier.weight(1f, fill = false).padding(bottom = navBarDp)) {
                        Column(
                            Modifier.onSizeChanged { sheetContentHeightPx = it.height },
                            content = sheetContent,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Read in the layout phase, so a drag remeasures without recomposing.
        val offered = state.offsetPx.toInt().coerceIn(0, height)
        val body = measurables[0].measure(constraints)
        // A maximum, not an exact height: a short sheet wraps its content instead of
        // padding itself out with blank surface.
        val sheet = measurables[1].measure(
            constraints.copy(minHeight = 0, maxHeight = offered),
        )
        state.onContentHeight(sheet.height.toFloat(), offered.toFloat(), scope)
        layout(width, height) {
            body.place(0, 0)
            // A sheet whose header and content both measured to nothing leaves only the
            // drag handle and the navigation-bar inset: a blank bar resting over the host
            // with no information in it. Measured, so the next pass sees it grow, but not
            // drawn. The header counts, because at the peek the content column is squeezed
            // to nothing by design and the header is the whole sheet.
            if (sheetContentHeightPx > 0 || headerHeightPx > 0) {
                sheet.place(0, height - sheet.height)
            }
        }
    }
}

/**
 * The drag handle and [header], measured against unbounded height so what they report
 * is what they *want* rather than what the sheet currently has room for.
 *
 * That distinction is the whole reason this is a `Layout` and not a `Column`. The sheet
 * is measured at whatever height it is at right now, and the peek is derived from this
 * region — so measuring the region against the room on offer would make the peek equal
 * to the height the sheet already had, and a hidden sheet would measure a zero-height
 * region and then peek at nothing. The handle is inside for the same reason: it is part
 * of what the peek has to make room for, and an `onSizeChanged` on it would collapse to
 * zero every time the sheet closed.
 *
 * The region reports its own height clipped to what it was offered rather than squashing
 * its children into it. Below the peek — hidden, or on the way up — the header sliding up
 * behind the sheet's own edge is the reveal; a compressed one is a broken layout.
 *
 * It carries no gesture of its own. The drag lives on the sheet's root column so that the
 * whole surface moves the sheet, and this region is simply part of that surface.
 */
@Composable
private fun SheetPeekRegion(
    onHeights: (region: Int, header: Int) -> Unit,
    modifier: Modifier,
    header: (@Composable ColumnScope.() -> Unit)?,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BottomSheetDefaults.DragHandle()
            }
            Column { header?.invoke(this) }
        },
    ) { measurables, constraints ->
        val unbounded = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val handle = measurables[0].measure(unbounded)
        val headerPlaceable = measurables[1].measure(unbounded)
        onHeights(handle.height + headerPlaceable.height, headerPlaceable.height)
        layout(
            constraints.maxWidth,
            (handle.height + headerPlaceable.height).coerceAtMost(constraints.maxHeight),
        ) {
            handle.place(0, 0)
            headerPlaceable.place(0, handle.height)
        }
    }
}

/**
 * Bridge the sheet and the scrollable content inside it so the two do not fight:
 * upward drag grows the sheet until it is fully expanded and only then scrolls the
 * content, and downward drag collapses the sheet only once the content is back at
 * its top. Velocity is handed over on the same conditions, and only the part the
 * sheet actually absorbs is reported as consumed.
 *
 * This is also what makes "drag anywhere" safe over scrollable regions. The sheet's
 * own `draggable` never sees those gestures — the scrollable child claims them first —
 * so without this bridge a drag over the tab panel would scroll content while the
 * sheet sat still, and a drag two inches to the left would move the sheet. The rule
 * above is what makes both feel like the one gesture the user thinks they are making.
 */
private fun sheetNestedScroll(
    state: FreeHeightSheetState,
    scope: CoroutineScope,
): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource) =
        if (source == NestedScrollSource.UserInput &&
            available.y < 0f &&
            state.offsetPx < state.expandedHeightPx
        ) {
            Offset(0f, -state.growBy(-available.y, scope))
        } else {
            Offset.Zero
        }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ) = if (source == NestedScrollSource.UserInput && available.y > 0f) {
        Offset(0f, -state.growBy(-available.y, scope))
    } else {
        Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val growth = -available.y
        if (growth <= 0f || state.offsetPx >= state.expandedHeightPx) return Velocity.Zero
        return Velocity(0f, -(growth - state.flingBy(growth)))
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val growth = -available.y
        if (growth >= 0f) return Velocity.Zero
        return Velocity(0f, -(growth - state.flingBy(growth)))
    }
}
