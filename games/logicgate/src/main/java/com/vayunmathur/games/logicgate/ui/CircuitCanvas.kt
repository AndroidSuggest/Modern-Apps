package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.data.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ----- Palette: Turing Complete mobile -----
private object Turing {
    val bg = Color(0xFF111E2E)          // canvas deep
    val grid = Color(0xFF1E344A)        // grid line
    val gridDot = Color(0x14FFFFFF)
    val inputRed = Color(0xFFD44A4A)    // big I/O circles
    val inputBorder = Color(0xFFFF8A8A)
    val outputRed = Color(0xFFD44A4A)
    val outputBorder = Color(0xFFFF8A8A)
    val gateBgDefault = Color(0xFF0F6D62)
    val gateStroke = Color(0xFF46E6CF)
    val bit = Color(0xFF7EE8C0)
    val bus4 = Color(0xFFFFA231)
    val bus8 = Color(0xFF60C0FF)
    val ghostOk = Color(0xFFFFFF00)
    val ghostBad = Color(0x66FFFFFF)
    val pinOut = Color(0xFFA7F3D0)
    val pinIn = Color.White
}

data class GateBox(val chip: PlacedChip, val left: Float, val top: Float, val w: Float, val h: Float) {
    fun inputPos(i: Int, count: Int): Offset {
        if (count <= 1) return Offset(left, top + h / 2f)
        val gap = h / (count + 1)
        return Offset(left, top + gap * (i + 1))
    }
    fun outputPos(i: Int, count: Int): Offset {
        if (count <= 1) return Offset(left + w, top + h / 2f)
        val gap = h / (count + 1)
        return Offset(left + w, top + gap * (i + 1))
    }
    fun inputPosLocal(i: Int, count: Int): Offset {
        if (count <= 1) return Offset(0f, h / 2f)
        val gap = h / (count + 1)
        return Offset(0f, gap * (i + 1))
    }
    fun outputPosLocal(i: Int, count: Int): Offset {
        if (count <= 1) return Offset(w, h / 2f)
        val gap = h / (count + 1)
        return Offset(w, gap * (i + 1))
    }
    fun bodyContains(p: Offset): Boolean = p.x in left..left + w && p.y in top..top + h
}

data class TerminalBox(val idx: Int, val center: Offset, val name: String, val isInput: Boolean, val pillW: Float)
data class HitOutput(val end: WireEnd, val pos: Offset)
data class HitInput(val end: WireEnd, val pos: Offset)

@Composable
fun CircuitCanvas(
    level: LevelDef,
    gates: List<PlacedChip>,
    wires: List<Wire>,
    outputMaps: List<OutputMapping>,
    inputPositions: Map<Int, IoPos>,
    outputPositions: Map<Int, IoPos>,
    wiringFrom: WireEnd?,
    onCreateWire: (from: WireEnd, to: WireEnd) -> Unit,
    onStartWiring: (WireEnd) -> Unit,
    onCancelWiring: () -> Unit,
    onGateMove: (id: String, x: Float, y: Float) -> Unit,
    onGateMoveFinished: (id: String, x: Float, y: Float) -> Unit,
    onInputTermMove: (idx: Int, x: Float, y: Float) -> Unit,
    onInputTermMoveFinished: (idx: Int, x: Float, y: Float) -> Unit,
    onOutputTermMove: (idx: Int, x: Float, y: Float) -> Unit,
    onOutputTermMoveFinished: (idx: Int, x: Float, y: Float) -> Unit,
    onGateDelete: (String) -> Unit,
    onWireDelete: (String) -> Unit,
    onOutputMapDelete: (Int) -> Unit,
    dragGhostLineEnd: Offset?,
    onGhostLine: (Offset?) -> Unit,
    inputValues: Map<Int, Int> = emptyMap(),
    desiredOutputValues: Map<Int, Int> = emptyMap(),
    outputValues: Map<Int, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    var canvasSizePx by remember { mutableStateOf(Size.Zero) }

    val pinHitR = with(density) { 28.dp.toPx() }
    val termMoveHitR = with(density) { 56.dp.toPx() }
    val termWireDotR = with(density) { 26.dp.toPx() }
    val wireHitThreshold = with(density) { 34.dp.toPx() }

    fun gateSizeFor(def: ChipDef): Pair<Float, Float> {
        val maxPins = max(def.inputCount, def.outputCount)
        val wDp = when {
            maxPins > 8 -> 128.dp
            maxPins > 4 -> 104.dp
            else -> 88.dp
        }
        val hDp = if (maxPins <= 2) 46.dp else (18.dp * maxPins + 18.dp).coerceAtLeast(46.dp)
        return with(density) { wDp.toPx() to hDp.toPx() }
    }

    val gateBoxes: List<GateBox> = remember(gates) {
        gates.map { g ->
            val def = ChipLibrary.get(g.chipId)
            val (w, h) = gateSizeFor(def)
            GateBox(g, g.x, g.y, w, h)
        }
    }
    val rectById = remember(gateBoxes) { gateBoxes.associateBy { it.chip.instanceId } }
    val chipDefs = remember(gates) { gates.associate { it.instanceId to ChipLibrary.get(it.chipId) } }

    fun pillW(name: String): Float {
        val measured = try {
            val layout = textMeasurer.measure(name, TextStyle(fontSize = 10.sp))
            layout.size.width.toFloat()
        } catch (_: Exception) {
            name.length * 9f
        }
        return (with(density) { 54.dp.toPx() } + measured).coerceIn(with(density) { 68.dp.toPx() }, with(density) { 132.dp.toPx() })
    }

    val inputLayouts: List<TerminalBox> = remember(level.inputs, inputPositions, canvasSizePx) {
        level.inputs.mapIndexed { i, name ->
            val default = defaultInputPos(i, canvasSizePx, pillW(name))
            val p = inputPositions[i]
            val center = if (p != null) Offset(p.x, p.y) else default
            TerminalBox(i, center, name, true, pillW(name))
        }
    }
    val outputLayouts: List<TerminalBox> = remember(level.outputs, outputPositions, canvasSizePx) {
        level.outputs.mapIndexed { i, name ->
            val default = defaultOutputPos(i, canvasSizePx, pillW(name))
            val p = outputPositions[i]
            val center = if (p != null) Offset(p.x, p.y) else default
            TerminalBox(i, center, name, false, pillW(name))
        }
    }

    fun dotForInput(t: TerminalBox): Offset = Offset(t.center.x + t.pillW / 2f - with(density) { 10.dp.toPx() }, t.center.y)
    fun dotForOutput(t: TerminalBox): Offset = Offset(t.center.x - t.pillW / 2f + with(density) { 10.dp.toPx() }, t.center.y)

    fun resolveSourceWith(boxes: Map<String, GateBox>, inLayouts: List<TerminalBox>, end: WireEnd): Offset? {
        if (end.instanceId.startsWith("__IN_")) {
            val idx = end.instanceId.removePrefix("__IN_").toIntOrNull() ?: return null
            return inLayouts.find { it.idx == idx }?.let { dotForInput(it) }
        }
        val gr = boxes[end.instanceId] ?: return null
        val def = chipDefs[end.instanceId] ?: return null
        return gr.outputPos(end.pinIndex.coerceIn(0, max(0, def.outputCount - 1)), def.outputCount)
    }
    fun resolveSinkWith(boxes: Map<String, GateBox>, outLayouts: List<TerminalBox>, end: WireEnd): Offset? {
        if (end.instanceId.startsWith("__OUT_")) {
            val idx = end.instanceId.removePrefix("__OUT_").toIntOrNull() ?: return null
            return outLayouts.find { it.idx == idx }?.let { dotForOutput(it) }
        }
        val gr = boxes[end.instanceId] ?: return null
        val def = chipDefs[end.instanceId] ?: return null
        return gr.inputPos(end.pinIndex.coerceIn(0, max(0, def.inputCount - 1)), def.inputCount)
    }

    fun resolveSource(end: WireEnd): Offset? = resolveSourceWith(rectById, inputLayouts, end)
    fun resolveSink(end: WireEnd): Offset? = resolveSinkWith(rectById, outputLayouts, end)

    // Live refs for background canvas gesture (so pointerInput(Unit) still sees latest)
    val gateBoxesRef = remember { mutableStateOf(gateBoxes) }
    val rectByIdRef = remember { mutableStateOf(rectById) }
    val chipDefsRef = remember { mutableStateOf(chipDefs) }
    val inputLayoutsRef = remember { mutableStateOf(inputLayouts) }
    val outputLayoutsRef = remember { mutableStateOf(outputLayouts) }
    val wiresRef = remember { mutableStateOf(wires) }
    val outputMapsRef = remember { mutableStateOf(outputMaps) }
    val canvasSizeRef = remember { mutableStateOf(canvasSizePx) }
    val wiringFromRef = remember { mutableStateOf(wiringFrom) }
    val dragGhostEndRef = remember { mutableStateOf(dragGhostLineEnd) }

    LaunchedEffect(gateBoxes, rectById, chipDefs, inputLayouts, outputLayouts, wires, outputMaps, canvasSizePx, wiringFrom, dragGhostLineEnd) {
        gateBoxesRef.value = gateBoxes
        rectByIdRef.value = rectById
        chipDefsRef.value = chipDefs
        inputLayoutsRef.value = inputLayouts
        outputLayoutsRef.value = outputLayouts
        wiresRef.value = wires
        outputMapsRef.value = outputMaps
        canvasSizeRef.value = canvasSizePx
        wiringFromRef.value = wiringFrom
        dragGhostEndRef.value = dragGhostLineEnd
    }

    val currentOnCreateWire by rememberUpdatedState(onCreateWire)
    val currentOnStartWiring by rememberUpdatedState(onStartWiring)
    val currentOnCancelWiring by rememberUpdatedState(onCancelWiring)
    val currentOnGateMove by rememberUpdatedState(onGateMove)
    val currentOnGateMoveFinished by rememberUpdatedState(onGateMoveFinished)
    val currentOnInputMove by rememberUpdatedState(onInputTermMove)
    val currentOnInputMoveFinished by rememberUpdatedState(onInputTermMoveFinished)
    val currentOnOutputMove by rememberUpdatedState(onOutputTermMove)
    val currentOnOutputMoveFinished by rememberUpdatedState(onOutputTermMoveFinished)
    val currentOnGateDelete by rememberUpdatedState(onGateDelete)
    val currentOnWireDelete by rememberUpdatedState(onWireDelete)
    val currentOnOutputMapDelete by rememberUpdatedState(onOutputMapDelete)
    val currentOnGhostLine by rememberUpdatedState(onGhostLine)

    // Canvas size tracking Box
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Turing.bg)
            .onSizeChanged { canvasSizePx = Size(it.width.toFloat(), it.height.toFloat()) }
    ) {
        // Bottom layer: grid + wires + ghost
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    fun resolveSrcLive(end: WireEnd): Offset? = resolveSourceWith(rectByIdRef.value, inputLayoutsRef.value, end)
                    fun resolveSnkLive(end: WireEnd): Offset? = resolveSinkWith(rectByIdRef.value, outputLayoutsRef.value, end)
                    fun closestWireLive(pos: Offset): Wire? {
                        var best: Wire? = null; var bestD = wireHitThreshold
                        for (w in wiresRef.value) {
                            val a = resolveSrcLive(w.from) ?: continue; val b = resolveSnkLive(w.to) ?: continue
                            val d = distPointToBezier(pos, a, b)
                            if (d < bestD) { bestD = d; best = w }
                        }
                        return best
                    }
                    fun closestOMLive(pos: Offset): OutputMapping? {
                        var best: OutputMapping? = null; var bestD = wireHitThreshold
                        for (om in outputMapsRef.value) {
                            val a = resolveSrcLive(om.from) ?: continue
                            val b = outputLayoutsRef.value.find { it.idx == om.outputIndex }?.let { dotForOutput(it) } ?: continue
                            val d = distPointToBezier(pos, a, b)
                            if (d < bestD) { bestD = d; best = om }
                        }
                        return best
                    }
                    fun hitInputLive(pos: Offset): HitInput? {
                        val boxes = gateBoxesRef.value
                        val cDefs = chipDefsRef.value
                        val outLayouts = outputLayoutsRef.value
                        for (box in boxes) {
                            val def = cDefs[box.chip.instanceId] ?: continue
                            for (j in 0 until def.inputCount) {
                                val pp = box.inputPos(j, def.inputCount)
                                if ((pos - pp).getDistance() < pinHitR) return HitInput(WireEnd(box.chip.instanceId, j), pp)
                            }
                        }
                        for (t in outLayouts) if ((pos - dotForOutput(t)).getDistance() < termWireDotR) return HitInput(WireEnd("__OUT_${t.idx}", 0), dotForOutput(t))
                        return null
                    }

                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPos = down.position
                            val cw = closestWireLive(downPos)
                            if (cw != null) {
                                currentOnWireDelete(cw.id)
                                // consume up
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (ch.changedToUpIgnoreConsumed()) break
                                }
                                continue
                            }
                            val om = closestOMLive(downPos)
                            if (om != null) {
                                currentOnOutputMapDelete(om.outputIndex)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (ch.changedToUpIgnoreConsumed()) break
                                }
                                continue
                            }
                            // If wiring active, tap empty cancels or completes to input
                            if (wiringFromRef.value != null) {
                                val hitIn = hitInputLive(downPos)
                                if (hitIn != null && wiringFromRef.value!!.instanceId != hitIn.end.instanceId) {
                                    currentOnCreateWire(wiringFromRef.value!!, hitIn.end)
                                } else {
                                    currentOnCancelWiring()
                                }
                                currentOnGhostLine(null)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (ch.changedToUpIgnoreConsumed()) break
                                }
                                continue
                            }
                            // Ghost drag tracking when wiring active (second finger move after start)
                            var cur = downPos
                            var tracking = wiringFromRef.value != null
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) {
                                    if (tracking) {
                                        val hitIn = hitInputLive(cur)
                                        if (hitIn != null && wiringFromRef.value != null && wiringFromRef.value!!.instanceId != hitIn.end.instanceId) {
                                            currentOnCreateWire(wiringFromRef.value!!, hitIn.end)
                                        }
                                        currentOnCancelWiring()
                                        currentOnGhostLine(null)
                                    }
                                    break
                                }
                                cur = ch.position
                                if (wiringFromRef.value != null) {
                                    currentOnGhostLine(cur)
                                    tracking = true
                                }
                            }
                        }
                    }
                }
        ) {
            val cs = canvasSizePx
            // grid
            val gridStep = 68f
            if (cs.width > 1f && cs.height > 1f) {
                var x = 0f
                while (x <= cs.width) {
                    drawLine(Turing.grid, Offset(x, 0f), Offset(x, cs.height), strokeWidth = 1.1f)
                    x += gridStep
                }
                var y = 0f
                while (y <= cs.height) {
                    drawLine(Turing.grid, Offset(0f, y), Offset(cs.width, y), strokeWidth = 1.1f)
                    y += gridStep
                }
                var gx = 0f
                while (gx < cs.width) {
                    var gy = 0f
                    while (gy < cs.height) { drawCircle(Turing.gridDot, 1f, Offset(gx, gy)); gy += gridStep }
                    gx += gridStep
                }
            }
            // wires
            for (w in wires) {
                val a = resolveSource(w.from) ?: continue; val b = resolveSink(w.to) ?: continue
                val (col, thick) = wireStyleForWidth(w.busWidth)
                drawWire(a, b, col, false, thick)
            }
            for (om in outputMaps) {
                val a = resolveSource(om.from) ?: continue
                val b = outputLayouts.find { it.idx == om.outputIndex }?.let { dotForOutput(it) } ?: continue
                val srcWidth = try {
                    val gate = gateBoxes.find { it.chip.instanceId == om.from.instanceId }
                    val def = gate?.let { chipDefs[it.chip.instanceId] }
                    def?.outputPinWidth(om.from.pinIndex) ?: 1
                } catch (_: Exception) { 1 }
                val (_, thick) = wireStyleForWidth(srcWidth)
                drawWire(a, b, Color(0xFFFDE68A), false, thick)
            }
            // ghost
            val gStart = wiringFrom?.let { resolveSource(it) }
            val gEnd = dragGhostLineEnd
            if (gStart != null && gEnd != null) {
                val isOverInput = run {
                    var found = false
                    for (box in gateBoxes) {
                        val def = chipDefs[box.chip.instanceId] ?: continue
                        for (j in 0 until def.inputCount) {
                            val pp = box.inputPos(j, def.inputCount)
                            if ((gEnd - pp).getDistance() < pinHitR) { found = true; break }
                        }
                    }
                    if (!found) for (t in outputLayouts) if ((gEnd - dotForOutput(t)).getDistance() < termWireDotR + 6f) { found = true; break }
                    found
                }
                drawWire(gStart, gEnd, if (isOverInput) Turing.ghostOk else Turing.ghostBad, true, 3f, dash = true)
            } else if (wiringFrom != null) {
                resolveSource(wiringFrom)?.let { drawCircle(Color.Yellow.copy(alpha = 0.28f), 22f, it) }
            }
        }

        // Terminal layers (draggable circles)
        inputLayouts.forEach { t ->
            DraggableTerminal(
                box = t,
                isInput = true,
                decimal = inputValues[t.idx],
                canvasSize = canvasSizePx,
                wiringFrom = wiringFrom,
                ghostEnd = dragGhostLineEnd,
                onMove = { idx, x, y -> currentOnInputMove(idx, x, y) },
                onMoveFinished = { idx, x, y -> currentOnInputMoveFinished(idx, x, y) },
                onStartWiring = { end -> currentOnStartWiring(end); currentOnGhostLine(resolveSource(end)) },
                onCompleteWiring = { from, to -> currentOnCreateWire(from, to); currentOnGhostLine(null) },
                onGhost = { off -> currentOnGhostLine(off) },
                onCancel = { currentOnCancelWiring(); currentOnGhostLine(null) },
                density = density,
                textMeasurer = textMeasurer,
                pinHitR = pinHitR,
                termWireDotR = termWireDotR,
                hitInputLive = { pos ->
                    val boxes = gateBoxesRef.value
                    val cDefs = chipDefsRef.value
                    val outLayouts = outputLayoutsRef.value
                    for (box in boxes) {
                        val def = cDefs[box.chip.instanceId] ?: continue
                        for (j in 0 until def.inputCount) {
                            val pp = box.inputPos(j, def.inputCount)
                            if ((pos - pp).getDistance() < pinHitR) return@DraggableTerminal HitInput(WireEnd(box.chip.instanceId, j), pp)
                        }
                    }
                    for (ot in outLayouts) if ((pos - dotForOutput(ot)).getDistance() < termWireDotR) return@DraggableTerminal HitInput(WireEnd("__OUT_${ot.idx}", 0), dotForOutput(ot))
                    null
                },
                dotForOutputLocal = { tt -> dotForOutput(tt) },
                dotForInputLocal = { tt -> dotForInput(tt) }
            )
        }
        outputLayouts.forEach { t ->
            DraggableTerminal(
                box = t,
                isInput = false,
                decimal = outputValues[t.idx] ?: desiredOutputValues[t.idx],
                canvasSize = canvasSizePx,
                wiringFrom = wiringFrom,
                ghostEnd = dragGhostLineEnd,
                onMove = { idx, x, y -> currentOnOutputMove(idx, x, y) },
                onMoveFinished = { idx, x, y -> currentOnOutputMoveFinished(idx, x, y) },
                onStartWiring = { end -> currentOnStartWiring(end) },
                onCompleteWiring = { from, to -> currentOnCreateWire(from, to); currentOnGhostLine(null) },
                onGhost = { off -> currentOnGhostLine(off) },
                onCancel = { currentOnCancelWiring(); currentOnGhostLine(null) },
                density = density,
                textMeasurer = textMeasurer,
                pinHitR = pinHitR,
                termWireDotR = termWireDotR,
                hitInputLive = { pos ->
                    val boxes = gateBoxesRef.value
                    val cDefs = chipDefsRef.value
                    val outLayouts = outputLayoutsRef.value
                    for (box in boxes) {
                        val def = cDefs[box.chip.instanceId] ?: continue
                        for (j in 0 until def.inputCount) {
                            val pp = box.inputPos(j, def.inputCount)
                            if ((pos - pp).getDistance() < pinHitR) return@DraggableTerminal HitInput(WireEnd(box.chip.instanceId, j), pp)
                        }
                    }
                    for (ot in outLayouts) if ((pos - dotForOutput(ot)).getDistance() < termWireDotR) return@DraggableTerminal HitInput(WireEnd("__OUT_${ot.idx}", 0), dotForOutput(ot))
                    null
                },
                dotForOutputLocal = { tt -> dotForOutput(tt) },
                dotForInputLocal = { tt -> dotForInput(tt) }
            )
        }

        // Gates layer — each gate is its own draggable Box (guaranteed move works, like Alchemist DraggableElement)
        gateBoxes.forEach { gBox ->
            DraggableGate(
                gateBox = gBox,
                chipDef = chipDefs[gBox.chip.instanceId],
                canvasSize = canvasSizePx,
                wiringFrom = wiringFrom,
                ghostEnd = dragGhostLineEnd,
                onMove = { id, x, y -> currentOnGateMove(id, x, y) },
                onMoveFinished = { id, x, y -> currentOnGateMoveFinished(id, x, y) },
                onDelete = { id -> currentOnGateDelete(id) },
                onStartWiring = { end -> currentOnStartWiring(end) },
                onCompleteWiring = { from, to -> currentOnCreateWire(from, to) },
                onGhost = { off -> currentOnGhostLine(off) },
                onCancel = { currentOnCancelWiring(); currentOnGhostLine(null) },
                density = density,
                textMeasurer = textMeasurer,
                pinHitR = pinHitR,
                hitInputLive = { pos ->
                    val boxes = gateBoxesRef.value
                    val cDefs = chipDefsRef.value
                    val outLayouts = outputLayoutsRef.value
                    for (box in boxes) {
                        val def = cDefs[box.chip.instanceId] ?: continue
                        for (j in 0 until def.inputCount) {
                            val pp = box.inputPos(j, def.inputCount)
                            if ((pos - pp).getDistance() < pinHitR) return@DraggableGate HitInput(WireEnd(box.chip.instanceId, j), pp)
                        }
                    }
                    for (ot in outLayouts) if ((pos - dotForOutput(ot)).getDistance() < termWireDotR) return@DraggableGate HitInput(WireEnd("__OUT_${ot.idx}", 0), dotForOutput(ot))
                    null
                },
                gateBoxesRef = gateBoxesRef,
                rectByIdRef = rectByIdRef,
                dotForOutputLocal = { tt -> dotForOutput(tt) },
            )
        }
    }
}

@Composable
private fun DraggableGate(
    gateBox: GateBox,
    chipDef: ChipDef?,
    canvasSize: Size,
    wiringFrom: WireEnd?,
    ghostEnd: Offset?,
    onMove: (String, Float, Float) -> Unit,
    onMoveFinished: (String, Float, Float) -> Unit,
    onDelete: (String) -> Unit,
    onStartWiring: (WireEnd) -> Unit,
    onCompleteWiring: (WireEnd, WireEnd) -> Unit,
    onGhost: (Offset?) -> Unit,
    onCancel: () -> Unit,
    density: androidx.compose.ui.unit.Density,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    pinHitR: Float,
    hitInputLive: (Offset) -> HitInput?,
    gateBoxesRef: MutableState<List<GateBox>>,
    rectByIdRef: MutableState<Map<String, GateBox>>,
    dotForOutputLocal: (TerminalBox) -> Offset,
) {
    val def = chipDef ?: return
    val id = gateBox.chip.instanceId
    var localPos by remember(id) { mutableStateOf(Offset(gateBox.left, gateBox.top)) }
    var dragging by remember(id) { mutableStateOf(false) }
    // Sync from VM when not dragging
    LaunchedEffect(gateBox.left, gateBox.top) {
        if (!dragging) localPos = Offset(gateBox.left, gateBox.top)
    }
    val w = gateBox.w
    val h = gateBox.h

    Box(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset(localPos.x.toInt(), localPos.y.toInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .clip(RoundedCornerShape(8.dp))
            .background(
                when (def.category) {
                    ChipCategory.PRIMITIVE -> Color(0xFF114A52)
                    ChipCategory.FOUNDATION -> Color(0xFF14523E)
                    ChipCategory.ROUTING -> Color(0xFF2A5A35)
                    ChipCategory.BUS -> Color(0xFF342A68)
                    ChipCategory.ARITH -> Color(0xFF6E3514)
                    ChipCategory.MEMORY -> Color(0xFF4A1C6B)
                    ChipCategory.CPU -> Color(0xFF7E163C)
                }
            )
            .border(1.2.dp, if (dragging) Color.White else Turing.gateStroke.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .pointerInput(id) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // If down near a pin, let pin composable handle wiring (child will consume first)
                        // Check if down is very close to a pin — if so skip gate drag to allow wiring
                        var nearPin = false
                        for (j in 0 until def.inputCount) {
                            val lp = gateBox.inputPosLocal(j, def.inputCount)
                            if ((down.position - lp).getDistance() < pinHitR) { nearPin = true; break }
                        }
                        if (!nearPin) {
                            for (j in 0 until def.outputCount) {
                                val lp = gateBox.outputPosLocal(j, def.outputCount)
                                if ((down.position - lp).getDistance() < pinHitR) { nearPin = true; break }
                            }
                        }
                        if (nearPin) {
                            // let child handle; wait for up
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) break
                            }
                            continue
                        }
                        val start = localPos
                        var total = Offset.Zero
                        dragging = true
                        val downTime = System.currentTimeMillis()
                        var longHandled = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.changedToUpIgnoreConsumed()) {
                                if (!longHandled) {
                                    if (total.getDistance() > 4f) {
                                        val clamped = clampGate(start + total, w, h, canvasSize, 12.dp, density)
                                        localPos = clamped
                                        onMoveFinished(id, clamped.x, clamped.y)
                                    }
                                }
                                break
                            }
                            total = ch.position - down.position
                            val elapsed = System.currentTimeMillis() - downTime
                            if (!longHandled && elapsed > 500 && total.getDistance() < 12f) {
                                longHandled = true
                                onDelete(id)
                                break
                            }
                            if (total.getDistance() > 8f) {
                                ch.consume()
                                val newP = start + total
                                val clamped = clampGate(newP, w, h, canvasSize, 12.dp, density)
                                localPos = clamped
                                onMove(id, clamped.x, clamped.y)
                            }
                        }
                        dragging = false
                    }
                }
            }
    ) {
        // Gate label
        Box(modifier = Modifier.fillMaxSize()) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                // subtle top highlight like screenshot
                drawRoundRect(Color.White.copy(alpha = 0.06f), Offset(0f, 0f), Size(w, 14f), CornerRadius(8f, 8f))
            }
            // Name
            androidx.compose.material3.Text(
                text = def.displayName.take(9),
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 4.dp)
            )
            androidx.compose.material3.Text(
                text = "${def.nandCost}N",
                fontSize = 7.sp,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 5.dp, top = 5.dp)
            )
            // Input pins with wiring drag
            for (j in 0 until def.inputCount) {
                val lp = gateBox.inputPosLocal(j, def.inputCount)
                // Hover highlight when ghost near
                val isHover = ghostEnd?.let { ge ->
                    val absPos = localPos + lp
                    (ge - absPos).getDistance() < pinHitR
                } ?: false
                Box(
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset((lp.x - 10f).toInt(), (lp.y - 10f).toInt()) }
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (isHover) Color.Yellow else Color.Transparent)
                        .border(1.dp, if (isHover) Color.Yellow else Color.White.copy(alpha = 0.2f), CircleShape)
                        .pointerInput(id, j) {
                            awaitPointerEventScope {
                                while (true) {
                                    val d = awaitFirstDown(requireUnconsumed = false)
                                    if ((d.position - Offset(10f, 10f)).getDistance() > pinHitR) {
                                        // not on pin center, let parent drag handle
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                                            if (ch.changedToUpIgnoreConsumed()) break
                                        }
                                        continue
                                    }
                                    // Pin tap: if wiring active, completes wire
                                    if (wiringFrom != null && wiringFrom.instanceId != id) {
                                        onCompleteWiring(wiringFrom, WireEnd(id, j))
                                        onGhost(null)
                                    } else {
                                        onCancel()
                                    }
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                                        if (ch.changedToUpIgnoreConsumed()) break
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isHover) Color.Yellow else Color.White))
                }
            }
            // Output pins with wiring drag start
            for (j in 0 until def.outputCount) {
                val lp = gateBox.outputPosLocal(j, def.outputCount)
                val isSrc = wiringFrom?.instanceId == id && wiringFrom.pinIndex == j
                val isHoverOut = ghostEnd == null && isSrc // glow when source active
                Box(
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset((lp.x - 10f).toInt(), (lp.y - 10f).toInt()) }
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .pointerInput(id, j) {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if ((down.position - Offset(10f, 10f)).getDistance() > pinHitR) {
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                            if (ch.changedToUpIgnoreConsumed()) break
                                        }
                                        continue
                                    }
                                    val pinAbs = localPos + lp
                                    onStartWiring(WireEnd(id, j))
                                    onGhost(pinAbs)
                                    var cur = pinAbs
                                    var total = Offset.Zero
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (ch.changedToUpIgnoreConsumed()) {
                                            // try create to input
                                            val hit = hitInputLive(cur)
                                            if (hit != null && hit.end.instanceId != id) {
                                                onCompleteWiring(WireEnd(id, j), hit.end)
                                            } else {
                                                onCancel()
                                            }
                                            onGhost(null)
                                            break
                                        }
                                        total = ch.position - down.position
                                        cur = pinAbs + total
                                        onGhost(cur)
                                        ch.consume()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.size(if (isSrc) 22.dp else 0.dp).clip(CircleShape).background(Color.Yellow.copy(alpha = 0.16f)))
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (isSrc) Color.Yellow else Turing.pinOut).border(1.dp, Color.Black.copy(alpha = 0.3f), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun DraggableTerminal(
    box: TerminalBox,
    isInput: Boolean,
    decimal: Int?,
    canvasSize: Size,
    wiringFrom: WireEnd?,
    ghostEnd: Offset?,
    onMove: (Int, Float, Float) -> Unit,
    onMoveFinished: (Int, Float, Float) -> Unit,
    onStartWiring: (WireEnd) -> Unit,
    onCompleteWiring: (WireEnd, WireEnd) -> Unit,
    onGhost: (Offset?) -> Unit,
    onCancel: () -> Unit,
    density: androidx.compose.ui.unit.Density,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    pinHitR: Float,
    termWireDotR: Float,
    hitInputLive: (Offset) -> HitInput?,
    dotForOutputLocal: (TerminalBox) -> Offset,
    dotForInputLocal: (TerminalBox) -> Offset,
) {
    var center by remember(box.idx, box.center) { mutableStateOf(box.center) }
    var dragging by remember(box.idx) { mutableStateOf(false) }
    LaunchedEffect(box.center) { if (!dragging) center = box.center }

    val radius = 40f
    val diameter = radius * 2f
    val radiusDp = with(density) { radius.toDp() }
    val diamDp = with(density) { diameter.toDp() }

    Box(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset((center.x - radius).toInt(), (center.y - radius).toInt()) }
            .size(diamDp)
            .clip(CircleShape)
            .background(Turing.inputRed)
            .border(if (wiringFrom?.instanceId == "__${if (isInput) "IN" else "OUT"}_${box.idx}") 2.4.dp else 1.4.dp, if (wiringFrom?.instanceId == "__${if (isInput) "IN" else "OUT"}_${box.idx}" || (ghostEnd?.let { (it - center).getDistance() < termWireDotR + 10f } == true)) Color.Yellow else if (isInput) Turing.inputBorder else Turing.outputBorder, CircleShape)
            .pointerInput(box.idx) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val isNearDot = run {
                            val dotLocal = if (isInput) Offset(diameter - 10f, radius) else Offset(10f, radius)
                            (down.position - dotLocal).getDistance() < termWireDotR
                        }
                        if (isNearDot) {
                            // Let dot handler manage (child) — wait for up to avoid double handling
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (ch.changedToUpIgnoreConsumed()) break
                            }
                            continue
                        }
                        val start = center
                        var total = Offset.Zero
                        dragging = true
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.changedToUpIgnoreConsumed()) {
                                val clamped = clampTerm(start + total, diameter, canvasSize, 8f, density)
                                center = clamped
                                onMoveFinished(box.idx, clamped.x, clamped.y)
                                break
                            }
                            total = ch.position - down.position
                            val newC = start + total
                            val clamped = clampTerm(newC, diameter, canvasSize, 8f, density)
                            center = clamped
                            onMove(box.idx, clamped.x, clamped.y)
                            ch.consume()
                        }
                        dragging = false
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Label + decimal like screenshot red circles: value big, name small below
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(text = decimal?.toString() ?: box.name.take(4), fontSize = 10.sp, color = Color.White, maxLines = 1)
            androidx.compose.material3.Text(text = box.name.take(7), fontSize = 7.sp, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
        }
        // Dot at edge
        val dotOffset = if (isInput) Modifier.align(Alignment.CenterEnd).offset(x = (-4).dp) else Modifier.align(Alignment.CenterStart).offset(x = 4.dp)
        Box(
            modifier = Modifier
                .then(dotOffset)
                .size(14.dp)
                .clip(CircleShape)
                .background(if (wiringFrom?.instanceId == "__${if (isInput) "IN" else "OUT"}_${box.idx}") Color.Yellow else if (isInput) Color(0xFF38BDF8) else Color(0xFFF87171))
                .border(1.dp, Color.Black.copy(alpha = 0.4f), CircleShape)
                .pointerInput(box.idx, isInput) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val dotAbs = if (isInput) center + Offset(radius - 6f, 0f) else center + Offset(-radius + 6f, 0f)
                            if (isInput) {
                                // source: start wiring drag
                                onStartWiring(WireEnd("__IN_${box.idx}", 0))
                                onGhost(dotAbs)
                                var cur = dotAbs
                                var total = Offset.Zero
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (ch.changedToUpIgnoreConsumed()) {
                                        val hit = hitInputLive(cur)
                                        if (hit != null) {
                                            onCompleteWiring(WireEnd("__IN_${box.idx}", 0), hit.end)
                                        } else {
                                            onCancel()
                                        }
                                        onGhost(null)
                                        break
                                    }
                                    total = ch.position - down.position
                                    cur = dotAbs + total
                                    onGhost(cur)
                                    ch.consume()
                                }
                            } else {
                                // sink: if wiring active, completes
                                if (wiringFrom != null) {
                                    onCompleteWiring(wiringFrom, WireEnd("__OUT_${box.idx}", 0))
                                    onGhost(null)
                                } else {
                                    onCancel()
                                }
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (ch.changedToUpIgnoreConsumed()) break
                                }
                            }
                        }
                    }
                }
        )
    }
}

private fun wireStyleForWidth(busWidth: Int): Pair<Color, Float> = when (busWidth) {
    4 -> Turing.bus4 to 4.2f
    8 -> Turing.bus8 to 5.8f
    else -> Turing.bit to 2.8f
}

private fun DrawScope.drawWire(from: Offset, to: Offset, color: Color, isSelected: Boolean, thickPx: Float, dash: Boolean = false) {
    val dx = to.x - from.x
    val ctrl = min(180f, max(50f, abs(dx) * 0.58f))
    val path = Path().apply { moveTo(from.x, from.y); cubicTo(from.x + ctrl, from.y, to.x - ctrl, to.y, to.x, to.y) }
    if (isSelected || color.alpha > 0.5f) drawPath(path, color.copy(alpha = 0.14f), style = Stroke(width = thickPx + 5.2f))
    if (dash) drawPath(path, color, style = Stroke(width = thickPx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)))
    else drawPath(path, color, style = Stroke(width = thickPx))
    drawCircle(color, thickPx * 0.68f + 1f, to)
    drawCircle(Color.Black.copy(alpha = 0.55f), 1.2f, to)
}

private fun distPointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ap = p - a; val ab = b - a; val ab2 = ab.x * ab.x + ab.y * ab.y
    if (ab2 == 0f) return (p - a).getDistance()
    var t = (ap.x * ab.x + ap.y * ab.y) / ab2; t = t.coerceIn(0f, 1f)
    val proj = Offset(a.x + ab.x * t, a.y + ab.y * t)
    return (p - proj).getDistance()
}
private fun distPointToBezier(p: Offset, from: Offset, to: Offset): Float {
    val dx = to.x - from.x; val ctrl = min(180f, max(50f, abs(dx) * 0.58f))
    var best = Float.MAX_VALUE; var prev = from; val steps = 24
    for (i in 1..steps) {
        val t = i / steps.toFloat(); val mt = 1 - t
        val x = mt * mt * mt * from.x + 3 * mt * mt * t * (from.x + ctrl) + 3 * mt * t * t * (to.x - ctrl) + t * t * t * to.x
        val y = mt * mt * mt * from.y + 3 * mt * mt * t * from.y + 3 * mt * t * t * to.y
        val cur = Offset(x, y); val d = distPointToSegment(p, prev, cur); if (d < best) best = d; prev = cur; if (best < 2f) return best
    }
    return best
}
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitFirstDown(requireUnconsumed: Boolean = true): androidx.compose.ui.input.pointer.PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
            val down = event.changes.firstOrNull { if (requireUnconsumed) !it.isConsumed else true } ?: continue; return down
        }
    }
}
