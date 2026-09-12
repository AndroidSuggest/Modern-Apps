package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.VerticalDivider

/**
 * Expanded-width map layout: the map stays full-bleed full-height in its pane
 * while place cards and search results render in a side panel instead of bottom
 * sheets.
 *
 * Fractional widths, never fixed dp: the map keeps roughly two thirds of the
 * window and the panel takes the rest, so the split holds from a small laptop
 * to a wide desktop monitor. [panelContent] is null when there is nothing to
 * show (browsing with no selection and no search), and then the map simply
 * fills the window. The map itself never splits — gestures, tiles and offline
 * behaviour are whatever [mapContent] already does, untouched.
 */
@Composable
fun MapWideLayout(
    mapContent: @Composable (Modifier) -> Unit,
    panelContent: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val panel = panelContent
    if (panel == null) {
        mapContent(modifier.fillMaxSize())
    } else {
        Row(modifier.fillMaxSize()) {
            mapContent(Modifier.weight(0.65f).fillMaxHeight())
            VerticalDivider(Modifier.fillMaxHeight())
            panel(Modifier.weight(0.35f).fillMaxHeight())
        }
    }
}
