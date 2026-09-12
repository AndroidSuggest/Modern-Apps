package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Expanded side-panel layout for the family map.
 *
 * Phone shows the map full-bleed with the family/person sheet as a bottom sheet
 * ([MainPageContent]). On Expanded the same two pieces sit side by side: the map
 * keeps most of the window and the list/person sheet becomes a side panel. Both
 * widths are fractional (weights) so the split scales with the window.
 *
 * Slots reuse the phone pieces ([FamilyListSheet], [PersonDetailSheet]) unchanged;
 * the caller decides which sheet content to pass as [panel].
 */
@Composable
fun FindFamilyWideLayout(
    map: @Composable (Modifier) -> Unit,
    panel: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize()) {
        Column(Modifier.weight(0.62f).fillMaxHeight()) {
            map(Modifier.fillMaxSize())
        }
        Column(Modifier.weight(0.38f).fillMaxHeight()) {
            panel(Modifier.fillMaxSize())
        }
    }
}
