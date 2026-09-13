package com.vayunmathur.launcher.ui

import androidx.compose.ui.unit.dp

/** Drop-target priorities: the hotseat and the drop bar both beat the bare page. */
internal const val PAGE_PRIORITY = 0
internal const val HOTSEAT_PRIORITY = 20

internal const val PREVIEW_ALPHA = 0.12f
internal val PREVIEW_STROKE = 2.dp
internal val HOTSEAT_HEIGHT = 72.dp

/** Past halfway the drawer is the thing in front, and takes the icons with it. */
internal const val DRAWER_INTERACTIVE = 0.5f

/**
 * How far the drawer has to have been dragged for a release to open it.
 *
 * Launcher3's `SUCCESS_TRANSITION_PROGRESS`. Measured against the stock launcher, a slow drag of a
 * third of the screen does not open all-apps there either - short swipes get in through the fling
 * below, which is why that threshold matters far more than this one.
 */
internal const val DRAWER_COMMIT = 0.5f

/** Enough to put the workspace behind the popup, not enough to hide which icon it belongs to. */
internal const val POPUP_SCRIM_ALPHA = 0.32f

/**
 * Past which a swipe counts as a throw rather than a drag.
 *
 * Launcher3's `base_swift_detector_fling_release_velocity`, which is `1dp` compared against a
 * velocity in **px per millisecond** - so a thousand dp per second. Deliberately brisk: the distance
 * threshold above is half the screen, and this is the only other way in, so a value much lower than
 * Launcher3's would open the drawer on swipes meant for the pager.
 */
internal val DRAWER_FLING = 1000.dp
