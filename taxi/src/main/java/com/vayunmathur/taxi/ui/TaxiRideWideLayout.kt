package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Expanded side-panel layout for the ride planner.
 *
 * Phone stacks the map above the route inputs and fare results. On Expanded the
 * map keeps most of the window and the inputs/results become a side panel. Both
 * widths are fractional (weights) so the split scales with the window.
 *
 * Slots only: the caller passes the map content and the inputs/results column,
 * reusing the phone pieces unchanged.
 */
@Composable
fun TaxiRideWideLayout(
    map: @Composable (Modifier) -> Unit,
    panel: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize()) {
        Column(Modifier.weight(0.6f).fillMaxHeight()) {
            map(Modifier.fillMaxSize())
        }
        Column(Modifier.weight(0.4f).fillMaxHeight()) {
            panel(Modifier.fillMaxSize())
        }
    }
}
