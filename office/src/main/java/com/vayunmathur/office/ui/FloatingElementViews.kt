package com.vayunmathur.office.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import com.vayunmathur.library.ui.IconCrop
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*

@Composable
fun FloatingElementLayer(
    elements: List<OdfSlideElement>,
    refW: Float,
    refH: Float,
    editMode: Boolean,
    selectedIndex: Int,
    keyPrefix: String,
    modifier: Modifier = Modifier,
    backgroundColor: Long? = null,
    interactiveBackground: Boolean = true,
    onSelect: (Int) -> Unit = {},
    onElementTextChange: (Int, String) -> Unit = { _, _ -> },
    onBoundsChange: (Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onDelete: (Int) -> Unit = {},
    onCropImage: (Int) -> Unit = {}
) {
    BoxWithConstraints(
        modifier
            .then(backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
            .then(if (editMode && interactiveBackground) Modifier.pointerInput(elements.size) { detectTapGestures { onSelect(-1) } } else Modifier)
    ) {
        val scaleW = maxWidth / refW
        val scaleH = maxHeight / refH
        val density = LocalDensity.current.density
        val pxPerModelW = (scaleW.value * density).coerceAtLeast(0.01f)
        val pxPerModelH = (scaleH.value * density).coerceAtLeast(0.01f)
        // Scale text proportionally to the canvas (pt -> px@96 -> scaled dp). (I60)
        val fontScale = ((96f / 72f) * (maxWidth.value / refW)).coerceIn(0.2f, 4f)

        var live by remember(selectedIndex, elements) { mutableStateOf<FloatArray?>(null) }

        elements.forEachIndexed { ei, element ->
            val isSel = editMode && ei == selectedIndex
            val b = if (isSel) (live ?: element.bounds()) else element.bounds()
            val boxW = (scaleW * b[2]).coerceAtLeast(1.dp)
            val boxH = (scaleH * b[3]).coerceAtLeast(1.dp)
            // Text frames wrap height when neither selected nor editing so large fonts aren't truncated.
            val isImageFrame = element is OdfSlideElement.Frame && (element.frame.image != null || element.frame.chart != null)
            val fixedHeight = editMode || isImageFrame || element is OdfSlideElement.Shape
            var base = Modifier.offset(x = scaleW * b[0], y = scaleH * b[1]).width(boxW)
            base = if (fixedHeight) base.height(boxH) else base
            Box(base) {
                fun startBounds0() = live ?: element.bounds()
                // Body drag-to-move for selected non-text elements (shapes, image/chart frames). (C1)
                val canBodyDrag = isSel && (element is OdfSlideElement.Shape ||
                    (element is OdfSlideElement.Frame && (element.frame.image != null || element.frame.chart != null)))
                Box(
                    Modifier.fillMaxSize()
                        .then(if (editMode && !isSel) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant) else Modifier)
                        .then(if (editMode && !isSel) Modifier.pointerInput(ei) { detectTapGestures { onSelect(ei) } } else Modifier)
                        .then(if (canBodyDrag) Modifier.pointerInput(ei, selectedIndex) {
                            detectDragGestures(
                                onDragStart = { live = element.bounds() },
                                onDragEnd = { live?.let { onBoundsChange(ei, it[0], it[1], it[2], it[3]) }; live = null },
                                onDragCancel = { live = null }
                            ) { change, drag ->
                                change.consume()
                                val c = startBounds0()
                                live = floatArrayOf(c[0] + drag.x / pxPerModelW, c[1] + drag.y / pxPerModelH, c[2], c[3])
                            }
                        } else Modifier)
                ) {
                    when (element) {
                        is OdfSlideElement.Frame -> PositionedFrame(element.frame, fontScale, isSel, "$keyPrefix#$ei") { onElementTextChange(ei, it) }
                        is OdfSlideElement.Shape -> PositionedShape(element.shape, fontScale, isSel, "$keyPrefix#$ei") { onElementTextChange(ei, it) }
                    }
                }
                if (isSel) {
                    Box(Modifier.fillMaxSize().border(2.dp, MaterialTheme.colorScheme.primary))
                    fun startBounds() = live ?: element.bounds()
                    fun commit() { live?.let { onBoundsChange(ei, it[0], it[1], it[2], it[3]) }; live = null }
                    // Move handle (top center, above the box).
                    ElementHandle(
                        Modifier.align(Alignment.TopCenter).offset(y = (-22).dp),
                        icon = { IconDragHandle(modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary) },
                        onStart = { live = element.bounds() }, onEnd = { commit() }
                    ) { dx, dy ->
                        val c = startBounds()
                        live = floatArrayOf(c[0] + dx / pxPerModelW, c[1] + dy / pxPerModelH, c[2], c[3])
                    }
                    // Corner resize handles.
                    ElementHandle(Modifier.align(Alignment.TopStart).offset((-8).dp, (-8).dp), onStart = { live = element.bounds() }, onEnd = { commit() }) { dx, dy ->
                        val c = startBounds(); val mdx = dx / pxPerModelW; val mdy = dy / pxPerModelH
                        live = floatArrayOf(c[0] + mdx, c[1] + mdy, (c[2] - mdx).coerceAtLeast(20f), (c[3] - mdy).coerceAtLeast(20f))
                    }
                    ElementHandle(Modifier.align(Alignment.TopEnd).offset(8.dp, (-8).dp), onStart = { live = element.bounds() }, onEnd = { commit() }) { dx, dy ->
                        val c = startBounds(); val mdx = dx / pxPerModelW; val mdy = dy / pxPerModelH
                        live = floatArrayOf(c[0], c[1] + mdy, (c[2] + mdx).coerceAtLeast(20f), (c[3] - mdy).coerceAtLeast(20f))
                    }
                    ElementHandle(Modifier.align(Alignment.BottomStart).offset((-8).dp, 8.dp), onStart = { live = element.bounds() }, onEnd = { commit() }) { dx, dy ->
                        val c = startBounds(); val mdx = dx / pxPerModelW; val mdy = dy / pxPerModelH
                        live = floatArrayOf(c[0] + mdx, c[1], (c[2] - mdx).coerceAtLeast(20f), (c[3] + mdy).coerceAtLeast(20f))
                    }
                    ElementHandle(Modifier.align(Alignment.BottomEnd).offset(8.dp, 8.dp), onStart = { live = element.bounds() }, onEnd = { commit() }) { dx, dy ->
                        val c = startBounds(); val mdx = dx / pxPerModelW; val mdy = dy / pxPerModelH
                        live = floatArrayOf(c[0], c[1], (c[2] + mdx).coerceAtLeast(20f), (c[3] + mdy).coerceAtLeast(20f))
                    }
                    // Delete (top end, above the box).
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 12.dp, y = (-24).dp).size(22.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.error, CircleShape)
                        .clickable { onDelete(ei) }, contentAlignment = Alignment.Center) {
                        IconDelete(tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                    }
                    // Crop (top start, above the box) — only for image frames.
                    if (element is OdfSlideElement.Frame && element.frame.image != null) {
                        Box(Modifier.align(Alignment.TopStart).offset(x = (-12).dp, y = (-24).dp).size(22.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                            .clickable { onCropImage(ei) }, contentAlignment = Alignment.Center) {
                            IconCrop(tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ElementHandle(modifier: Modifier, icon: (@Composable () -> Unit)? = null, onStart: () -> Unit, onEnd: () -> Unit, onDrag: (Float, Float) -> Unit) {
    Box(
        modifier.size(18.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onStart() },
                    onDragEnd = { onEnd() },
                    onDragCancel = { onEnd() }
                ) { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
            },
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) icon()
    }
}

@Composable
private fun SlideTextEditor(key: String, paragraphs: List<OdfParagraph>, fontScale: Float, onChange: (String) -> Unit, onFocus: () -> Unit = {}) {
    val initial = paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
    var tfv by remember(key) { mutableStateOf(TextFieldValue(initial)) }
    val baseSp = (paragraphs.firstOrNull()?.spans?.firstOrNull()?.fontSize ?: 18f) * fontScale
    val bold = paragraphs.firstOrNull()?.spans?.firstOrNull()?.bold == true
    val italic = paragraphs.firstOrNull()?.spans?.firstOrNull()?.italic == true
    val color = paragraphs.firstOrNull()?.spans?.firstOrNull()?.color?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = tfv,
        onValueChange = { tfv = it; onChange(it.text) },
        textStyle = TextStyle(color = color, fontSize = baseSp.coerceAtLeast(8f).sp, fontWeight = if (bold) FontWeight.Bold else null, fontStyle = if (italic) FontStyle.Italic else null),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
    )
}

@Composable
private fun PositionedFrame(frame: OdfFrame, fontScale: Float, editing: Boolean = false, editKey: String = "", onTextChange: (String) -> Unit = {}) {
    Box(Modifier.fillMaxSize()
        .then(if (frame.rotationDegrees != 0f) Modifier.rotate(frame.rotationDegrees) else Modifier)
        .then(frame.fillColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier)
        .then(frame.strokeColor?.let { Modifier.border((frame.strokeWidth ?: 1f).dp.coerceAtLeast(0.5.dp), Color(it.toInt())) } ?: Modifier)
    ) {
        frame.image?.let { OdfImageView(it, Modifier.fillMaxSize()) }
        frame.chart?.let { OdfChartView(it) }
        if (editing && frame.image == null && frame.chart == null) {
            SlideTextEditor(editKey, frame.paragraphs, fontScale, onTextChange)
        } else if (frame.image == null && frame.chart == null) {
            Column { for (para in frame.paragraphs) ParagraphView(para, fontSizeMultiplier = fontScale) }
        }
    }
}

@Composable
private fun PositionedShape(shape: OdfShape, fontScale: Float, editing: Boolean = false, editKey: String = "", onTextChange: (String) -> Unit = {}) {
    val fillColor = shape.fillColor?.let { Color(it.toInt()) } ?: Color.Transparent
    val strokeColor = shape.strokeColor?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.outline
    val strokeW = shape.strokeWidth ?: 1f
    Box(Modifier.fillMaxSize().then(if (shape.rotationDegrees != 0f) Modifier.rotate(shape.rotationDegrees) else Modifier)) {
        Canvas(Modifier.fillMaxSize()) {
            val dashEffect = if (shape.strokeDashed) PathEffect.dashPathEffect(floatArrayOf(strokeW * 4f, strokeW * 4f)) else null
            val strokeStyle = Stroke(strokeW, pathEffect = dashEffect)
            val grad = shape.fillGradient
            val fillBrush: Brush? = grad?.let { g ->
                val rad = Math.toRadians(g.angle.toDouble())
                val ex = Math.cos(rad).toFloat(); val ey = Math.sin(rad).toFloat()
                Brush.linearGradient(
                    listOf(Color(g.startColor.toInt()), Color(g.endColor.toInt())),
                    start = Offset(0f, 0f),
                    end = Offset(if (ex == 0f && ey == 0f) size.width else size.width * ex, size.height * ey)
                )
            }
            when (shape) {
                is OdfShape.Rect -> { if (fillBrush != null) drawRect(fillBrush) else drawRect(fillColor); drawRect(strokeColor, style = strokeStyle) }
                is OdfShape.Ellipse -> { if (fillBrush != null) drawOval(fillBrush) else drawOval(fillColor); drawOval(strokeColor, style = strokeStyle) }
                is OdfShape.Line -> {
                    // Draw between the actual endpoints: pick the box corner matching endpoint 1 so
                    // negative-slope lines (bottom-left -> top-right) aren't mirrored.
                    val sx = if (shape.x <= shape.x2) 0f else size.width
                    val sy = if (shape.y <= shape.y2) 0f else size.height
                    drawLine(strokeColor, Offset(sx, sy), Offset(size.width - sx, size.height - sy), strokeW, pathEffect = dashEffect)
                }
                is OdfShape.CustomShape -> { if (fillBrush != null) drawRect(fillBrush) else drawRect(fillColor); drawRect(strokeColor, style = strokeStyle) }
                is OdfShape.Polyline -> {
                    if (shape.points.size >= 2) {
                        val path = androidx.compose.ui.graphics.Path()
                        shape.points.forEachIndexed { i, (px, py) ->
                            val fx = if (shape.width != 0f) (px - shape.x) / shape.width * size.width else 0f
                            val fy = if (shape.height != 0f) (py - shape.y) / shape.height * size.height else 0f
                            if (i == 0) path.moveTo(fx, fy) else path.lineTo(fx, fy)
                        }
                        if (shape.closed) { path.close(); drawPath(path, fillColor) }
                        drawPath(path, strokeColor, style = Stroke(strokeW))
                    }
                }
            }
        }
        if (editing && shape !is OdfShape.Line) {
            Box(Modifier.padding(4.dp).align(Alignment.Center)) { SlideTextEditor(editKey, shape.text, fontScale, onTextChange) }
        } else if (shape.text.isNotEmpty()) {
            Column(Modifier.padding(4.dp).align(Alignment.Center)) { for (para in shape.text) ParagraphView(para, fontSizeMultiplier = fontScale) }
        }
    }
}
