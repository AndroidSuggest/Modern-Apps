package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

@Composable
fun Joystick(
    modifier: Modifier,
    onMove: (x: Float, y: Float) -> Unit,
    onLookDelta: (deltaYaw: Float, deltaPitch: Float) -> Unit,
    isLook: Boolean = false
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var center by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }

    Box(modifier = modifier.size(130.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            center = Offset(size.width / 2f, size.height / 2f)
                            dragging = true
                            val raw = offset - center
                            val dist = kotlin.math.hypot(raw.x, raw.y)
                            val maxDist = 60.dp.toPx()
                            val clamped = if (dist > maxDist && dist > 0.001f) raw * (maxDist / dist) else raw
                            knob = clamped
                            if (!isLook) {
                                val nx = clamped.x / maxDist
                                val ny = clamped.y / maxDist
                                val mag = sqrt(nx * nx + ny * ny)
                                if (mag < 0.15f) onMove(0f, 0f) else onMove(nx, -ny)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val maxDistPx = 60.dp.toPx()
                            val newKnob = knob + Offset(dragAmount.x, dragAmount.y)
                            val dist = kotlin.math.hypot(newKnob.x, newKnob.y)
                            val clamped = if (dist > maxDistPx && dist > 0.001f) {
                                newKnob * (maxDistPx / dist)
                            } else newKnob
                            knob = clamped
                            if (isLook) {
                                val sens = 0.25f
                                onLookDelta(-dragAmount.x * sens, -dragAmount.y * sens)
                            } else {
                                val nx = clamped.x / maxDistPx
                                val ny = clamped.y / maxDistPx
                                val mag = sqrt(nx * nx + ny * ny)
                                if (mag < 0.15f) onMove(0f, 0f) else onMove(nx, -ny)
                            }
                        },
                        onDragEnd = {
                            knob = Offset.Zero
                            dragging = false
                            if (!isLook) onMove(0f, 0f)
                        },
                        onDragCancel = {
                            knob = Offset.Zero
                            dragging = false
                            if (!isLook) onMove(0f, 0f)
                        }
                    )
                }
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color = Color.White.copy(alpha = 0.18f), radius = 65.dp.toPx(), center = c)
            drawCircle(color = Color.White.copy(alpha = 0.08f), radius = 60.dp.toPx(), center = c)
            val knobAbs = c + knob
            drawCircle(
                color = if (dragging) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.3f),
                radius = 27.dp.toPx(),
                center = knobAbs
            )
        }
    }
}

@Composable
fun Crosshair(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = Color.White, radius = 2.5f, center = c)
        drawCircle(color = Color.Black.copy(alpha = 0.5f), radius = 4f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
    }
}
