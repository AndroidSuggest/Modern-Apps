package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import com.vayunmathur.library.ui.BottomSheetDefaults
import com.vayunmathur.library.ui.BottomSheetScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.library.ui.dynamicLightColorScheme
import com.vayunmathur.library.ui.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.vayunmathur.findfamily.util.FamilyListActions
import com.vayunmathur.findfamily.util.MainPageActions
import com.vayunmathur.findfamily.util.MainPageUiState
import com.vayunmathur.findfamily.util.PersonActions
import com.vayunmathur.library.ui.isExpandedWidth
import kotlin.time.ExperimentalTime

@Composable
fun MainPageContent(
    state: MainPageUiState,
    familyActions: FamilyListActions,
    personActions: PersonActions,
    actions: MainPageActions,
    map: @Composable () -> Unit,
    backupButtons: @Composable () -> Unit = {},
    historyScrubber: @Composable BoxScope.() -> Unit = {},
) {
    val scaffoldState = rememberBottomSheetScaffoldState()

    // Under Layoutlib (store-listing previews) there is no frame clock or real layout pass,
    // so animation/offset-driven effects either no-op or hang the render. Skip them in
    // inspection mode; the page still lays out (peeked sheet + top bar + FAB) statically.
    val inPreview = LocalInspectionMode.current
    // Expanded windows use the side-panel layout below instead of the bottom sheet.
    val expanded = isExpandedWidth() && !inPreview

    // In history mode the sheet is effectively gone - the name moves to the app bar, the drag
    // handle is removed and swiping is disabled - but the peek must stay NON-ZERO.
    //
    // A peek of 0.dp crashes. `rememberBottomSheetScaffoldState` defaults to
    // `skipHiddenState = true`, so `Hidden` is not a legal anchor, and Material3 only creates a
    // `PartiallyExpanded` anchor when the peek height is greater than zero. At 0.dp the sheet
    // therefore has exactly one anchor, `Expanded`, while the state is still targeting
    // `PartiallyExpanded` - and the next measure pass throws
    // `AnchoredDraggableUninitializedException` from `DraggableAnchorsNode.checkOffsetIsValid`.
    // That is a hard FATAL on the main thread, not a layout glitch.
    //
    // 1.dp is below a pixel boundary on every supported density once the sheet is undecorated,
    // so this keeps the anchor alive without showing anything.
    val peekHeight = if (state.historyMode) 1.dp else SheetPeekHeight

    // The FAB sits on top of the always-light map, so color it from a light dynamic
    // scheme regardless of the app's (possibly dark) theme. Captured OUTSIDE the
    // scaffold to avoid the library's in-scaffold color-resolution quirk. Remembered
    // so we don't rebuild the whole palette on every recomposition.
    val context = LocalContext.current
    val lightScheme = remember(context) { dynamicLightColorScheme(context) }
    val fabContainerColor = lightScheme.primaryContainer
    val fabExpandedColor = lightScheme.primary
    val fabContentColor = lightScheme.onPrimaryContainer

    // The sheet's offset when settled at its peek, captured ONCE. Overlays then sit
    // at their default position plus (currentSheetOffset - peekOffset), clamped <= 0,
    // so they move 1:1 with the sheet. Peek is constant (128) whenever overlays show,
    // so a single capture stays correct across list/detail/back-from-history.
    val collapsedSheetOffset = remember { mutableFloatStateOf(Float.NaN) }
    if (!inPreview) {
        LaunchedEffect(scaffoldState) {
            snapshotFlow {
                val st = scaffoldState.bottomSheetState
                val settled = st.currentValue == SheetValue.PartiallyExpanded &&
                    st.targetValue == SheetValue.PartiallyExpanded
                val hist = state.historyMode
                if (settled && !hist) runCatching { st.requireOffset() }.getOrNull() else null
            }.collect { off ->
                if (off != null && collapsedSheetOffset.floatValue.isNaN()) {
                    collapsedSheetOffset.floatValue = off
                }
            }
        }

        // Leaving history sets the peek back to non-zero, but the collapsed sheet needs
        // a nudge to animate back to its peek. Retry until the anchor is ready.
        LaunchedEffect(state.historyMode) {
            if (!state.historyMode) {
                repeat(10) {
                    if (runCatching { scaffoldState.bottomSheetState.partialExpand() }.isSuccess) {
                        return@LaunchedEffect
                    }
                    kotlinx.coroutines.delay(50)
                }
            }
        }
    }

    if (expanded) {
        FindFamilyWideLayout(
            map = { map() },
            panel = {
                if (state.nothingSelected) {
                    FamilyListSheet(state.familyList, familyActions)
                } else if (!state.historyMode && state.selectedUserId != null) {
                    state.person?.let { PersonDetailSheet(it, personActions) }
                }
            },
        )
        return
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetSwipeEnabled = !state.historyMode,
        sheetDragHandle = if (state.historyMode) null else { { BottomSheetDefaults.DragHandle() } },
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // No top bar: the chrome floats over the map as a TopAppBarOverlay in the content
        // slot below, so the map gets the whole window (GitHub #680).
        sheetContent = {
            MainSheetContent(
                state = state,
                familyActions = familyActions,
                personActions = personActions,
                actions = actions,
            )
        }
    ) { _ ->
        // Full-bleed map slot; overlays (FAB, history bar) sit just above the collapsed
        // sheet peek and lift upward as the sheet expands.
        Box(Modifier.fillMaxSize()) {
            // Lift overlays above their peek baseline as the sheet expands:
            // (current - settledPeekOffset), clamped to <= 0. Uses the settled peek
            // offset (sampled above) so transitions never push overlays off-screen.
            val sheetLiftPx: () -> Int = {
                val base = collapsedSheetOffset.floatValue
                val cur = runCatching { scaffoldState.bottomSheetState.requireOffset() }.getOrNull()
                if (cur != null && !base.isNaN()) (cur - base).roundToInt().coerceAtMost(0) else 0
            }

            map()

            // The chrome that used to be the top bar, floating over the map instead. Colored
            // from the light scheme for the same reason the FAB below is: the map is always
            // light, whatever the app's theme.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                MaterialTheme(colorScheme = lightScheme) {
                    MapOverlayBar(state, actions, backupButtons)
                }
            }

            historyScrubber()

            MainFabOverlay(
                state = state,
                actions = actions,
                peekHeight = peekHeight,
                sheetLiftPx = sheetLiftPx,
                fabContainerColor = fabContainerColor,
                fabExpandedColor = fabExpandedColor,
                fabContentColor = fabContentColor,
                lightScheme = lightScheme,
                inPreview = inPreview,
            )
            
        }
    }
}
