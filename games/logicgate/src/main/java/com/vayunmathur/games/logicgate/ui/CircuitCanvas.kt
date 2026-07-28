package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import com.vayunmathur.games.logicgate.data.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ----- Palette: Turing Complete reference (screenshot faithful) -----
internal object Turing {
    // Canvas is slate-blue from screenshot
    val bg = Color(0xFF2B4D68)
    val gridLine = Color(0xFF1E3C56)
    val gridLine2 = Color(0xFF24435E)
    val gridDot = Color(0x0DFFFFFF)

    val headerBg = Color(0xFF2E2B44)
    val headerPink = Color(0xFFE66A7E)

    val leftPanelBg = Color(0xFF1D2A3A)
    val leftPanelCard = Color(0xFF243447)
    val leftPanelCardDeep = Color(0xFF1A2838)

    val iconBarBg = Color(0xFF1C2C3E)
    val iconBg = Color(0xFF22364D)
    val iconActive = Color(0xFF2A4460)

    val bottomBg = Color(0xFF2A2A44)
    val bottomBgDeep = Color(0xFF24243C)

    val inputRed = Color(0xFFC93B3B)
    val inputRedDeep = Color(0xFF9F2E2E)
    val inputBorder = Color(0xFFFF9A9A)

    val gateTeal = Color(0xFF0F7A6E)
    val gateTealDeep = Color(0xFF0C635A)
    val gateStroke = Color(0xFF4BE8C6)
    val gateLabel = Color.White

    val bitGreen = Color(0xFF2ECC71)
    val bitRed = Color(0xFFE74C4C)
    val busOrange = Color(0xFFFFA53D)
    val busBlue = Color(0xFF4FC3FF)
    val busBlueDeep = Color(0xFF8AD8FF)

    val wireThin = Color(0xFF3DD68A)
    val wireOrange = Color(0xFFFFA53D)
    val wireBlue = Color(0xFF4EC8FF)
    val wireYellow = Color(0xFFFDE68A)

    val ghostOk = Color(0xFFFFFF00)
    val ghostBad = Color(0x66FFFFFF)
    val pinOut = Color(0xFFA7F3D0)
    val pinIn = Color.White
    val orangeLabel = Color(0xFFF0A040)
    val rightTabOn = Color(0xFF3D455C)
    val rightTabOff = Color(0xFF1E2636)
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
}

data class TerminalBox(val idx: Int, val center: Offset, val name: String, val isInput: Boolean, val pillW: Float)
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
    val termWireDotR = with(density) { 26.dp.toPx() }
    val wireHitThreshold = with(density) { 34.dp.toPx() }

    fun gateSizeFor(def: ChipDef): Pair<Float, Float> {
        // Match screenshot small rects: 72x28 for 3-pin etc
        val maxPins = max(def.inputCount, def.outputCount)
        val wDp = when {
            maxPins > 8 -> 124.dp
            maxPins > 5 -> 98.dp
            maxPins > 3 -> 80.dp
            else -> 68.dp
        }
        val hDp = when {
            maxPins <= 1 -> 30.dp
            maxPins == 2 -> 38.dp
            maxPins == 3 -> 46.dp
            maxPins == 4 -> 58.dp
            else -> (13.dp * maxPins + 16.dp).coerceAtLeast(48.dp)
        }
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

    fun dotForInput(t: TerminalBox): Offset = Offset(t.center.x + 42f, t.center.y)
    fun dotForOutput(t: TerminalBox): Offset = Offset(t.center.x - 42f, t.center.y)

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

    val gateBoxesRef = remember { mutableStateOf(gateBoxes) }
    val rectByIdRef = remember { mutableStateOf(rectById) }
    val chipDefsRef = remember { mutableStateOf(chipDefs) }
    val inputLayoutsRef = remember { mutableStateOf(inputLayouts) }
    val outputLayoutsRef = remember { mutableStateOf(outputLayouts) }
    val wiresRef = remember { mutableStateOf(wires) }
    val outputMapsRef = remember { mutableStateOf(outputMaps) }
    val wiringFromRef = remember { mutableStateOf(wiringFrom) }

    LaunchedEffect(gateBoxes, rectById, chipDefs, inputLayouts, outputLayouts, wires, outputMaps, canvasSizePx, wiringFrom, dragGhostLineEnd) {
        gateBoxesRef.value = gateBoxes
        rectByIdRef.value = rectById
        chipDefsRef.value = chipDefs
        inputLayoutsRef.value = inputLayouts
        outputLayoutsRef.value = outputLayouts
        wiresRef.value = wires
        outputMapsRef.value = outputMaps
        wiringFromRef.value = wiringFrom
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Turing.bg)
            .onSizeChanged { canvasSizePx = Size(it.width.toFloat(), it.height.toFloat()) }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    fun resolveSrcLive(end: WireEnd): Offset? = resolveSourceWith(rectByIdRef.value, inputLayoutsRef.value, end)
                    fun resolveSnkLive(end: WireEnd): Offset? = resolveSinkWith(rectByIdRef.value, outputLayoutsRef.value, end)
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
                            val b = outputLayoutsRef.value.find { it.idx == om.outputIndex }?.let { dotForOutput(it) } ?: continue
                            val d = distPointToOrth(pos, a, b)
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
            // grid - screenshot has big squares
            val gridStep = 84f
            if (cs.width > 1f && cs.height > 1f) {
                var x = 0f
                while (x <= cs.width) {
                    drawLine(Turing.gridLine, Offset(x, 0f), Offset(x, cs.height), strokeWidth = 1.2f)
                    x += gridStep
                }
                var y = 0f
                while (y <= cs.height) {
                    drawLine(Turing.gridLine, Offset(0f, y), Offset(cs.width, y), strokeWidth = 1.2f)
                    y += gridStep
                }
                // subtle dot at intersections
                var gx = 0f
                while (gx < cs.width) {
                    var gy = 0f
                    while (gy < cs.height) {
                        drawCircle(Turing.gridDot, 1.1f, Offset(gx, gy))
                        gy += gridStep
                    }
                    gx += gridStep
                }
            }
            // wires - orthogonal like Turing Complete
            for (w in wires) {
                val a = resolveSource(w.from) ?: continue
                val b = resolveSink(w.to) ?: continue
                val (col, thick) = wireStyleForWidth(w.busWidth)
                drawOrthWire(a, b, col, thick, isGhost = false, dash = false)
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
                drawOrthWire(a, b, Turing.wireBlue, thick, false, false)
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
                drawOrthWire(gStart, gEnd, if (isOverInput) Turing.ghostOk else Turing.ghostBad, 3f, true, true)
            } else if (wiringFrom != null) {
                resolveSource(wiringFrom)?.let { drawCircle(Color.Yellow.copy(alpha = 0.28f), 22f, it) }
            }
        }

        // Terminals
        inputLayouts.forEach { t ->
            TuringBigTerminal(
                box = t,
                isInput = true,
                decimal = inputValues[t.idx],
                inputWidth = try { level.inputWidth(t.idx) } catch (_: Exception) { 1 },
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
                            if ((pos - pp).getDistance() < pinHitR) return@TuringBigTerminal HitInput(WireEnd(box.chip.instanceId, j), pp)
                        }
                    }
                    for (ot in outLayouts) if ((pos - dotForOutput(ot)).getDistance() < termWireDotR) return@TuringBigTerminal HitInput(WireEnd("__OUT_${ot.idx}", 0), dotForOutput(ot))
                    null
                }
            )
        }
        outputLayouts.forEach { t ->
            TuringBigTerminal(
                box = t,
                isInput = false,
                decimal = outputValues[t.idx] ?: desiredOutputValues[t.idx],
                inputWidth = try { level.outputWidth(t.idx) } catch (_: Exception) { 1 },
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
                            if ((pos - pp).getDistance() < pinHitR) return@TuringBigTerminal HitInput(WireEnd(box.chip.instanceId, j), pp)
                        }
                    }
                    for (ot in outLayouts) if ((pos - dotForOutput(ot)).getDistance() < termWireDotR) return@TuringBigTerminal HitInput(WireEnd("__OUT_${ot.idx}", 0), dotForOutput(ot))
                    null
                }
            )
        }

        // Gates layer
        gateBoxes.forEach { gBox ->
            TuringGate(
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
                pinHitR = pinHitR,
                hitInputLive = { pos ->
                    val boxes = gateBoxesRef.value
                    val cDefs = chipDefsRef.value
                    val outLayouts = outputLayoutsRef.value
                    for (box in boxes) {
                        val def = cDefs[box.chip.instanceId] ?: continue
                        for (j in 0 until def.inputCount) {
                            val pp = box.inputPos(j, def.inputCount)
                            if ((pos - pp).getDistance() < pinHitR) return@TuringGate HitInput(WireEnd(box.chip.instanceId, j), pp)
                        }
                    }
                    for (ot in outLayouts) if ((pos - dotForOutput(ot)).getDistance() < termWireDotR) return@TuringGate HitInput(WireEnd("__OUT_${ot.idx}", 0), dotForOutput(ot))
                    null
                }
            )
        }
    }
}

@Composable
private fun TuringGate(
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
    pinHitR: Float,
    hitInputLive: (Offset) -> HitInput?,
) {
    val def = chipDef ?: return
    val id = gateBox.chip.instanceId
    var localPos by remember(id) { mutableStateOf(Offset(gateBox.left, gateBox.top)) }
    var dragging by remember(id) { mutableStateOf(false) }
    LaunchedEffect(gateBox.left, gateBox.top) {
        if (!dragging) localPos = Offset(gateBox.left, gateBox.top)
    }
    val w = gateBox.w
    val h = gateBox.h
    Box(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset(localPos.x.toInt(), localPos.y.toInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .clip(RoundedCornerShape(5.dp))
            .background(Turing.gateTeal)
            .border(1.4.dp, if (dragging) Color.White else Turing.gateStroke.copy(alpha = 0.78f), RoundedCornerShape(5.dp))
            .pointerInput(id) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
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
                                if (!longHandled && total.getDistance() > 4f) {
                                    val clamped = clampGate(start + total, w, h, canvasSize, 12.dp, density)
                                    localPos = clamped
                                    onMoveFinished(id, clamped.x, clamped.y)
                                }
                                break
                            }
                            total = ch.position - down.position
                            val elapsed = System.currentTimeMillis() - downTime
                            if (!longHandled && elapsed > 520 && total.getDistance() < 12f) {
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
        Box(modifier = Modifier.fillMaxSize()) {
            // top subtle highlight and bottom label bar like screenshot
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawRoundRect(Turing.gateTealDeep, Offset(0f, size.height * 0.58f), Size(size.width, size.height * 0.42f), CornerRadius(0f, 0f))
                drawRoundRect(Color.White.copy(alpha = 0.07f), Offset(0f, 0f), Size(size.width, 9f), CornerRadius(5f, 5f))
            }
            // width badge top
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-7).dp)
                    .size(18.dp, 12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF103D4A))
                    .border(0.7.dp, Turing.gateStroke.copy(alpha = 0.6f), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Text(text = "${def.dominantBusWidth()}", fontSize = 7.sp, color = Turing.gateStroke)
            }
            // name centered
            androidx.compose.material3.Text(
                text = def.displayName.take(6).uppercase(),
                fontSize = if (def.inputs.size > 4) 7.sp else 8.5.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            // nand cost tiny top end
            androidx.compose.material3.Text(
                text = "${def.nandCost}",
                fontSize = 6.sp,
                color = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 3.dp, top = 2.dp)
            )
            // Inputs
            for (j in 0 until def.inputCount) {
                val lp = gateBox.inputPosLocal(j, def.inputCount)
                val isHover = ghostEnd?.let { (localPos + lp - it).getDistance() < pinHitR } ?: false
                Box(
                    modifier = Modifier
                        .offset { androidx.compose.ui.unit.IntOffset((lp.x - 10f).toInt(), (lp.y - 10f).toInt()) }
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (isHover) Color.Yellow else Color.Transparent)
                        .border(0.7.dp, if (isHover) Color.Yellow else Color.White.copy(alpha = 0.14f), CircleShape)
                        .pointerInput(id, j) {
                            awaitPointerEventScope {
                                while (true) {
                                    val d = awaitFirstDown(requireUnconsumed = false)
                                    if ((d.position - Offset(10f, 10f)).getDistance() > pinHitR) {
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val ch = ev.changes.firstOrNull { it.id == d.id } ?: break
                                            if (ch.changedToUpIgnoreConsumed()) break
                                        }
                                        continue
                                    }
                                    if (wiringFrom != null && wiringFrom.instanceId != id) {
                                        onCompleteWiring(wiringFrom, WireEnd(id, j))
                                        onGhost(null)
                                    } else onCancel()
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
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(if (isHover) Color.Yellow else Color.White.copy(alpha = 0.9f)))
                }
            }
            // Outputs
            for (j in 0 until def.outputCount) {
                val lp = gateBox.outputPosLocal(j, def.outputCount)
                val isSrc = wiringFrom?.instanceId == id && wiringFrom.pinIndex == j
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
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (ch.changedToUpIgnoreConsumed()) {
                                            val hit = hitInputLive(cur)
                                            if (hit != null && hit.end.instanceId != id) onCompleteWiring(WireEnd(id, j), hit.end) else onCancel()
                                            onGhost(null)
                                            break
                                        }
                                        val total = ch.position - down.position
                                        cur = pinAbs + total
                                        onGhost(cur)
                                        ch.consume()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSrc) Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(Color.Yellow.copy(alpha = 0.18f)))
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(if (isSrc) Color.Yellow else Turing.pinOut).border(1.dp, Color.Black.copy(alpha = 0.35f), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun TuringBigTerminal(
    box: TerminalBox,
    isInput: Boolean,
    decimal: Int?,
    inputWidth: Int,
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
    pinHitR: Float,
    termWireDotR: Float,
    hitInputLive: (Offset) -> HitInput?,
) {
    var center by remember(box.idx, box.center) { mutableStateOf(box.center) }
    var dragging by remember(box.idx) { mutableStateOf(false) }
    LaunchedEffect(box.center) { if (!dragging) center = box.center }
    val radius = 44f
    val diameter = radius * 2f
    val diamDp = with(density) { diameter.toDp() }
    val isWiringSrc = wiringFrom?.instanceId == "__${if (isInput) "IN" else "OUT"}_${box.idx}"
    Box(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset((center.x - radius).toInt(), (center.y - radius).toInt()) }
            .size(diamDp)
            .clip(CircleShape)
            .background(Turing.inputRed)
            .border(if (isWiringSrc) 2.2.dp else 1.3.dp, if (isWiringSrc || ghostEnd?.let { (it - center).getDistance() < 48f } == true) Color.Yellow else Turing.inputBorder, CircleShape)
            .pointerInput(box.idx) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val isNearDot = run {
                            val dotLocal = if (isInput) Offset(diameter - 12f, radius) else Offset(12f, radius)
                            (down.position - dotLocal).getDistance() < termWireDotR
                        }
                        if (isNearDot) {
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
        // width badge like screenshot: small blue rectangle at top
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-3).dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF0E2F49))
                .border(0.8.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(text = "$inputWidth", fontSize = 7.sp, color = Color.White)
        }
        // content: value big, name small
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Text(text = decimal?.toString() ?: box.name.take(3), fontSize = 11.sp, color = Color.White, maxLines = 1)
            androidx.compose.material3.Text(text = box.name.take(10), fontSize = 7.sp, color = Color.White.copy(alpha = 0.92f), maxLines = 1)
        }
        // wire dot at edge
        val dotOffset = if (isInput) Modifier.align(Alignment.CenterEnd).offset(x = (-5).dp) else Modifier.align(Alignment.CenterStart).offset(x = 5.dp)
        Box(
            modifier = Modifier
                .then(dotOffset)
                .size(14.dp)
                .clip(CircleShape)
                .background(if (isWiringSrc) Color.Yellow else if (isInput) Color(0xFF38BDF8) else Color(0xFFF87171))
                .border(1.dp, Color.Black.copy(alpha = 0.45f), CircleShape)
                .pointerInput(box.idx, isInput) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val dotAbs = if (isInput) center + Offset(radius - 9f, 0f) else center + Offset(-radius + 9f, 0f)
                            if (isInput) {
                                onStartWiring(WireEnd("__IN_${box.idx}", 0))
                                onGhost(dotAbs)
                                var cur = dotAbs
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (ch.changedToUpIgnoreConsumed()) {
                                        val hit = hitInputLive(cur)
                                        if (hit != null) onCompleteWiring(WireEnd("__IN_${box.idx}", 0), hit.end) else onCancel()
                                        onGhost(null)
                                        break
                                    }
                                    val total = ch.position - down.position
                                    cur = dotAbs + total
                                    onGhost(cur)
                                    ch.consume()
                                }
                            } else {
                                if (wiringFrom != null) {
                                    onCompleteWiring(wiringFrom, WireEnd("__OUT_${box.idx}", 0))
                                    onGhost(null)
                                } else onCancel()
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

private fun wireStyleForWidth(busWidth: Int): Pair<Color, Float> = when {
    busWidth >= 8 -> Turing.wireBlue to 5.2f
    busWidth >= 2 -> Turing.wireOrange to 3.8f
    else -> Turing.wireThin to 2.6f
}

private fun DrawScope.drawOrthWire(from: Offset, to: Offset, color: Color, thickPx: Float, isGhost: Boolean, dash: Boolean) {
    // Screenshot orthogonal: sharp 90 deg with small junction dots and tiny offset
    // Path: from.x -> midX -> to.x, with vertical segment at midX
    // Compute stub lengths
    val dx = to.x - from.x
    val stub = 18f
    val midX = if (dx > 0) from.x + stub + 28f else from.x + stub
    // Alternate: use 2-bend path if vertical distance large, else straight
    val path = Path().apply {
        moveTo(from.x, from.y)
        // small horizontal out
        lineTo(from.x + stub, from.y)
        // if destination y differs significantly, go vertical then horizontal
        if (abs(to.y - from.y) > 6f) {
            // choose intermediate X clamped
            val ix = if (abs(dx) < 50f) (from.x + to.x) / 2f else from.x + 32f + (dx * 0.15f).coerceIn(0f, 80f)
            lineTo(ix, from.y)
            lineTo(ix, to.y)
            lineTo(to.x - 6f, to.y)
        } else {
            lineTo(to.x - 6f, to.y)
        }
        lineTo(to.x, to.y)
    }
    if (!isGhost) {
        // glow underneath like screenshot slightly thicker darker
        drawPath(path, color.copy(alpha = 0.18f), style = Stroke(width = thickPx + 4.5f))
    }
    if (dash) {
        drawPath(path, color.copy(alpha = 0.9f), style = Stroke(width = thickPx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f)))
    } else {
        drawPath(path, color, style = Stroke(width = thickPx))
    }
    // junction dots at bends (orange/blue small circles like screenshot)
    drawCircle(color, thickPx * 0.55f + 1.2f, to)
    drawCircle(Color.White.copy(alpha = 0.9f), 1.8f, to)
    // intermediate junction dot if we had a bend
    if (abs(to.y - from.y) > 18f) {
        val ix = if (abs(dx) < 50f) (from.x + to.x) / 2f else from.x + 32f + (dx * 0.15f).coerceIn(0f, 80f)
        drawCircle(color, 3.2f, Offset(ix, from.y))
        drawCircle(color, 3.2f, Offset(ix, to.y))
        drawCircle(Color.White.copy(alpha = 0.7f), 1f, Offset(ix, from.y))
        drawCircle(Color.White.copy(alpha = 0.7f), 1f, Offset(ix, to.y))
    }
}

private fun distPointToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ap = p - a; val ab = b - a; val ab2 = ab.x * ab.x + ab.y * ab.y
    if (ab2 == 0f) return (p - a).getDistance()
    var t = (ap.x * ab.x + ap.y * ab.y) / ab2; t = t.coerceIn(0f, 1f)
    val proj = Offset(a.x + ab.x * t, a.y + ab.y * t)
    return (p - proj).getDistance()
}
private fun distPointToOrth(p: Offset, from: Offset, to: Offset): Float {
    // approximate distance to our orthogonal polyline: use 3 segments
    val dx = to.x - from.x
    val stub = 18f
    val ix = if (abs(dx) < 50f) (from.x + to.x) / 2f else from.x + 32f + (dx * 0.15f).coerceIn(0f, 80f)
    val p1 = from
    val p2 = Offset(from.x + stub, from.y)
    val p3 = Offset(ix, from.y)
    val p4 = Offset(ix, to.y)
    val p5 = Offset(to.x, to.y)
    var best = distPointToSegment(p, p1, p2)
    best = min(best, distPointToSegment(p, p2, p3))
    best = min(best, distPointToSegment(p, p3, p4))
    best = min(best, distPointToSegment(p, p4, p5))
    return best
}
private fun distPointToBezier(p: Offset, from: Offset, to: Offset): Float = distPointToOrth(p, from, to)

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitFirstDown(requireUnconsumed: Boolean = true): androidx.compose.ui.input.pointer.PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
            val down = event.changes.firstOrNull { if (requireUnconsumed) !it.isConsumed else true } ?: continue; return down
        }
    }
}
