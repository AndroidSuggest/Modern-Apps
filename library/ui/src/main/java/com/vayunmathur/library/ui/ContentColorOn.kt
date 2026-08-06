package com.vayunmathur.library.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Black or white, whichever is readable on [background].
 *
 * For content drawn on a colour that comes from data rather than the theme -
 * calendar event colours, contact avatars, user-picked tags - where no
 * `colorScheme.onX` role applies.
 */
fun contentColorOn(background: Color): Color =
    if (background.luminance() > 0.45f) Color.Black else Color.White
