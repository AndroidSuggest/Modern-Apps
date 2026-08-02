package com.vayunmathur.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItemColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * The layout behind [ListItem], laid out with a plain Row/Column rather than
 * Material3's alignment-line-based measure policy.
 *
 * Material3's `ListItem` queries its children's baseline alignment lines, which
 * throws a framework NullPointerException
 * (`LookaheadDelegate.getAlignmentLinesOwner`) when the item is remeasured
 * inside the Navigation3 adaptive lookahead pass on a configuration change -
 * in practice, rotating the device. Every app here navigates with
 * `ListDetailSceneStrategy`, so every app that shows a list item was exposed;
 * contacts hit it and wrote this layout, and the fix now sits under the shared
 * wrapper so the other twenty apps get it too.
 *
 * Matches `ListItem`'s appearance: 56dp minimum height, 16dp horizontal
 * padding, 16dp gaps around the leading and trailing slots, and the same
 * per-slot typography and colours.
 *
 * The one thing it does not reproduce is tonal/shadow elevation, which would
 * need a Surface and which nothing here sets away from the default of zero.
 */
@Composable
internal fun SafeListItemLayout(
    colors: ListItemColors,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val container = if (enabled) colors.containerColor else colors.disabledContainerColor
    val main = if (enabled) colors.contentColor else colors.disabledContentColor
    val leading = if (enabled) colors.leadingContentColor else colors.disabledLeadingContentColor
    val trailing = if (enabled) colors.trailingContentColor else colors.disabledTrailingContentColor
    val overline = if (enabled) colors.overlineContentColor else colors.disabledOverlineContentColor
    val supporting =
        if (enabled) colors.supportingContentColor else colors.disabledSupportingContentColor

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container)
            .heightIn(min = 56.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingContent != null) {
            CompositionLocalProvider(LocalContentColor provides leading) { leadingContent() }
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (overlineContent != null) {
                SlotStyle(MaterialTheme.typography.labelSmall, overline, overlineContent)
            }
            SlotStyle(MaterialTheme.typography.bodyLarge, main, content)
            if (supportingContent != null) {
                SlotStyle(MaterialTheme.typography.bodyMedium, supporting, supportingContent)
            }
        }
        if (trailingContent != null) {
            Spacer(Modifier.width(16.dp))
            CompositionLocalProvider(LocalContentColor provides trailing) { trailingContent() }
        }
    }
}

@Composable
private fun SlotStyle(style: TextStyle, color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides color) {
        ProvideTextStyle(style, content)
    }
}
