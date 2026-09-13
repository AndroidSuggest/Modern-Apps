package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.graphics.get
import com.vayunmathur.photos.data.BlurAdj
import com.vayunmathur.photos.data.DodgeBurnStrokes
import com.vayunmathur.photos.data.DodgeBurnStroke
import com.vayunmathur.photos.data.HealingStrokes
import com.vayunmathur.photos.data.LiquifyOp
import com.vayunmathur.photos.data.LiquifyParams
import com.vayunmathur.photos.data.LiquifyTool
import com.vayunmathur.photos.data.RedEyeSpot
import com.vayunmathur.photos.data.RedEyeSpots
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.SmudgeStroke
import com.vayunmathur.photos.data.SmudgeStrokes
import com.vayunmathur.photos.data.applyToBitmap
import com.vayunmathur.photos.data.applyHealingToBitmap
import com.vayunmathur.photos.data.drawGradientBitmap
import com.vayunmathur.photos.data.drawShapeBitmap
import com.vayunmathur.photos.data.floodFillBitmap
import com.vayunmathur.photos.data.PaintShape

/** Non-drawing tool overlays above the photo (selection tint, blur, healing, retouch, liquify). */
@Composable
internal fun EditPhotoToolOverlays(state: EditPhotoEditorState) {
    if (state.isDrawing || state.isCropping) return
    // Keep the live selection visible in every mode (until
    // cleared) so the user knows edits are scoped.
    state.selection?.let { if (state.selDragStart == null) SelectionMaskOverlay(it) }
    val blurAdj = state.document.activeAdjustment<BlurAdj>()
    if (state.editorMode == EditorMode.LensBlur && blurAdj != null && !blurAdj.blur.isIdentity()) {
        BlurOverlay(blurParams = blurAdj.blur, onBlurChanged = { state.vm.updateActiveAdjustment(BlurAdj(it)) })
    }
    if (state.editorMode == EditorMode.Selective) {
        MaskOverlay(mask = state.currentSelectiveMask, showMask = state.showSelectiveMask, onMaskChanged = { state.currentSelectiveMask = it })
    }
    if (state.editorMode == EditorMode.Healing) {
        HealingOverlay(
            sourceX = state.healingSourceX, sourceY = state.healingSourceY, brushSize = state.healingBrushSize,
            isSettingSource = state.isSettingHealingSource,
            onSourceSet = { x, y -> state.healingSourceX = x; state.healingSourceY = y; state.isSettingHealingSource = false },
            onPaint = { x, y -> if (state.healingSourceX != null && state.healingSourceY != null) state.currentHealingPoints = state.currentHealingPoints + (x to y) },
        )
    }
    if (state.editorMode == EditorMode.RedEye) {
        Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { o ->
                val nx = o.x / size.width; val ny = o.y / size.height
                state.vm.applyToActivePixelLayer { RedEyeSpots(listOf(RedEyeSpot(nx, ny, state.brushSize))).applyToBitmap(it) }
            }
        })
    }
    if (state.editorMode == EditorMode.DodgeBurn || state.editorMode == EditorMode.Smudge) {
        Box(modifier = Modifier.fillMaxSize().pointerInput(state.editorMode) {
            detectDragGestures(
                onDrag = { change, _ ->
                    change.consume()
                    val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                    state.retouchPoints = state.retouchPoints + (nx to ny)
                },
            )
        })
    }
    if (state.editorMode == EditorMode.Liquify) {
        var liqStart by remember { mutableStateOf<Offset?>(null) }
        Box(modifier = Modifier.fillMaxSize().pointerInput(state.liquifyTool) {
            detectDragGestures(
                onDragStart = { liqStart = it },
                onDragEnd = {
                    val s = liqStart
                    if (s != null) {
                        val nx = (s.x / size.width).coerceIn(0f, 1f)
                        val ny = (s.y / size.height).coerceIn(0f, 1f)
                        val op = LiquifyOp(
                            tool = state.liquifyTool, x = nx, y = ny,
                            radius = state.liquifyRadius, strength = state.liquifyStrength,
                        )
                        state.vm.applyToActivePixelLayer {
                            LiquifyParams(listOf(op)).applyToBitmap(it)
                        }
                    }
                    liqStart = null
                },
                onDrag = { change, _ ->
                    change.consume()
                    val s = liqStart ?: return@detectDragGestures
                    if (state.liquifyTool == LiquifyTool.Push) {
                        val nx = (s.x / size.width).coerceIn(0f, 1f)
                        val ny = (s.y / size.height).coerceIn(0f, 1f)
                        val ddx = (change.position.x - s.x) / size.width
                        val ddy = (change.position.y - s.y) / size.height
                        val op = LiquifyOp(
                            tool = LiquifyTool.Push,
                            x = nx, y = ny, dx = ddx, dy = ddy,
                            radius = state.liquifyRadius, strength = state.liquifyStrength,
                        )
                        state.vm.applyToActivePixelLayer {
                            LiquifyParams(listOf(op)).applyToBitmap(it)
                        }
                        liqStart = change.position
                    }
                },
            )
        })
    }
}

/** Selection-tool gesture overlays (marquee, lasso, polygon, wand). */
@Composable
internal fun EditPhotoSelectionOverlays(state: EditPhotoEditorState) {
    if (state.isDrawing || state.isCropping) return
    if (state.editorMode != EditorMode.Selection) return
    when (state.selectionTool) {
        SelectionTool.Rectangle, SelectionTool.Ellipse -> SelectionOverlay(
            isEllipse = state.selectionTool == SelectionTool.Ellipse,
            dragStart = state.selDragStart,
            dragCurrent = state.selDragCurrent,
            onStart = { state.selDragStart = it; state.selDragCurrent = it },
            onDrag = { state.selDragCurrent = it },
            onEnd = {
                val s = state.selDragStart; val e = state.selDragCurrent
                if (s != null && e != null) {
                    val l = (minOf(s.x, e.x) / state.currentViewportWidth).coerceIn(0f, 1f)
                    val t = (minOf(s.y, e.y) / state.currentViewportHeight).coerceIn(0f, 1f)
                    val r = (maxOf(s.x, e.x) / state.currentViewportWidth).coerceIn(0f, 1f)
                    val b = (maxOf(s.y, e.y) / state.currentViewportHeight).coerceIn(0f, 1f)
                    if (r - l > 0.01f && b - t > 0.01f) {
                        state.commitMarquee(Rect(l, t, r, b), state.selectionTool == SelectionTool.Ellipse)
                    }
                }
                state.selDragStart = null; state.selDragCurrent = null
            },
        )
        SelectionTool.Lasso -> LassoOverlay(
            points = state.lassoPoints,
            onStart = { state.lassoPoints = listOf(it) },
            onDrag = { state.lassoPoints = state.lassoPoints + it },
            onEnd = {
                val norm = state.lassoPoints.map {
                    (it.x / state.currentViewportWidth).coerceIn(0f, 1f) to (it.y / state.currentViewportHeight).coerceIn(0f, 1f)
                }
                state.lassoPoints = emptyList()
                state.commitPolygon(norm)
            },
        )
        SelectionTool.Polygon -> PolygonOverlay(
            points = state.polygonPoints,
            onTap = { state.polygonPoints = state.polygonPoints + it },
        )
        SelectionTool.Wand -> Box(
            modifier = Modifier.fillMaxSize().pointerInput(state.wandTolerance, state.selectionCombine) {
                detectTapGestures { o ->
                    val nx = (o.x / size.width).coerceIn(0f, 1f)
                    val ny = (o.y / size.height).coerceIn(0f, 1f)
                    state.baseBitmap?.let { bmp ->
                        state.applySelection(Selection.magicWand(bmp, nx, ny, state.wandTolerance))
                    }
                }
            },
        )
    }
}

/** Paint / mask / fill / gradient / shape / eyedropper overlays. */
@Composable
internal fun EditPhotoPaintOverlays(state: EditPhotoEditorState) {
    if (state.isDrawing || state.isCropping) return
    if (state.editorMode == EditorMode.MaskPaint) {
        Box(modifier = Modifier.fillMaxSize().pointerInput(state.maskBrushSize, state.maskPaintReveal) {
            detectDragGestures(
                onDrag = { change, _ ->
                    change.consume()
                    val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                    val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                    state.maskPoints = state.maskPoints + (nx to ny)
                },
                onDragEnd = {
                    if (state.maskPoints.isNotEmpty()) {
                        state.vm.paintOnActiveMask(state.maskPoints, state.maskBrushSize, if (state.maskPaintReveal) 1f else 0f)
                        state.maskPoints = emptyList()
                    }
                },
            )
        })
    }
    if (state.editorMode == EditorMode.Fill) {
        Box(modifier = Modifier.fillMaxSize().pointerInput(state.paintColor, state.fillTolerance) {
            detectTapGestures { o ->
                val nx = (o.x / size.width).coerceIn(0f, 1f)
                val ny = (o.y / size.height).coerceIn(0f, 1f)
                state.vm.applyToActivePixelLayer { floodFillBitmap(it, nx, ny, state.paintColor.toArgb(), state.fillTolerance) }
            }
        })
    }
    if (state.editorMode == EditorMode.GradientTool || state.editorMode == EditorMode.ShapeRect ||
        state.editorMode == EditorMode.ShapeEllipse || state.editorMode == EditorMode.ShapeLine
    ) {
        PaintDragOverlay(
            mode = state.editorMode,
            color = state.paintColor,
            start = state.paintDragStart,
            current = state.paintDragCurrent,
            onStart = { state.paintDragStart = it; state.paintDragCurrent = it },
            onDrag = { state.paintDragCurrent = it },
            onEnd = {
                val s = state.paintDragStart; val e = state.paintDragCurrent
                if (s != null && e != null) {
                    val x0 = (s.x / state.currentViewportWidth).coerceIn(0f, 1f)
                    val y0 = (s.y / state.currentViewportHeight).coerceIn(0f, 1f)
                    val x1 = (e.x / state.currentViewportWidth).coerceIn(0f, 1f)
                    val y1 = (e.y / state.currentViewportHeight).coerceIn(0f, 1f)
                    val argb = state.paintColor.toArgb()
                    when (state.editorMode) {
                        EditorMode.GradientTool -> state.vm.applyToActivePixelLayer {
                            drawGradientBitmap(it, x0, y0, x1, y1, argb)
                        }
                        EditorMode.ShapeRect -> state.vm.applyToActivePixelLayer {
                            drawShapeBitmap(it, PaintShape.Rectangle, minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), argb, state.shapeStrokeWidth)
                        }
                        EditorMode.ShapeEllipse -> state.vm.applyToActivePixelLayer {
                            drawShapeBitmap(it, PaintShape.Ellipse, minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1), argb, state.shapeStrokeWidth)
                        }
                        EditorMode.ShapeLine -> state.vm.applyToActivePixelLayer {
                            drawShapeBitmap(it, PaintShape.Line, x0, y0, x1, y1, argb, state.shapeStrokeWidth)
                        }
                        else -> {}
                    }
                }
                state.paintDragStart = null; state.paintDragCurrent = null
            },
        )
    }
    if (state.editorMode == EditorMode.Eyedropper) {
        val previewBitmap = state.vm.compositedPreview.collectAsState().value
        Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { o ->
                val bmp = previewBitmap ?: state.baseBitmap
                if (bmp != null) {
                    val sx = ((o.x / size.width) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
                    val sy = ((o.y / size.height) * bmp.height).toInt().coerceIn(0, bmp.height - 1)
                    state.useColor(Color(bmp[sx, sy]))
                }
            }
        })
    }
}

/** Crop + free-transform overlays. */
@Composable
internal fun EditPhotoCropTransformOverlays(state: EditPhotoEditorState) {
    if (state.isCropping) {
        CropOverlay(
            cx = state.cropCx, cy = state.cropCy, hx = state.cropHx, hy = state.cropHy, angleDeg = state.cropAngle,
            onChange = { ncx, ncy, nhx, nhy ->
                // Reject the drag in real time if it would pull any crop corner
                // off the photo, so the box snaps back instead of going outside.
                val w = state.document.canvasWidth.coerceAtLeast(1).toFloat()
                val h = state.document.canvasHeight.coerceAtLeast(1).toFloat()
                if (cropWithinImage(ncx, ncy, nhx, nhy, state.cropAngle, w, h)) {
                    state.cropCx = ncx; state.cropCy = ncy; state.cropHx = nhx; state.cropHy = nhy; state.cropAspect = null
                }
            },
            onAngle = { newAngle ->
                // Only accept the rotation if the crop still fits inside the
                // photo at that angle; otherwise ignore it (user must shrink first).
                val w = state.document.canvasWidth.coerceAtLeast(1).toFloat()
                val h = state.document.canvasHeight.coerceAtLeast(1).toFloat()
                if (cropWithinImage(state.cropCx, state.cropCy, state.cropHx, state.cropHy, newAngle, w, h)) {
                    state.cropAngle = newAngle
                }
            },
        )
    }
    if (state.editorMode == EditorMode.FreeTransform) {
        val vpW = state.currentViewportWidth
        val vpH = state.currentViewportHeight
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val pts = listOf(state.ftTL, state.ftTR, state.ftBR, state.ftBL).map { Offset(it.x * vpW, it.y * vpH) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, Color.White, style = Stroke(width = 2f))
        }
        Handle(Offset(state.ftTL.x * vpW, state.ftTL.y * vpH)) { d -> state.ftTL += Offset(d.x / vpW, d.y / vpH) }
        Handle(Offset(state.ftTR.x * vpW, state.ftTR.y * vpH)) { d -> state.ftTR += Offset(d.x / vpW, d.y / vpH) }
        Handle(Offset(state.ftBL.x * vpW, state.ftBL.y * vpH)) { d -> state.ftBL += Offset(d.x / vpW, d.y / vpH) }
        Handle(Offset(state.ftBR.x * vpW, state.ftBR.y * vpH)) { d -> state.ftBR += Offset(d.x / vpW, d.y / vpH) }
    }
}
