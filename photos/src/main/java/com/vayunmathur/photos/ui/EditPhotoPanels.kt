package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconHighlightAlt
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconBrush
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconEraser
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconVisible
import com.vayunmathur.photos.data.DrawingTool

@Composable
internal fun CategoryBar(onSelect: (ToolCategory) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToolCategory.entries.forEach { cat ->
            Column(
                modifier = Modifier.clickable { onSelect(cat) }.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CategoryIcon(cat)
                Text(stringResource(cat.labelRes), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun CategoryIcon(category: ToolCategory) {
    when (category) {
        ToolCategory.Adjust -> IconSettings()
        ToolCategory.Filters -> IconStar()
        ToolCategory.Retouch -> IconBrush()
        ToolCategory.Select -> IconHighlightAlt()
        ToolCategory.Transform -> IconCrop()
        ToolCategory.Draw -> IconDraw()
        ToolCategory.Paint -> IconEdit()
        ToolCategory.Layers -> IconCopy()
    }
}

@Composable
internal fun ToolScreen(
    category: ToolCategory,
    editorMode: EditorMode,
    onToolSelected: (EditorMode) -> Unit,
    onBack: () -> Unit,
    panel: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { IconBack() }
            Text(stringResource(category.labelRes), fontWeight = FontWeight.Bold)
            InfoHint(stringResource(category.descriptionRes))
        }
        if (categoryTools[category].orEmpty().size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                categoryTools[category].orEmpty().forEach { entry ->
                    Surface(
                        modifier = Modifier.clickable { onToolSelected(entry.mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (editorMode == entry.mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(stringResource(entry.labelRes), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
        }
        val toolLabel = categoryTools[category]?.firstOrNull { it.mode == editorMode }?.labelRes
        if (toolLabel != null && editorMode != EditorMode.None) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(toolLabel), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                InfoHint(stringResource(editorMode.descriptionRes()))
            }
        }
        panel()
    }
}

/** A small ⓘ icon that toggles an inline drop-up description (not a dialog). */
@Composable
internal fun InfoHint(text: String) {
    if (text.isBlank()) return
    var open by remember { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    Box {
        Text(
            "ⓘ",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).clickable { open = !open },
        )
        if (open) {
            Popup(
                popupPositionProvider = AbovePopupPositionProvider(gapPx),
                onDismissRequest = { open = false },
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        text,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.widthIn(max = 240.dp).padding(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SmallButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(6.dp),
        color = if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) { Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
}

@Composable
internal fun DrawingToolbar(
    activeTool: DrawingTool,
    onSelectPointer: () -> Unit,
    onSelectPen: () -> Unit,
    onSelectHighlighter: () -> Unit,
    onSelectEraser: () -> Unit,
    onSelectText: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIcon(active = activeTool == DrawingTool.Pointer, onClick = onSelectPointer) { IconVisible() }
        ToolIcon(active = activeTool == DrawingTool.Pen, onClick = onSelectPen) { IconDraw() }
        ToolIcon(active = activeTool == DrawingTool.Highlighter, onClick = onSelectHighlighter) { IconBrush() }
        ToolIcon(active = activeTool == DrawingTool.Eraser, onClick = onSelectEraser) { IconEraser() }
        ToolIcon(active = activeTool == DrawingTool.Text, onClick = onSelectText) {
            Text("T", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ToolIcon(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier.size(40.dp).then(
                if (active) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape) else Modifier
            ),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
internal fun PanelContainer(
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
internal fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    cornerRadius: Dp = 6.dp,
    horizontalPadding: Dp = 12.dp,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerRadius),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 6.dp))
    }
}
