package com.vayunmathur.pdf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Expanded layout for the PDF reader: viewer beside the tools panel.
 *
 * Phone stacks pages vertically with tools in drawers and bottom bars
 * ([SafePdfViewerScreen]). On Expanded the page list keeps most of the window
 * and the outline/tools panel takes the side fraction. Both widths are
 * fractional (weights) so the split scales with the window.
 *
 * Slots only: the caller reuses the page list and the tools/outline content
 * unchanged.
 */
@Composable
fun PdfViewerWideLayout(
    viewer: @Composable (Modifier) -> Unit,
    tools: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize()) {
        Column(Modifier.weight(0.68f).fillMaxHeight()) {
            viewer(Modifier.fillMaxSize())
        }
        Column(Modifier.weight(0.32f).fillMaxHeight()) {
            tools(Modifier.fillMaxSize())
        }
    }
}
