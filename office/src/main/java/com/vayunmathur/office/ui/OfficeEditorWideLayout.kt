package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Expanded layout for the document editor: outline beside the document.
 *
 * Phone keeps the outline in a navigation drawer (see DocumentScreen). On Expanded
 * the same two pieces sit side by side permanently. Both widths are fractional
 * (weights) so the split scales with the window.
 *
 * Slots only: the caller reuses the outline list and the document view unchanged.
 */
@Composable
fun OfficeEditorWideLayout(
    outline: @Composable (Modifier) -> Unit,
    document: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize()) {
        Column(Modifier.weight(0.3f).fillMaxHeight()) {
            outline(Modifier.fillMaxSize())
        }
        Column(Modifier.weight(0.7f).fillMaxHeight()) {
            document(Modifier.fillMaxSize())
        }
    }
}
