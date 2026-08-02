package com.vayunmathur.library.ui

import androidx.compose.ui.unit.dp

/**
 * The spacing scale.
 *
 * There are ~3,800 `.dp` literals across the apps spanning 91 distinct values.
 * The intent was already fairly consistent - 8, 16, 4, 12 and 24 account for
 * the vast majority - but the tail (2, 6, 18, 20, 26...) is drift, and it is
 * what makes screens look subtly misaligned next to each other.
 *
 * Named rather than numeric so a reader can tell an intentional gap from a
 * nudge. Reach for the nearest step rather than adding a new one; sizes that
 * are genuinely one-off (an icon's exact dimensions, a hairline) are better as
 * a literal at the call site than as a fake scale entry.
 */
object Spacing {
    /** Hairline gaps inside a single control. */
    val xs = 4.dp

    /** Between tightly related items - an icon and its label. */
    val sm = 8.dp

    /** Between rows of a list, or fields in a form. */
    val md = 12.dp

    /** The default screen margin, and the gap between distinct blocks. */
    val lg = 16.dp

    /** Between sections that should read as separate. */
    val xl = 24.dp

    /** Around empty states and other full-screen centred content. */
    val xxl = 32.dp
}
