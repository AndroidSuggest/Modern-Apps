package com.vayunmathur.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The rows every settings screen is built from.
 *
 * Eighteen apps had a settings screen and all of them assembled the same three
 * primitives by hand - a [ListItem], sometimes a [Switch], and a
 * [HorizontalDivider] - which is why the padding, the divider inset and
 * whether the whole row or only the switch was tappable all drifted apart.
 *
 * These are deliberately thin. A settings screen is a list of rows in
 * sections; anything more elaborate than that should still be written by hand
 * rather than bent into these.
 */

/**
 * A titled group of settings rows.
 *
 * Pass [title] as null for an untitled leading group, which reads better than
 * inventing a heading for the two or three rows at the top of a screen.
 */
@Composable
fun SettingsSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        content()
    }
}

/**
 * A settings row: a title, optional supporting text, and optional leading and
 * trailing content.
 *
 * [onClick] is optional because plenty of rows only display a value. When it
 * is null the row is not clickable at all, rather than clickable with a no-op,
 * so it does not ripple or claim to be interactive.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    ListItem(
        headlineContent = { Text(title, color = contentColor) },
        supportingContent = supportingText?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else contentColor,
                )
            }
        },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null && enabled) modifier.clickable(onClick = onClick) else modifier,
    )
}

/**
 * A settings row that toggles something.
 *
 * The whole row toggles, not just the switch - a switch is a small target and
 * every app that hand-rolled this made a different call about it.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    leadingContent: @Composable (() -> Unit)? = null,
) {
    SettingsRow(
        title = title,
        modifier = modifier,
        supportingText = supportingText,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        leadingContent = leadingContent,
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
}

/**
 * Divider between settings rows, inset past any leading icon so it lines up
 * with the text rather than cutting the full width.
 */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier, inset: Boolean = true) {
    HorizontalDivider(
        modifier = if (inset) modifier.padding(horizontal = 16.dp) else modifier,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}
