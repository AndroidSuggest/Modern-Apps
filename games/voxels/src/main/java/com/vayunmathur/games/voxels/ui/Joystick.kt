package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
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
                            emit(clamped, maxDist, isLook, onMove, onLookDelta)
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
                            emit(clamped, maxDistPx, isLook, onMove, onLookDelta)
                        },
                        onDragEnd = {
                            knob = Offset.Zero
                            dragging = false
                            if (isLook) onLookDelta(0f, 0f) else onMove(0f, 0f)
                        },
                        onDragCancel = {
                            knob = Offset.Zero
                            dragging = false
                            if (isLook) onLookDelta(0f, 0f) else onMove(0f, 0f)
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

// Reports the stick's normalized displacement. For move this is a direction; for look it is a
// rotation rate applied every frame, so holding the stick at an offset rotates continuously.
private fun emit(
    clamped: Offset,
    maxDist: Float,
    isLook: Boolean,
    onMove: (Float, Float) -> Unit,
    onLookDelta: (Float, Float) -> Unit
) {
    val nx = clamped.x / maxDist
    val ny = clamped.y / maxDist
    val mag = sqrt(nx * nx + ny * ny)
    if (isLook) {
        // Signs preserve the prior look feel (stick right / up map the same way); -ny so up looks up.
        // A cubic response curve gives fine control near center and fast turns near the edge, so both
        // precise and large movements are possible.
        if (mag < 0.1f) onLookDelta(0f, 0f) else onLookDelta(lookCurve(-nx), lookCurve(ny))
    } else {
        if (mag < 0.15f) onMove(0f, 0f) else onMove(nx, -ny)
    }
}

private fun lookCurve(v: Float): Float {
    val a = kotlin.math.abs(v)
    val shaped = 0.2f * a + 0.8f * a * a * a
    return if (v < 0f) -shaped else shaped
}

// A floating look joystick: drag anywhere within this region and the stick materializes at the touch
// origin, rotating the camera by displacement from there (rate-based, same curve as the move stick).
// Non-drag touches are not consumed, so taps fall through to the world (place/break).
@Composable
fun FloatingLookJoystick(modifier: Modifier, onLookRate: (Float, Float) -> Unit) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    Box(modifier.pointerInput(Unit) {
        val maxR = 70.dp.toPx()
        detectDragGestures(
            onDragStart = { off -> origin = off; knob = Offset.Zero; active = true; onLookRate(0f, 0f) },
            onDrag = { change, drag ->
                change.consume()
                val nk = knob + drag
                val dist = hypot(nk.x, nk.y)
                knob = if (dist > maxR && dist > 0.001f) nk * (maxR / dist) else nk
                val nx = knob.x / maxR
                val ny = knob.y / maxR
                val mag = sqrt(nx * nx + ny * ny)
                if (mag < 0.1f) onLookRate(0f, 0f) else onLookRate(lookCurve(-nx), lookCurve(ny))
            },
            onDragEnd = { active = false; knob = Offset.Zero; onLookRate(0f, 0f) },
            onDragCancel = { active = false; knob = Offset.Zero; onLookRate(0f, 0f) }
        )
    }) {
        if (active) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(color = Color.White.copy(alpha = 0.18f), radius = 70.dp.toPx(), center = origin)
                drawCircle(color = Color.White.copy(alpha = 0.08f), radius = 65.dp.toPx(), center = origin)
                drawCircle(color = Color.White.copy(alpha = 0.5f), radius = 30.dp.toPx(), center = origin + knob)
            }
        }
    }
}

// A control that reports press/release (for hold-to-repeat actions like jump/ascend/descend) and an
// optional double-tap. Styled as a translucent rounded button; `dimmed` shows a disabled look while
// still accepting gestures.
@Composable
fun HoldButton(
    label: String,
    dimmed: Boolean = false,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {},
    onDoubleTap: (() -> Unit)? = null,
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val dtap by rememberUpdatedState(onDoubleTap)
    Box(
        Modifier
            .width(96.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (dimmed) 0.12f else 0.24f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { press(); tryAwaitRelease(); release() },
                    onDoubleTap = { dtap?.invoke() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White.copy(alpha = if (dimmed) 0.5f else 0.95f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}
