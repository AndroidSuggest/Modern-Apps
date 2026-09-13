package com.vayunmathur.photos.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import com.vayunmathur.library.ink.CanvasTextElement
import com.vayunmathur.library.ink.InkCanvasView
import com.vayunmathur.library.ink.translate
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.TextElement
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/** Photo viewport: image, ink canvas, pointer selection, and all tool overlays. */
@Composable
internal fun ColumnScope.EditPhotoCanvas(
    state: EditPhotoEditorState,
    topPaddingDp: androidx.compose.ui.unit.Dp,
) {
    val preview by state.vm.compositedPreview.collectAsState()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(top = topPaddingDp)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val display = preview ?: state.baseBitmap
        if (display != null) {
            val ratio = display.width.toFloat() / display.height.toFloat()
            val containerRatio = maxW / maxH
            val (vpW, vpH) = if (ratio > containerRatio) maxW to (maxW / ratio) else (maxH * ratio) to maxH

            val density = LocalDensity.current
            val densityFloat = density.density
            val vpWdp = with(density) { vpW.toDp() }
            val vpHdp = with(density) { vpH.toDp() }

            val isDrawing = state.isDrawing
            val currentBrush: Brush = remember(state.activeTool, state.penColor, state.penSize, state.highlighterColor, state.highlighterSize, state.highlighterOpacity) {
                when (state.activeTool) {
                    DrawingTool.Highlighter -> {
                        val argb = state.highlighterColor.toArgb()
                        val alpha = (state.highlighterOpacity * 255).roundToInt()
                        val colorWithAlpha = (alpha shl 24) or (argb and 0x00FFFFFF)
                        Brush.createWithColorIntArgb(StockBrushes.highlighter(), colorWithAlpha, state.highlighterSize, 0.1f)
                    }
                    else -> Brush.createWithColorIntArgb(StockBrushes.pressurePen(), state.penColor.toArgb(), state.penSize, 0.1f)
                }
            }
            val transformState = rememberTransformableState { _, zoomChange, offsetChange, _ ->
                if (state.activeTool == DrawingTool.Pointer) { state.scale *= zoomChange; state.offset += offsetChange }
            }
            val isInkDrawing = isDrawing && state.activeTool != DrawingTool.Pointer && state.activeTool != DrawingTool.Text
            Box(
                modifier = Modifier
                    .size(vpWdp, vpHdp)
                    .onGloballyPositioned {
                        state.currentViewportWidth = it.size.width.toFloat()
                        state.currentViewportHeight = it.size.height.toFloat()
                    }
                    .graphicsLayer {
                        scaleX = state.scale; scaleY = state.scale
                        translationX = state.offset.x; translationY = state.offset.y; clip = false
                    }
                    // Always-visible thin outline of the photo so its edges are
                    // distinguishable from the black background (tracks zoom/pan).
                    .border(1.dp, MaterialTheme.colorScheme.primary)
                    .then(
                        if (state.activeTool == DrawingTool.Text && isDrawing) Modifier.pointerInput(state.activeTool) {
                            detectTapGestures { tapOffset ->
                                val newId = Uuid.random().toString()
                                state.texts.add(
                                    TextElement(
                                        id = newId, text = "New Text",
                                        x = tapOffset.x / size.width, y = tapOffset.y / size.height,
                                        rotation = 0f, color = state.penColor.toArgb(), fontSize = state.textFontSize,
                                    )
                                )
                                state.selectedTextId = newId
                                state.selectedTextIndex = state.texts.size - 1
                                state.textToEdit = state.texts.last()
                            }
                        }
                        else if (!isInkDrawing && state.activeTool != DrawingTool.Pointer && isDrawing)
                            Modifier.transformable(state = transformState)
                        else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                display.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                val canvasTextElements by remember {
                    derivedStateOf {
                        state.texts.map { te ->
                            CanvasTextElement(
                                text = te.text, x = te.x * state.currentViewportWidth, y = te.y * state.currentViewportHeight,
                                rotation = te.rotation, color = te.color, fontSize = te.fontSize,
                                fontFamily = te.fontFamily, bold = te.bold, italic = te.italic, align = te.align,
                            )
                        }
                    }
                }

                InkCanvasView(
                    currentBrush = currentBrush,
                    finishedStrokes = state.inkStrokes.toList(),
                    onStrokeFinished = { state.inkStrokes.add(it); state.redoStrokes.clear() },
                    onStrokeErased = { state.inkStrokes.remove(it) },
                    eraserMode = state.activeTool == DrawingTool.Eraser,
                    enabled = isDrawing && state.activeTool != DrawingTool.Pointer && state.activeTool != DrawingTool.Text,
                    textElements = canvasTextElements,
                    selectedStrokeIndex = state.selectedStrokeIndex,
                    selectedTextIndex = state.selectedTextIndex,
                    modifier = Modifier.fillMaxSize(),
                )

                if (isDrawing && state.activeTool == DrawingTool.Pointer) {
                    Box(
                        modifier = Modifier.fillMaxSize().pointerInput(
                            state.selectedStrokeIndex, state.selectedTextIndex,
                            state.currentViewportWidth, state.currentViewportHeight,
                        ) {
                            detectTapGestures(
                                onDoubleTap = { tapOffset ->
                                    val ti = hitTestText(tapOffset.x, tapOffset.y, state.texts, state.currentViewportWidth, state.currentViewportHeight, densityFloat)
                                    if (ti != null) state.textToEdit = state.texts.getOrNull(ti)
                                },
                                onTap = { tapOffset ->
                                    val ti = hitTestText(tapOffset.x, tapOffset.y, state.texts, state.currentViewportWidth, state.currentViewportHeight, densityFloat)
                                    if (ti != null) {
                                        state.selectedTextIndex = ti; state.selectedStrokeIndex = null; state.selectedTextId = state.texts.getOrNull(ti)?.id
                                    } else {
                                        val si = hitTestStroke(tapOffset.x, tapOffset.y, state.inkStrokes)
                                        state.selectedStrokeIndex = si; state.selectedTextIndex = null; state.selectedTextId = null
                                    }
                                },
                            )
                        }.pointerInput(state.selectedStrokeIndex, state.selectedTextIndex, state.currentViewportWidth, state.currentViewportHeight) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    state.selectedStrokeIndex?.let { idx ->
                                        if (idx in state.inkStrokes.indices) state.inkStrokes[idx] = state.inkStrokes[idx].translate(dragAmount.x, dragAmount.y)
                                    }
                                    state.selectedTextIndex?.let { idx ->
                                        if (idx in state.texts.indices) {
                                            val c = state.texts[idx]
                                            state.texts[idx] = c.copy(x = c.x + dragAmount.x / state.currentViewportWidth, y = c.y + dragAmount.y / state.currentViewportHeight)
                                        }
                                    }
                                },
                            )
                        },
                    )
                }

                EditPhotoToolOverlays(state)
                EditPhotoSelectionOverlays(state)
                EditPhotoPaintOverlays(state)
                EditPhotoCropTransformOverlays(state)
            }
        }
    }
}
