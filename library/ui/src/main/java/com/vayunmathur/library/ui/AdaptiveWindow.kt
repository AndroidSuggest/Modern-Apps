package com.vayunmathur.library.ui

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

/**
 * Which width bucket the current window falls in, using the same breakpoints
 * the navigation engine and [paneContentMargin] already use.
 *
 * Apps branch on this instead of naming `androidx.window` types directly (the
 * adaptive dependency is `implementation`, not `api`, in this module — the
 * facade in `MaterialFunctions.kt` is the only legal entry point).
 *
 * - [Compact]: phones in portrait; single column, bottom navigation.
 * - [Medium]: the width at which `MainNavigation` starts showing two panes
 *   side by side (`WIDTH_DP_MEDIUM_LOWER_BOUND`, 600dp). Landscape phones and
 *   small tablets land here.
 * - [Expanded]: large tablets, ChromeOS and desktop windows
 *   (`WIDTH_DP_EXPANDED_LOWER_BOUND`, 840dp). Side panels, rails and
 *   multi-column grids belong here — the same threshold openassistant uses
 *   for its permanent navigation drawer.
 */
enum class WindowWidthClass { Compact, Medium, Expanded }

/**
 * The current window's width bucket. See [WindowWidthClass] for what each
 * bucket means and which threshold it mirrors.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun currentWindowWidthClass(): WindowWidthClass {
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ->
            WindowWidthClass.Expanded
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
            WindowWidthClass.Medium
        else -> WindowWidthClass.Compact
    }
}

/**
 * True on medium widths and up — exactly the condition under which
 * `MainNavigation` shows two panes side by side.
 */
@Composable
fun isMediumWidth(): Boolean = currentWindowWidthClass() != WindowWidthClass.Compact

/**
 * True on expanded widths and up: large tablets, ChromeOS and desktop
 * windows. Gate side panels, navigation rails and multi-column grids on
 * this, not on [isMediumWidth] — a 600dp landscape phone is two-pane but
 * has no room for a third column.
 */
@Composable
fun isExpandedWidth(): Boolean = currentWindowWidthClass() == WindowWidthClass.Expanded
