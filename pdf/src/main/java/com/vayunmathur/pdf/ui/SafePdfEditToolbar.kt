package com.vayunmathur.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.BottomAppBar
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconStyle
import com.vayunmathur.library.ui.IconCallout
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconImage
import com.vayunmathur.library.ui.IconNote
import com.vayunmathur.library.ui.IconRedact
import com.vayunmathur.library.ui.IconSelect
import com.vayunmathur.library.ui.IconTextTool
import com.vayunmathur.library.ui.MaterialTheme

/** Bottom edit toolbar: tools, menus, color dots, style, delete/duplicate. */
@Composable
internal fun EditToolbar(
    tool: EditTool,
    onTool: (EditTool) -> Unit,
    shape: ShapeKind,
    onShape: (ShapeKind) -> Unit,
    markup: MarkupKind,
    onMarkup: (MarkupKind) -> Unit,
    color: Color,
    onColor: (Color) -> Unit,
    onStyle: () -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    BottomAppBar {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton({ IconSelect(tint = it) }, tool == EditTool.SELECT) { onTool(EditTool.SELECT) }
            ToolButton({ IconTextTool(tint = it) }, tool == EditTool.TEXT) { onTool(EditTool.TEXT) }
            MarkupMenuButton(markup = markup, active = tool == EditTool.MARKUP, onMarkup = onMarkup)
            ToolButton({ IconDraw(tint = it) }, tool == EditTool.DRAW) { onTool(EditTool.DRAW) }
            ShapeMenuButton(shape = shape, active = tool == EditTool.SHAPE, onShape = onShape)
            LinesMenuButton(tool = tool, onTool = onTool)
            ToolButton({ IconNote(tint = it) }, tool == EditTool.NOTE) { onTool(EditTool.NOTE) }
            ToolButton({ IconCallout(tint = it) }, tool == EditTool.CALLOUT) { onTool(EditTool.CALLOUT) }
            ToolButton({ IconRedact(tint = it) }, tool == EditTool.REDACT) { onTool(EditTool.REDACT) }
            ToolButton({ IconImage(tint = it) }, tool == EditTool.IMAGE) { onTool(EditTool.IMAGE) }

            for (c in listOf(Color.Red, Color.Yellow, Color.Blue, Color.Black)) {
                Box(
                    Modifier
                        .padding(3.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(c)
                        .pointerInput(c) { detectTapGestures { onColor(c) } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (c.copy(alpha = 1f) == color.copy(alpha = 1f)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                    }
                }
            }

            IconButton(onStyle) {
                IconStyle()
            }
            if (canDelete) {
                IconButton(onDuplicate) {
                    IconCopy()
                }
                IconButton(onDelete) { IconDelete() }
            }
        }
    }
}
