package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Maximum content width on expanded windows.
 *
 * A settings form or new-tab page stretched edge to edge across a 1200dp
 * desktop window is unreadable: line lengths explode and controls drift far
 * from their labels. Past this width content stays centred at a readable
 * measure instead. 720dp holds a comfortable two-column form without
 * letterboxing a 840dp tablet — an expanded window narrower than that is
 * simply full-bleed.
 */
val DesktopContentMaxWidth: Dp = 720.dp

/**
 * Centres [content] at a readable measure on very wide windows.
 *
 * Below [DesktopContentMaxWidth] this is a pass-through filling its parent;
 * above it the content is capped at [maxWidth] and centred, so a form or
 * empty state keeps a sane line length instead of stretching across a
 * desktop window. Wrap scaffold *content* in this, never the scaffold
 * itself — bars stay full-bleed while the body is constrained.
 */
@Composable
fun DesktopMaxWidthContainer(
    modifier: Modifier = Modifier,
    maxWidth: Dp = DesktopContentMaxWidth,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = contentAlignment,
    ) {
        Box(
            modifier = Modifier.widthIn(max = maxWidth).fillMaxSize(),
            content = content,
        )
    }
}
