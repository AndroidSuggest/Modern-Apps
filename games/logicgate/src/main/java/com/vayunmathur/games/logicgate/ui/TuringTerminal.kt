package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.data.WireEnd

@Composable
internal fun TuringBigTerminal(
    box: TerminalBox,
    isInput: Boolean,
    inputWidth: Int,
    canvasSize: Size,
    wiringFrom: WireEnd?,
    ghostEnd: Offset?,
    onMoveFinished: (Int, Float, Float) -> Unit,
    onMove: (Int, Float, Float) -> Unit,
    onStartWiring: (WireEnd) -> Unit,
    onCompleteWiring: (WireEnd, WireEnd) -> Unit,
    onGhost: (Offset?) -> Unit,
    onCancel: () -> Unit,
    resolveTargetAt: (Offset, String?) -> WireEnd?,
    density: androidx.compose.ui.unit.Density,
    pinHitR: Float,
    termWireDotR: Float,
    isCompact: Boolean = false,
    onToggleInput: (Int) -> Unit = {},
    pinOutsideDp: Dp,
    termMinWpx: Float,
    termMaxWpx: Float,
    pinOutsidePx: Float,
    isOn: Boolean = false
) {
    var center by remember(box.idx, box.center) { mutableStateOf(box.center) }
    val centerState by rememberUpdatedState(center)
    var dragging by remember(box.idx) { mutableStateOf(false) }
    LaunchedEffect(box.center) { if (!dragging) center = box.center }
    val visualW = box.pillW.coerceIn(termMinWpx, termMaxWpx)
    val visualH = with(density) { if (isCompact) 46.dp.toPx() else 42.dp.toPx() }
    val halfW = visualW / 2f; val halfH = visualH / 2f
    val isWiringSrc = wiringFrom?.instanceId == "__${if (isInput) "IN" else "OUT"}_${box.idx}"
    val label = box.name
    val bgColor = if (isOn) Turing.bitGreen else Turing.inputRed
    val toggleState by rememberUpdatedState(onToggleInput)
    val moveFinishedState by rememberUpdatedState(onMoveFinished)
    val moveState by rememberUpdatedState(onMove)
    val startWiringState by rememberUpdatedState(onStartWiring)
    val completeWiringState by rememberUpdatedState(onCompleteWiring)
    val ghostState by rememberUpdatedState(onGhost)
    val cancelState by rememberUpdatedState(onCancel)
    val wiringFromState by rememberUpdatedState(wiringFrom)
    val resolveTargetState by rememberUpdatedState(resolveTargetAt)
    val selfEndId = "__${if (isInput) "IN" else "OUT"}_${box.idx}"
    Box(modifier = Modifier.graphicsLayer { translationX = center.x - halfW; translationY = center.y - halfH }) {
        Box(
            modifier = Modifier
                .size(with(density) { visualW.toDp() }, with(density) { visualH.toDp() })
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor)
                .border(1.3.dp, Turing.inputBorder, RoundedCornerShape(16.dp))
                .pointerInput(box.idx) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pinLocalX = if (isInput) visualW + pinOutsidePx else -pinOutsidePx
                            if ((down.position - Offset(pinLocalX, halfH)).getDistance() < termWireDotR + 10f) {
                                while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                                continue
                            }
                            var dragTotal = Offset.Zero; dragging = true; val downTime = System.currentTimeMillis()
                            while (true) {
                                val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) {
                                    val elapsed = System.currentTimeMillis() - downTime
                                    if (dragTotal.getDistance() < 8f && elapsed < 300L && isInput) toggleState(box.idx)
                                    else if (dragTotal.getDistance() > 4f) { moveFinishedState(box.idx, center.x, center.y) }
                                    break
                                }
                                val delta = ch.position - ch.previousPosition
                                dragTotal += delta
                                if (dragTotal.getDistance() > 8f) {
                                    ch.consume()
                                    center = clampTerm(center + delta, visualW, canvasSize, pinOutsidePx, density)
                                    moveState(box.idx, center.x, center.y) // keep connected wires attached live
                                }
                            }
                            dragging = false
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) { androidx.compose.material3.Text(text = label, fontSize = 12.sp, color = Color.White, maxLines = 1, fontWeight = FontWeight.Bold, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp)) }
        val pinHitSize = 36.dp; val pinDotSize = 14.dp
        val pinMod = if (isInput) Modifier.offset { IntOffset((visualW + pinOutsidePx - with(density) { pinHitSize.toPx() } / 2f + 8f).toInt(), (halfH - with(density) { pinHitSize.toPx() } / 2f).toInt()) }
        else Modifier.offset { IntOffset((-pinOutsidePx - with(density) { pinHitSize.toPx() } / 2f - 8f).toInt(), (halfH - with(density) { pinHitSize.toPx() } / 2f).toInt()) }
        Box(modifier = Modifier.then(pinMod).size(pinHitSize).clip(CircleShape).pointerInput(box.idx) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (wiringFromState != null && wiringFromState!!.instanceId != selfEndId) {
                        completeWiringState(wiringFromState!!, WireEnd(selfEndId, 0)); ghostState(null)
                        while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                        continue
                    }
                    val dotAbs = Offset(centerState.x + if (isInput) halfW + pinOutsidePx + 8f else -halfW - pinOutsidePx - 8f, centerState.y)
                    startWiringState(WireEnd(selfEndId, 0)); ghostState(dotAbs)
                    var cur = dotAbs
                    while (true) {
                        val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (ch.changedToUpIgnoreConsumed()) {
                            val target = resolveTargetState(cur, selfEndId)
                            if (target != null) completeWiringState(WireEnd(selfEndId, 0), target) else cancelState()
                            ghostState(null); break
                        }
                        cur = dotAbs + (ch.position - down.position); ghostState(cur); ch.consume()
                    }
                }
            }
        }, contentAlignment = Alignment.Center) { Box(modifier = Modifier.size(pinDotSize).clip(CircleShape).background(if (isWiringSrc) Color.Yellow else if (isInput) Color(0xFF38BDF8) else Color(0xFFF87171)).border(1.2.dp, Color.Black, CircleShape)) }
    }
}
