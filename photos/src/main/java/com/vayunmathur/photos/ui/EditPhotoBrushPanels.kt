package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.DodgeBurnMode
import com.vayunmathur.photos.data.SelectionCombine
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun LiquifyPanel(
    tool: com.vayunmathur.photos.data.LiquifyTool,
    onTool: (com.vayunmathur.photos.data.LiquifyTool) -> Unit,
    strength: Float,
    onStrength: (Float) -> Unit,
    radius: Float,
    onRadius: (Float) -> Unit,
) {
    PanelContainer(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.vayunmathur.photos.data.LiquifyTool.entries.forEach { t ->
                SelectableChip(t.name, tool == t, { onTool(t) }, horizontalPadding = 10.dp)
            }
        }
        LabeledSlider(stringResource(R.string.strength), strength * 100f, 5f..100f) { onStrength(it / 100f) }
        LabeledSlider(stringResource(R.string.size), radius * 100f, 5f..50f) { onRadius(it / 100f) }
    }
}

@Composable
internal fun PaintPanel(
    mode: EditorMode,
    color: Color,
    onColor: (Color) -> Unit,
    recentColors: List<Color>,
    fillTolerance: Float,
    onFillTolerance: (Float) -> Unit,
    strokeWidth: Float,
    onStrokeWidth: (Float) -> Unit,
) {
    val swatches = listOf(
        Color.Red, Color(0xFFFF9500), Color.Yellow, Color(0xFF34C759),
        Color(0xFF00B4EC), Color.Blue, Color(0xFF7B2FBE), Color.Magenta,
        Color.White, Color.Black,
    )
    PanelContainer(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (mode == EditorMode.Eyedropper) {
            Text(stringResource(R.string.tap_the_image_to_pick_a_color), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Current color
            Box(modifier = Modifier.size(28.dp).background(color, CircleShape).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape))
            swatches.forEach { sw ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(sw, CircleShape)
                        .border(if (sw == color) 3.dp else 1.dp, if (sw == color) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                        .clickable { onColor(sw) },
                )
            }
        }
        if (recentColors.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.recent), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                recentColors.forEach { rc ->
                    Box(
                        modifier = Modifier.size(24.dp).background(rc, CircleShape)
                            .border(1.dp, Color.Gray, CircleShape).clickable { onColor(rc) },
                    )
                }
            }
        }
        if (mode == EditorMode.Fill) {
            LabeledSlider(stringResource(R.string.tolerance), fillTolerance * 100f, 1f..80f) { onFillTolerance(it / 100f) }
        }
        if (mode == EditorMode.ShapeLine) {
            LabeledSlider(stringResource(R.string.thickness), strokeWidth * 100f, 0.2f..5f) { onStrokeWidth(it / 100f) }
        }
    }
}

@Composable
internal fun PaintDragOverlay(
    mode: EditorMode,
    color: Color,
    start: Offset?,
    current: Offset?,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(mode) {
            detectDragGestures(
                onDragStart = { onStart(it) },
                onDrag = { change, _ -> change.consume(); onDrag(change.position) },
                onDragEnd = { onEnd() },
            )
        },
    ) {
        val s = start; val c = current
        if (s != null && c != null) {
            when (mode) {
                EditorMode.ShapeLine, EditorMode.GradientTool ->
                    drawLine(color, s, c, strokeWidth = 3f)
                EditorMode.ShapeRect -> drawRect(
                    color,
                    Offset(minOf(s.x, c.x), minOf(s.y, c.y)),
                    Size(kotlin.math.abs(c.x - s.x), kotlin.math.abs(c.y - s.y)),
                    style = Stroke(width = 2f),
                )
                EditorMode.ShapeEllipse -> drawOval(
                    color,
                    Offset(minOf(s.x, c.x), minOf(s.y, c.y)),
                    Size(kotlin.math.abs(c.x - s.x), kotlin.math.abs(c.y - s.y)),
                    style = Stroke(width = 2f),
                )
                else -> {}
            }
        }
    }
}

@Composable
internal fun FreeTransformPanel(
    onApply: () -> Unit,
    onReset: () -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onDone: () -> Unit,
) {
    PanelContainer(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.drag_corners_to_distort), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint(stringResource(R.string.drag_the_four_corner_handles_to_scale_ro))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Flip H", true, onFlipH)
            SmallButton("Flip V", true, onFlipV)
            SmallButton("Reset", true, onReset)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Surface(
                modifier = Modifier.clickable { onDone() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) { Text(stringResource(UiR.string.done), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            Surface(
                modifier = Modifier.clickable { onApply() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) { Text(stringResource(R.string.apply), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        }
    }
}

@Composable
internal fun MaskPaintPanel(
    reveal: Boolean,
    onReveal: (Boolean) -> Unit,
    brushSize: Float,
    onBrushSize: (Float) -> Unit,
) {
    PanelContainer(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip("Hide", !reveal, { onReveal(false) })
            SelectableChip("Reveal", reveal, { onReveal(true) })
            InfoHint("Paint on the active layer's mask. Hide erases (black), Reveal restores (white). Adds a full mask if the layer has none.")
        }
        LabeledSlider(stringResource(R.string.brush), brushSize * 100f, 1f..30f) { onBrushSize(it / 100f) }
    }
}

@Composable
internal fun SimpleBrushPanel(label: String, brushSize: Float, onBrushSize: (Float) -> Unit) {
    PanelContainer {
        LabeledSlider(label, brushSize * 100f, 1f..20f) { onBrushSize(it / 100f) }
    }
}

@Composable
internal fun DodgeBurnPanel(mode: DodgeBurnMode, onMode: (DodgeBurnMode) -> Unit, brushSize: Float, onBrushSize: (Float) -> Unit) {
    PanelContainer {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DodgeBurnMode.entries.forEach { m ->
                SelectableChip(m.name, mode == m, { onMode(m) })
            }
        }
        LabeledSlider(stringResource(R.string.brush), brushSize * 100f, 1f..20f) { onBrushSize(it / 100f) }
    }
}

@Composable
internal fun SelectionPanel(
    tool: SelectionTool,
    onTool: (SelectionTool) -> Unit,
    combine: SelectionCombine,
    onCombine: (SelectionCombine) -> Unit,
    feather: Float,
    onFeather: (Float) -> Unit,
    onFeatherCommit: () -> Unit,
    wandTolerance: Float,
    onWandTolerance: (Float) -> Unit,
    polygonPointCount: Int,
    onClosePolygon: () -> Unit,
    hasSelection: Boolean,
    onInvert: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onContentAwareFill: () -> Unit,
    onSelectSubject: () -> Unit,
) {
    PanelContainer(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionTool.entries.forEach { t ->
                SelectableChip(stringResource(t.labelRes), tool == t, { onTool(t) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.combine), fontSize = 12.sp, modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint(stringResource(R.string.new_replaces_the_selection_add_subtract))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionCombine.entries.forEach { c ->
                    SelectableChip(c.name, combine == c, { onCombine(c) })
                }
            }
        }
        if (tool == SelectionTool.Wand) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tolerance), fontSize = 12.sp, modifier = Modifier.width(92.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                InfoHint(stringResource(R.string.how_close_in_color_a_pixel_must_be_to_th))
                com.vayunmathur.library.ui.Slider(value = wandTolerance, onValueChange = onWandTolerance, valueRange = 0.01f..0.6f, modifier = Modifier.weight(1f))
                Text("${(wandTolerance * 100).roundToInt()}", fontSize = 12.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (tool == SelectionTool.Polygon) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.tap_to_add_points, polygonPointCount), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SmallButton("Close path", polygonPointCount >= 3, onClosePolygon)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.edge_softness), fontSize = 12.sp, modifier = Modifier.width(92.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint(stringResource(R.string.blurs_the_selection_s_edge_so_edits_fade))
            com.vayunmathur.library.ui.Slider(value = feather, onValueChange = onFeather, onValueChangeFinished = onFeatherCommit, valueRange = 0f..50f, modifier = Modifier.weight(1f))
            Text("${feather.roundToInt()}", fontSize = 12.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("Invert", hasSelection, onInvert)
            SmallButton("Delete area", hasSelection, onDelete)
            SmallButton("Clear", hasSelection, onClear)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("Select subject", true, onSelectSubject)
            InfoHint("Auto-selects the main foreground subject on-device (no cloud). Works best when the subject stands out from its background; refine with Add/Subtract or the wand afterward.")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("Remove (content-aware)", hasSelection, onContentAwareFill)
            InfoHint(stringResource(R.string.fills_the_selected_area_from_surrounding))
        }
    }
}
