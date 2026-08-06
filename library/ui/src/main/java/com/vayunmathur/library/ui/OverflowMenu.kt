package com.vayunmathur.library.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * The three-dot overflow menu in a top app bar.
 *
 * Twenty-one apps build this by hand, each declaring its own `expanded` state,
 * wiring the icon button, and remembering to close the menu in every item's
 * onClick - the last of which is easy to miss, and leaves the menu hanging
 * open over whatever the action did.
 *
 * Items are declared through [OverflowMenuScope], which closes the menu before
 * running the action so an item cannot forget to.
 */
@Composable
fun OverflowMenu(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = { IconMenu() },
    content: @Composable OverflowMenuScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, modifier = modifier) { icon() }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        OverflowMenuScope { expanded = false }.content()
    }
}

/** Receiver for [OverflowMenu] items. */
class OverflowMenuScope internal constructor(private val dismiss: () -> Unit) {

    /** One menu entry. The menu closes before [onClick] runs. */
    @Composable
    fun Item(
        text: String,
        enabled: Boolean = true,
        leadingIcon: @Composable (() -> Unit)? = null,
        trailingIcon: @Composable (() -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        DropdownMenuItem(
            text = { Text(text) },
            onClick = { dismiss(); onClick() },
            enabled = enabled,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
        )
    }
}
