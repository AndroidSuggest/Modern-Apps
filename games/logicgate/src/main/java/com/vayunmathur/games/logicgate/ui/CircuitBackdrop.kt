package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import com.vayunmathur.games.logicgate.data.*
import kotlin.math.max

/** Values the wire layer reads; built per composition in [CircuitCanvas]. */
internal data class WireLayerData(
    val wires: List<Wire>,
    val outputMaps: List<OutputMapping>,
    val gateBoxes: List<GateBox>,
    val chipDefs: Map<String, ChipDef>,
    val inputLayouts: List<TerminalBox>,
    val outputLayouts: List<TerminalBox>,
    val wiringFrom: WireEnd?,
    val wiringAnchor: Offset?,
    val dragGhostLineEnd: Offset?,
    val pinHitR: Float,
    val termWireDotR: Float,
    val wireHitThreshold: Float,
    val termMinWpx: Float,
    val termMaxWpx: Float,
    val pinOutsidePx: Float,
    val isCompact: Boolean,
)

/** Callbacks the wire layer fires; wrapped in `rememberUpdatedState` internally. */
internal data class WireLayerEvents(
    val onWireDelete: (String) -> Unit,
    val onOutputMapDelete: (Int) -> Unit,
    val onCreateWire: (from: WireEnd, to: WireEnd) -> Unit,
    val onCancelWiring: () -> Unit,
    val onGhostLine: (Offset?) -> Unit,
    val onSelectGate: (String?) -> Unit,
)

internal fun dotForInput(t: TerminalBox, pinOutsidePx: Float, termMinWpx: Float, termMaxWpx: Float): Offset =
    Offset(t.center.x + t.pillW.coerceIn(termMinWpx, termMaxWpx) / 2f + pinOutsidePx + 8f, t.center.y)

internal fun dotForOutput(t: TerminalBox, pinOutsidePx: Float, termMinWpx: Float, termMaxWpx: Float): Offset =
    Offset(t.center.x - t.pillW.coerceIn(termMinWpx, termMaxWpx) / 2f - pinOutsidePx - 8f, t.center.y)

internal fun resolveSourceWith(
    boxes: Map<String, GateBox>,
    chipDefs: Map<String, ChipDef>,
    inLayouts: List<TerminalBox>,
    pinOutsidePx: Float,
    termMinWpx: Float,
    termMaxWpx: Float,
    end: WireEnd,
): Offset? {
    if (end.instanceId.startsWith("__IN_")) {
        val idx = end.instanceId.removePrefix("__IN_").toIntOrNull() ?: return null
        return inLayouts.find { it.idx == idx }?.let { dotForInput(it, pinOutsidePx, termMinWpx, termMaxWpx) }
    }
    val gr = boxes[end.instanceId] ?: return null
    val def = chipDefs[end.instanceId] ?: return null
    return gr.outputPos(end.pinIndex.coerceIn(0, max(0, def.outputCount - 1)), def.outputCount)
}

internal fun resolveSinkWith(
    boxes: Map<String, GateBox>,
    chipDefs: Map<String, ChipDef>,
    outLayouts: List<TerminalBox>,
    pinOutsidePx: Float,
    termMinWpx: Float,
    termMaxWpx: Float,
    end: WireEnd,
): Offset? {
    if (end.instanceId.startsWith("__OUT_")) {
        val idx = end.instanceId.removePrefix("__OUT_").toIntOrNull() ?: return null
        return outLayouts.find { it.idx == idx }?.let { dotForOutput(it, pinOutsidePx, termMinWpx, termMaxWpx) }
    }
    val gr = boxes[end.instanceId] ?: return null
    val def = chipDefs[end.instanceId] ?: return null
    return gr.inputPos(end.pinIndex.coerceIn(0, max(0, def.inputCount - 1)), def.inputCount)
}

// Find the pin endpoint nearest to an absolute canvas position, excluding the drag's origin instance.
// Direction (source vs sink) is resolved later by the ViewModel's createWire auto-orient.
internal fun resolveTargetAt(
    gateBoxes: List<GateBox>,
    chipDefs: Map<String, ChipDef>,
    inputLayouts: List<TerminalBox>,
    outputLayouts: List<TerminalBox>,
    pinOutsidePx: Float,
    termMinWpx: Float,
    termMaxWpx: Float,
    pinHitR: Float,
    termWireDotR: Float,
    pos: Offset,
    excludeInstance: String?,
): WireEnd? {
    var best: WireEnd? = null
    var bestD = Float.MAX_VALUE
    for (box in gateBoxes) {
        if (box.chip.instanceId == excludeInstance) continue
        val def = chipDefs[box.chip.instanceId] ?: continue
        for (j in 0 until def.inputCount) {
            val d = (pos - box.inputPos(j, def.inputCount)).getDistance()
            if (d < pinHitR && d < bestD) { bestD = d; best = WireEnd(box.chip.instanceId, j) }
        }
        for (j in 0 until def.outputCount) {
            val d = (pos - box.outputPos(j, def.outputCount)).getDistance()
            if (d < pinHitR && d < bestD) { bestD = d; best = WireEnd(box.chip.instanceId, j) }
        }
    }
    for (t in inputLayouts) {
        if ("__IN_${t.idx}" == excludeInstance) continue
        val d = (pos - dotForInput(t, pinOutsidePx, termMinWpx, termMaxWpx)).getDistance()
        if (d < termWireDotR && d < bestD) { bestD = d; best = WireEnd("__IN_${t.idx}", 0) }
    }
    for (t in outputLayouts) {
        if ("__OUT_${t.idx}" == excludeInstance) continue
        val d = (pos - dotForOutput(t, pinOutsidePx, termMinWpx, termMaxWpx)).getDistance()
        if (d < termWireDotR && d < bestD) { bestD = d; best = WireEnd("__OUT_${t.idx}", 0) }
    }
    return best
}

/**
 * The wire canvas: draws every wire/output-map/ghost and handles background taps (tap a wire to
 * delete it, tap a second pin to finish wiring, tap empty space to clear selection).
 */
@Composable
internal fun CircuitWireLayer(data: WireLayerData, events: WireLayerEvents, modifier: Modifier = Modifier) {
    val gateBoxesRef = remember { mutableStateOf(data.gateBoxes) }
    val chipDefsRef = remember { mutableStateOf(data.chipDefs) }
    val inputLayoutsRef = remember { mutableStateOf(data.inputLayouts) }
    val outputLayoutsRef = remember { mutableStateOf(data.outputLayouts) }
    val wiresRef = remember { mutableStateOf(data.wires) }
    val outputMapsRef = remember { mutableStateOf(data.outputMaps) }
    val wiringFromRef = remember { mutableStateOf(data.wiringFrom) }

    LaunchedEffect(data.gateBoxes, data.chipDefs, data.inputLayouts, data.outputLayouts, data.wires, data.outputMaps, data.wiringFrom) {
        gateBoxesRef.value = data.gateBoxes
        chipDefsRef.value = data.chipDefs
        inputLayoutsRef.value = data.inputLayouts
        outputLayoutsRef.value = data.outputLayouts
        wiresRef.value = data.wires
        outputMapsRef.value = data.outputMaps
        wiringFromRef.value = data.wiringFrom
    }

    val onCreateWireState by rememberUpdatedState(events.onCreateWire)
    val onCancelWiringState by rememberUpdatedState(events.onCancelWiring)
    val onWireDeleteState by rememberUpdatedState(events.onWireDelete)
    val onOutputMapDeleteState by rememberUpdatedState(events.onOutputMapDelete)
    val onGhostLineState by rememberUpdatedState(events.onGhostLine)
    val onSelectGateState by rememberUpdatedState(events.onSelectGate)

    // Snapshot the layout constants for the gesture scope (they never change mid-gesture).
    val pinHitR = data.pinHitR
    val termWireDotR = data.termWireDotR
    val wireHitThreshold = data.wireHitThreshold
    val termMinWpx = data.termMinWpx
    val termMaxWpx = data.termMaxWpx
    val pinOutsidePx = data.pinOutsidePx

    Canvas(modifier = modifier.pointerInput(Unit) {
        fun resolveSrcLive(end: WireEnd): Offset? = resolveSourceWith(gateBoxesRef.value.associateBy { it.chip.instanceId }, chipDefsRef.value, inputLayoutsRef.value, pinOutsidePx, termMinWpx, termMaxWpx, end)
        fun resolveSnkLive(end: WireEnd): Offset? = resolveSinkWith(gateBoxesRef.value.associateBy { it.chip.instanceId }, chipDefsRef.value, outputLayoutsRef.value, pinOutsidePx, termMinWpx, termMaxWpx, end)
        fun closestWireLive(pos: Offset): Wire? {
            var best: Wire? = null
            var bestD = wireHitThreshold
            for (w in wiresRef.value) {
                val a = resolveSrcLive(w.from) ?: continue
                val b = resolveSnkLive(w.to) ?: continue
                val d = distPointToOrth(pos, a, b)
                if (d < bestD) { bestD = d; best = w }
            }
            return best
        }
        fun closestOMLive(pos: Offset): OutputMapping? {
            var best: OutputMapping? = null
            var bestD = wireHitThreshold
            for (om in outputMapsRef.value) {
                val a = resolveSrcLive(om.from) ?: continue
                val b = outputLayoutsRef.value.find { it.idx == om.outputIndex }?.let { t ->
                    Offset(t.center.x - t.pillW.coerceIn(termMinWpx, termMaxWpx) / 2f - pinOutsidePx - 8f, t.center.y)
                } ?: continue
                val d = distPointToOrth(pos, a, b)
                if (d < bestD) { bestD = d; best = om }
            }
            return best
        }
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                val downPos = down.position
                val cw = closestWireLive(downPos)
                if (cw != null) {
                    onWireDeleteState(cw.id)
                    while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                    continue
                }
                val om = closestOMLive(downPos)
                if (om != null) {
                    onOutputMapDeleteState(om.outputIndex)
                    while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                    continue
                }
                if (wiringFromRef.value != null) {
                    var hitResult: HitInput? = null
                    for (box in gateBoxesRef.value) {
                        val def = try { ChipLibrary.get(box.chip.chipId) } catch (_: Exception) { null } ?: continue
                        for (j in 0 until def.inputCount) {
                            val pp = box.inputPos(j, def.inputCount)
                            if ((downPos - pp).getDistance() < pinHitR) { hitResult = HitInput(WireEnd(box.chip.instanceId, j), pp); break }
                        }
                        if (hitResult != null) break
                    }
                    if (hitResult == null) {
                        for (t in outputLayoutsRef.value) {
                            val dp = Offset(t.center.x - t.pillW.coerceIn(termMinWpx, termMaxWpx) / 2f - pinOutsidePx - 8f, t.center.y)
                            if ((downPos - dp).getDistance() < termWireDotR) { hitResult = HitInput(WireEnd("__OUT_${t.idx}", 0), dp); break }
                        }
                    }
                    if (hitResult == null) {
                        for (box in gateBoxesRef.value) {
                            val def = try { ChipLibrary.get(box.chip.chipId) } catch (_: Exception) { null } ?: continue
                            for (j in 0 until def.outputCount) {
                                val pp = box.outputPos(j, def.outputCount)
                                if ((downPos - pp).getDistance() < pinHitR) { hitResult = HitInput(WireEnd(box.chip.instanceId, j), pp); break }
                            }
                            if (hitResult != null) break
                        }
                    }
                    if (hitResult == null) {
                        for (t in inputLayoutsRef.value) {
                            val dp = Offset(t.center.x + t.pillW.coerceIn(termMinWpx, termMaxWpx) / 2f + pinOutsidePx + 8f, t.center.y)
                            if ((downPos - dp).getDistance() < termWireDotR) { hitResult = HitInput(WireEnd("__IN_${t.idx}", 0), dp); break }
                        }
                    }
                    val hit = hitResult
                    if (hit != null && wiringFromRef.value!!.instanceId != hit.end.instanceId) onCreateWireState(wiringFromRef.value!!, hit.end) else onCancelWiringState()
                    onGhostLineState(null)
                    while (true) { val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull { it.id == down.id } ?: break; if (ch.changedToUpIgnoreConsumed()) break }
                    continue
                }
                // Empty-space tap: clear the current selection.
                onSelectGateState(null)
            }
        }
    }) {
        val boxesById = data.gateBoxes.associateBy { it.chip.instanceId }
        fun resolveSource(end: WireEnd): Offset? = resolveSourceWith(boxesById, data.chipDefs, data.inputLayouts, pinOutsidePx, termMinWpx, termMaxWpx, end)
        fun resolveSink(end: WireEnd): Offset? = resolveSinkWith(boxesById, data.chipDefs, data.outputLayouts, pinOutsidePx, termMinWpx, termMaxWpx, end)
        for (w in data.wires) {
            val a = resolveSource(w.from) ?: continue
            val b = resolveSink(w.to) ?: continue
            val (col, thick) = wireStyleForWidth(w.busWidth, data.isCompact)
            drawOrthWire(a, b, col, thick, false, false)
        }
        for (om in data.outputMaps) {
            val a = resolveSource(om.from) ?: continue
            val b = data.outputLayouts.find { it.idx == om.outputIndex }?.let { dotForOutput(it, pinOutsidePx, termMinWpx, termMaxWpx) } ?: continue
            val srcWidth = try { val g = data.gateBoxes.find { it.chip.instanceId == om.from.instanceId }; g?.let { ChipLibrary.get(it.chip.chipId).outputPinWidth(om.from.pinIndex) } ?: 1 } catch (_: Exception) { 1 }
            val (_, thick) = wireStyleForWidth(srcWidth, data.isCompact)
            drawOrthWire(a, b, Turing.wireBlue, thick, false, false)
        }
        val gStart = data.wiringAnchor ?: data.wiringFrom?.let { resolveSource(it) ?: resolveSink(it) }
        val gEnd = data.dragGhostLineEnd
        if (gStart != null && gEnd != null) {
            var found = false
            for (box in data.gateBoxes) {
                val def = try { ChipLibrary.get(box.chip.chipId) } catch (_: Exception) { null } ?: continue
                for (j in 0 until def.inputCount) if ((gEnd - box.inputPos(j, def.inputCount)).getDistance() < data.pinHitR) { found = true; break }
                if (!found) for (j in 0 until def.outputCount) if ((gEnd - box.outputPos(j, def.outputCount)).getDistance() < data.pinHitR) { found = true; break }
                if (found) break
            }
            if (!found) {
                for (t in data.inputLayouts) if ((gEnd - dotForInput(t, pinOutsidePx, termMinWpx, termMaxWpx)).getDistance() < data.termWireDotR + 6f) { found = true; break }
                if (!found) for (t in data.outputLayouts) if ((gEnd - dotForOutput(t, pinOutsidePx, termMinWpx, termMaxWpx)).getDistance() < data.termWireDotR + 6f) { found = true; break }
            }
            drawOrthWire(gStart, gEnd, if (found) Turing.wireYellow else Turing.ghostBad, 3f, true, true)
        } else if (data.wiringFrom != null) {
            (data.wiringAnchor ?: resolveSource(data.wiringFrom) ?: resolveSink(data.wiringFrom))?.let { drawCircle(Color.Yellow.copy(alpha = 0.28f), if (data.isCompact) 28f else 22f, it) }
        }
    }
}
