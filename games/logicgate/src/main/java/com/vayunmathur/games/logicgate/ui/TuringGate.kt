package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.data.ChipDef
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.WireEnd

@Composable
internal fun TuringGate(
    gateBox: GateBox,
    chipDef: ChipDef?,
    canvasSize: Size,
    wiringFrom: WireEnd?,
    wiringAnchor: Offset?,
    ghostEnd: Offset?,
    isSelected: Boolean,
    onSelect: (String) -> Unit,
    onMoveFinished: (String, Float, Float) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onDelete: (String) -> Unit,
    onStartWiring: (WireEnd) -> Unit,
    onCompleteWiring: (WireEnd, WireEnd) -> Unit,
    onGhost: (Offset?) -> Unit,
    onCancel: () -> Unit,
    resolveTargetAt: (Offset, String?) -> WireEnd?,
    onAnchor: (Offset) -> Unit,
    inDeleteZone: (Float) -> Boolean,
    onDragZone: (Boolean, Boolean) -> Unit,
    density: androidx.compose.ui.unit.Density,
    pinHitR: Float,
    isCompact: Boolean = false
) {
    val def = chipDef ?: return
    val id = gateBox.chip.instanceId
    var localPos by remember(id) { mutableStateOf(Offset(gateBox.left, gateBox.top)) }
    val localPosState by rememberUpdatedState(localPos)
    var dragging by remember(id) { mutableStateOf(false) }
    LaunchedEffect(gateBox.left, gateBox.top) { if (!dragging) localPos = Offset(gateBox.left, gateBox.top) }
    val w = gateBox.w; val h = gateBox.h
    val wDp = with(density) { w.toDp() }
    val hDp = with(density) { h.toDp() }
    val gateStyle = remember(def.id) { gateStyleFor(def) }
    val isTriangle = gateStyle.shape == GateShape.TRIANGLE

    Box(modifier = Modifier.graphicsLayer { translationX = localPos.x; translationY = localPos.y }) {
        Box(
            modifier = Modifier
                .size(wDp, hDp)
                .drawBehind {
                    val strokeCol = when { isSelected -> Color.Yellow; dragging -> Color.White; else -> Turing.gateBlueStroke }
                    val strokeW = (if (isSelected || dragging) 2.dp else 1.4.dp).toPx()
                    drawGate(gateStyle, Turing.gateBlue, strokeCol, strokeW)
                }
                .pointerInput(id) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dragTotal = Offset.Zero
                            dragging = true
                            val downTime = System.currentTimeMillis()
                            var longHandled = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) {
                                    val armed = dragTotal.getDistance() > 4f && inDeleteZone(localPos.y + h / 2f)
                                    onDragZone(false, false)
                                    if (!longHandled) {
                                        when {
                                            armed -> onDelete(id) // dropped in the delete band -> remove
                                            dragTotal.getDistance() > 4f -> onMoveFinished(id, localPos.x, localPos.y)
                                            else -> onSelect(id) // tap selects (yellow outline)
                                        }
                                    }
                                    break
                                }
                                val delta = ch.position - ch.previousPosition
                                dragTotal += delta
                                val elapsed = System.currentTimeMillis() - downTime
                                if (!longHandled && elapsed > 520 && dragTotal.getDistance() < 12f) { longHandled = true; onDragZone(false, false); onDelete(id); break }
                                if (dragTotal.getDistance() > 4f) {
                                    ch.consume()
                                    localPos = clampGateWithPin(localPos + delta, w, h, gateBox.pinOut, canvasSize, 12.dp, density)
                                    onMove(id, localPos.x, localPos.y) // keep connected wires attached live
                                    onDragZone(true, inDeleteZone(localPos.y + h / 2f))
                                }
                            }
                            dragging = false
                        }
                    }
                },
            contentAlignment = if (isTriangle) Alignment.CenterStart else Alignment.Center
        ) {
            // Triangle points right, so keep the label in the wide left portion.
            androidx.compose.material3.Text(text = def.displayName, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(start = if (isTriangle) 6.dp else 4.dp, end = if (isTriangle) 14.dp else 4.dp))
        }
        for (j in 0 until def.inputCount) {
            val lp = gateBox.inputPosLocal(def.inputCount, j)
            val isHover = ghostEnd?.let { (localPos + lp - it).getDistance() < pinHitR } ?: false
            // Source pin (the one being dragged from) — identified precisely by the wiring anchor
            // since WireEnd(id, pin) can't tell an input from an output of the same index.
            val isSrc = wiringFrom?.instanceId == id && wiringAnchor != null && (localPos + lp - wiringAnchor).getDistance() < 12f
            val hitSize = 28.dp; val dotSize = 12.dp
            val halfHit = with(density) { hitSize.toPx() } / 2f
            val onStartState by rememberUpdatedState(onStartWiring)
            val onCompleteState by rememberUpdatedState(onCompleteWiring)
            val onCancelState by rememberUpdatedState(onCancel)
            val onGhostState by rememberUpdatedState(onGhost)
            val wiringFromState by rememberUpdatedState(wiringFrom)
            val resolveTargetState by rememberUpdatedState(resolveTargetAt)
            val onAnchorState by rememberUpdatedState(onAnchor)
            Box(
                modifier = Modifier.offset { IntOffset((lp.x - halfHit).toInt(), (lp.y - halfHit).toInt()) }.size(hitSize).pointerInput(id, j) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if ((down.position - Offset(halfHit, halfHit)).getDistance() > pinHitR) {
                                while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                                continue
                            }
                            if (wiringFromState != null && wiringFromState!!.instanceId != id) {
                                onCompleteState(wiringFromState!!, WireEnd(id, j)); onGhostState(null)
                                while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                                continue
                            }
                            val pinAbs = localPosState + lp
                            onStartState(WireEnd(id, j)); onAnchorState(pinAbs); onGhostState(pinAbs)
                            var cur = pinAbs
                            while (true) {
                                val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) {
                                    val target = resolveTargetState(cur, id)
                                    if (target != null) onCompleteState(WireEnd(id, j), target) else onCancelState()
                                    onGhostState(null); break
                                }
                                cur = pinAbs + (ch.position - down.position); onGhostState(cur); ch.consume()
                            }
                        }
                    }
                },
                contentAlignment = Alignment.Center
            ) { Box(modifier = Modifier.size(dotSize).clip(CircleShape).background(if (isSrc || isHover) Color.Yellow else Color.White).border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape)) }
        }
        for (j in 0 until def.outputCount) {
            val lp = gateBox.outputPosLocal(def.outputCount, j)
            val isSrc = wiringFrom?.instanceId == id && wiringAnchor != null && (localPos + lp - wiringAnchor).getDistance() < 12f
            val hitSize = 28.dp; val dotSize = 12.dp
            val halfHit = with(density) { hitSize.toPx() } / 2f
            val onStartState by rememberUpdatedState(onStartWiring)
            val onCompleteState by rememberUpdatedState(onCompleteWiring)
            val onCancelState by rememberUpdatedState(onCancel)
            val onGhostState by rememberUpdatedState(onGhost)
            val wiringFromState by rememberUpdatedState(wiringFrom)
            val resolveTargetState by rememberUpdatedState(resolveTargetAt)
            val onAnchorState by rememberUpdatedState(onAnchor)
            Box(
                modifier = Modifier.offset { IntOffset((lp.x - halfHit).toInt(), (lp.y - halfHit).toInt()) }.size(hitSize).pointerInput(id, j) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if ((down.position - Offset(halfHit, halfHit)).getDistance() > pinHitR) {
                                while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                                continue
                            }
                            if (wiringFromState != null && wiringFromState!!.instanceId != id) {
                                onCompleteState(wiringFromState!!, WireEnd(id, j)); onGhostState(null)
                                while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                                continue
                            }
                            val pinAbs = localPosState + lp
                            onStartState(WireEnd(id, j)); onAnchorState(pinAbs); onGhostState(pinAbs)
                            var cur = pinAbs
                            while (true) {
                                val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) {
                                    val target = resolveTargetState(cur, id)
                                    if (target != null) onCompleteState(WireEnd(id, j), target) else onCancelState()
                                    onGhostState(null); break
                                }
                                cur = pinAbs + (ch.position - down.position); onGhostState(cur); ch.consume()
                            }
                        }
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                if (isSrc) Box(modifier = Modifier.size(hitSize).clip(CircleShape).background(Color.Yellow.copy(alpha = 0.18f)))
                Box(modifier = Modifier.size(dotSize).clip(CircleShape).background(if (isSrc) Color.Yellow else Turing.pinOut).border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape))
            }
        }
    }
}
