package com.vayunmathur.pdf.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconBezier
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconLine
import com.vayunmathur.library.ui.IconPolyline
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/** Dropdown for text-markup tools (highlight, underline, strikeout, squiggly). */
@Composable
internal fun MarkupMenuButton(
    markup: MarkupKind,
    active: Boolean,
    onMarkup: (MarkupKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) {
            markup.icon()(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (kind in MarkupKind.entries) {
                DropdownMenuItem(
                    leadingIcon = {
                        kind.icon()(
                            if (active && kind == markup) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = { Text(kind.label()) },
                    onClick = { expanded = false; onMarkup(kind) },
                )
            }
        }
    }
}

/** A single toolbar button whose icon reflects the selected [shape]; tapping it
 * opens a dropdown to pick a rectangle/ellipse (outline or filled). */
@Composable
internal fun ShapeMenuButton(
    shape: ShapeKind,
    active: Boolean,
    onShape: (ShapeKind) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) {
            shape.icon()(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (kind in ShapeKind.entries) {
                DropdownMenuItem(
                    leadingIcon = {
                        kind.icon()(
                            if (active && kind == shape) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = { Text(kind.label()) },
                    onClick = { expanded = false; onShape(kind) },
                )
            }
        }
    }
}

/** Dropdown for the line tools (straight line, polyline, Bézier). The button
 * icon reflects the active line tool. */
@Composable
internal fun LinesMenuButton(
    tool: EditTool,
    onTool: (EditTool) -> Unit,
) {
    val lineTools = listOf(
        Triple<EditTool, @Composable (Color) -> Unit, String>(EditTool.LINE, { t -> IconLine(tint = t) }, "Line"),
        Triple<EditTool, @Composable (Color) -> Unit, String>(EditTool.POLYLINE, { t -> IconPolyline(tint = t) }, "Polyline"),
        Triple<EditTool, @Composable (Color) -> Unit, String>(EditTool.BEZIER, { t -> IconBezier(tint = t) }, "Bézier curve"),
    )
    val active = tool == EditTool.LINE || tool == EditTool.POLYLINE || tool == EditTool.BEZIER
    val currentIcon: @Composable (Color) -> Unit = lineTools.firstOrNull { it.first == tool }?.second ?: { t -> IconLine(tint = t) }
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }) {
            currentIcon(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((t, icon, label) in lineTools) {
                DropdownMenuItem(
                    leadingIcon = {
                        icon(
                            if (tool == t) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    text = { Text(label) },
                    onClick = { expanded = false; onTool(t) },
                )
            }
        }
    }
}

/** Single toolbar icon button with selected-state tint. */
@Composable
internal fun ToolButton(
    icon: @Composable (tint: Color) -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick) {
        icon(
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
