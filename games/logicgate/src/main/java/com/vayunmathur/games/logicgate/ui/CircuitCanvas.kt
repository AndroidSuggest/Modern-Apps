package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.data.*
import kotlin.math.max

internal object Turing {
    val bg = Color(0xFF2B4D68)
    val headerBg = Color(0xFF2E2B44)
    val headerPink = Color(0xFFE66A7E)
    val leftPanelBg = Color(0xFF1D2A3A)
    val leftPanelCard = Color(0xFF243447)
    val iconBarBg = Color(0xFF1C2C3E)
    val iconBg = Color(0xFF22364D)
    val bottomBg = Color(0xFF2A2A44)
    val inputRed = Color(0xFFC93B3B)
    val inputBorder = Color(0xFFFF9A9A)
    val gateTeal = Color(0xFF0F7A6E)
    val gateStroke = Color(0xFF4BE8C6)
    val gateBlue = Color(0xFF2C6FB5)
    val gateBlueStroke = Color(0xFF7CB6EC)
    val bitGreen = Color(0xFF2ECC71)
    val bitRed = Color(0xFFE74C4C)
    val busOrange = Color(0xFFFFA53D)
    val busBlue = Color(0xFF4FC3FF)
    val wireThin = Color(0xFF3DD68A)
    val wireOrange = Color(0xFFFFA53D)
    val wireBlue = Color(0xFF4EC8FF)
    val wireYellow = Color(0xFFFDE68A)
    val ghostBad = Color(0x66FFFFFF)
    val pinOut = Color(0xFFA7F3D0)
    val orangeLabel = Color(0xFFF0A040)
    val rightTabOn = Color(0xFF3D455C)
    val rightTabOff = Color(0xFF1E2636)
}

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
    onGateMoveFinished: (id: String, x: Float, y: Float) -> Unit,
    onInputTermMoveFinished: (idx: Int, x: Float, y: Float) -> Unit,
    onOutputTermMoveFinished: (idx: Int, x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
    onGateMove: (id: String, x: Float, y: Float) -> Unit = { _, _, _ -> },
    onInputTermMove: (idx: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
    onOutputTermMove: (idx: Int, x: Float, y: Float) -> Unit = { _, _, _ -> },
    onGateDelete: (String) -> Unit,
    onWireDelete: (String) -> Unit,
    onOutputMapDelete: (Int) -> Unit,
    dragGhostLineEnd: Offset?,
    onGhostLine: (Offset?) -> Unit,
    inputValues: Map<Int, Int> = emptyMap(),
    desiredOutputValues: Map<Int, Int> = emptyMap(),
    outputValues: Map<Int, Int> = emptyMap(),
    isCompact: Boolean = false,
    onToggleInput: (Int) -> Unit = {},
    inputOnMap: Map<Int, Boolean> = emptyMap(),
    inputBitSlices: Map<Int, List<Boolean>> = emptyMap(),
    outputBitSlicesActual: Map<Int, List<Boolean>> = emptyMap(),
    // Reports the current pan/zoom so callers can map screen drops to content coords: content = (screen - offset) / scale
    onViewportChange: (scale: Float, offset: Offset) -> Unit = { _, _ -> },
    selectedGateId: String? = null,
    onSelectGate: (String?) -> Unit = {}
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    var canvasSizePx by remember { mutableStateOf(Size.Zero) }
    // Pinch-to-zoom transform (content space -> screen: p*scale + offset, origin top-left)
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // Explicit start anchor for the wiring ghost — WireEnd(gate, pin) is ambiguous between
    // an input and output pin, so we remember the exact pin the drag started from.
    var wiringAnchor by remember { mutableStateOf<Offset?>(null) }
    // Drag-a-gate-to-the-bottom-to-delete (Alchemist style).
    var gateDragActive by remember { mutableStateOf(false) }
    var gateDragArmed by remember { mutableStateOf(false) }
    val deleteBandPx = with(density) { 72.dp.toPx() }
    fun inDeleteZone(contentCenterY: Float): Boolean {
        val chH = canvasSizePx.height
        if (chH <= 0f) return false
        val screenRelY = contentCenterY * scale + offset.y
        return screenRelY > chH - deleteBandPx
    }

    val pinHitR = with(density) { if (isCompact) 36.dp.toPx() else 28.dp.toPx() }
    val termWireDotR = with(density) { if (isCompact) 32.dp.toPx() else 26.dp.toPx() }
    val wireHitThreshold = with(density) { if (isCompact) 40.dp.toPx() else 34.dp.toPx() }
    val pinOutsideDp: Dp = 14.dp
    val pinOutsidePx = with(density) { pinOutsideDp.toPx() }
    val termMinWpx = with(density) { 78.dp.toPx() }
    val termMaxWpx = with(density) { 124.dp.toPx() }
    val termHpx = with(density) { if (isCompact) 46.dp.toPx() else 42.dp.toPx() } // must match TuringBigTerminal.visualH

    fun gateSizeFor(def: ChipDef): Pair<Float, Float> = gatePlacedSizePx(def, density, textMeasurer)

    val gateBoxes: List<GateBox> = remember(gates, isCompact, pinOutsidePx) {
        gates.map { g ->
            val def = ChipLibrary.get(g.chipId)
            val (w, h) = gateSizeFor(def)
            GateBox(g, g.x, g.y, w, h, pinOut = pinOutsidePx)
        }
    }
    val chipDefs = remember(gates) { gates.associate { it.instanceId to ChipLibrary.get(it.chipId) } }

    fun pillW(display: String): Float {
        val measured = try {
            val layout = textMeasurer.measure(display, TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))
            layout.size.width.toFloat()
        } catch (_: Exception) { display.length * 12f }
        return (with(density) { 28.dp.toPx() } + measured).coerceIn(termMinWpx, termMaxWpx)
    }
    fun visualPillW(raw: Float): Float = raw.coerceIn(termMinWpx, termMaxWpx)

    val inputLayouts: List<TerminalBox> = remember(level.inputs, inputPositions, canvasSizePx) {
        level.inputs.mapIndexed { i, _ ->
            val label = displayInputLabel(level, i)
            val pw = pillW(label)
            val vw = visualPillW(pw)
            val default = defaultInputPos(i, level.inputs.size, canvasSizePx, vw, termHpx)
            val p = inputPositions[i]
            val center = if (p != null) Offset(p.x, p.y) else default
            TerminalBox(i, center, label, true, pw)
        }
    }
    val outputLayouts: List<TerminalBox> = remember(level.outputs, outputPositions, canvasSizePx) {
        level.outputs.mapIndexed { i, _ ->
            val label = displayOutputLabel(level, i)
            val pw = pillW(label)
            val vw = visualPillW(pw)
            val default = defaultOutputPos(i, level.outputs.size, canvasSizePx, vw, termHpx)
            val p = outputPositions[i]
            val center = if (p != null) Offset(p.x, p.y) else default
            TerminalBox(i, center, label, false, pw)
        }
    }

    fun dotForInput(t: TerminalBox): Offset = dotForInput(t, pinOutsidePx, termMinWpx, termMaxWpx)
    fun dotForOutput(t: TerminalBox): Offset = dotForOutput(t, pinOutsidePx, termMinWpx, termMaxWpx)

    // Find the pin endpoint nearest to an absolute canvas position, excluding the drag's origin instance.
    fun resolveTargetAt(pos: Offset, excludeInstance: String?): WireEnd? = resolveTargetAt(
        gateBoxes, chipDefs, inputLayouts, outputLayouts,
        pinOutsidePx, termMinWpx, termMaxWpx, pinHitR, termWireDotR, pos, excludeInstance
    )

    LaunchedEffect(wiringFrom) { if (wiringFrom == null) wiringAnchor = null }
    val onViewportChangeState by rememberUpdatedState(onViewportChange)
    LaunchedEffect(scale, offset) { onViewportChangeState(scale, offset) }

    Box(
        modifier = modifier.fillMaxSize().background(Turing.bg).clipToBounds()
            // Two-finger pinch/pan, intercepted in the Initial pass so single-finger
            // gestures still reach the gates/terminals/wires below.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val p0 = pressed[0]; val p1 = pressed[1]
                            // Only transform once both pointers were already down last frame,
                            // otherwise the just-landed finger produces a bogus first delta.
                            if (p0.previousPressed && p1.previousPressed) {
                                val curDist = (p0.position - p1.position).getDistance()
                                val prevDist = (p0.previousPosition - p1.previousPosition).getDistance()
                                val zoom = if (prevDist > 0.01f) curDist / prevDist else 1f
                                val centroid = (p0.position + p1.position) / 2f
                                val prevCentroid = (p0.previousPosition + p1.previousPosition) / 2f
                                val pan = centroid - prevCentroid
                                var newOffset = offset + pan
                                val contentUnder = (centroid - newOffset) / scale
                                val newScale = (scale * zoom).coerceIn(0.15f, 3.5f)
                                newOffset = centroid - contentUnder * newScale
                                scale = newScale
                                offset = newOffset
                            }
                            pressed.forEach { it.consume() }
                        }
                    }
                }
            }
    ) {
      // Infinite grid drawn in screen space so it fills the whole viewport at any pan/zoom.
      Canvas(modifier = Modifier.fillMaxSize()) {
          val step = (if (isCompact) 72f else 84f) * scale
          if (step >= 6f) {
              val w = size.width; val h = size.height
              val startX = offset.x.mod(step); val startY = offset.y.mod(step)
              val lineCol = Color.White.copy(alpha = 0.08f)
              val sw = if (isCompact) 1.5f else 1.2f
              var x = startX
              while (x <= w) { drawLine(lineCol, Offset(x, 0f), Offset(x, h), strokeWidth = sw); x += step }
              var y = startY
              while (y <= h) { drawLine(lineCol, Offset(0f, y), Offset(w, y), strokeWidth = sw); y += step }
              val dotCol = Color.White.copy(alpha = if (isCompact) 0.12f else 0.07f)
              val dotR = (if (isCompact) 1.6f else 1.1f) * scale.coerceIn(0.5f, 1.5f)
              var gx = startX
              while (gx <= w) { var gy = startY; while (gy <= h) { drawCircle(dotCol, dotR, Offset(gx, gy)); gy += step }; gx += step }
          }
      }
      Box(
        modifier = Modifier.fillMaxSize()
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .onSizeChanged { canvasSizePx = Size(it.width.toFloat(), it.height.toFloat()) }
      ) {
        CircuitWireLayer(
            data = WireLayerData(
                wires = wires, outputMaps = outputMaps,
                gateBoxes = gateBoxes, chipDefs = chipDefs,
                inputLayouts = inputLayouts, outputLayouts = outputLayouts,
                wiringFrom = wiringFrom, wiringAnchor = wiringAnchor,
                dragGhostLineEnd = dragGhostLineEnd,
                pinHitR = pinHitR, termWireDotR = termWireDotR,
                wireHitThreshold = wireHitThreshold,
                termMinWpx = termMinWpx, termMaxWpx = termMaxWpx,
                pinOutsidePx = pinOutsidePx, isCompact = isCompact,
            ),
            events = WireLayerEvents(
                onWireDelete = onWireDelete,
                onOutputMapDelete = onOutputMapDelete,
                onCreateWire = onCreateWire,
                onCancelWiring = onCancelWiring,
                onGhostLine = onGhostLine,
                onSelectGate = onSelectGate,
            ),
            modifier = Modifier.fillMaxSize(),
        )

        inputLayouts.forEach { t ->
            TuringBigTerminal(
                box = t, isInput = true,
                inputWidth = try { level.inputWidth(t.idx) } catch (_: Exception) { 1 },
                canvasSize = canvasSizePx, wiringFrom = wiringFrom, ghostEnd = dragGhostLineEnd,
                onMoveFinished = onInputTermMoveFinished,
                onMove = onInputTermMove,
                onStartWiring = { end -> onStartWiring(end); wiringAnchor = dotForInput(t); onGhostLine(dotForInput(t)) },
                onCompleteWiring = { from, to -> onCreateWire(from, to); onGhostLine(null) },
                onGhost = onGhostLine,
                onCancel = { onCancelWiring(); onGhostLine(null) },
                resolveTargetAt = { pos, excl -> resolveTargetAt(pos, excl) },
                density = density, pinHitR = pinHitR, termWireDotR = termWireDotR, isCompact = isCompact,
                onToggleInput = onToggleInput,
                pinOutsideDp = pinOutsideDp, termMinWpx = termMinWpx, termMaxWpx = termMaxWpx, pinOutsidePx = pinOutsidePx,
                isOn = inputOnMap[t.idx] ?: (inputBitSlices[t.idx]?.firstOrNull() == true)
            )
        }
        outputLayouts.forEach { t ->
            TuringBigTerminal(
                box = t, isInput = false,
                inputWidth = try { level.outputWidth(t.idx) } catch (_: Exception) { 1 },
                canvasSize = canvasSizePx, wiringFrom = wiringFrom, ghostEnd = dragGhostLineEnd,
                onMoveFinished = onOutputTermMoveFinished,
                onMove = onOutputTermMove,
                onStartWiring = { end -> onStartWiring(end); wiringAnchor = dotForOutput(t); onGhostLine(dotForOutput(t)) },
                onCompleteWiring = { from, to -> onCreateWire(from, to); onGhostLine(null) },
                onGhost = onGhostLine,
                onCancel = { onCancelWiring(); onGhostLine(null) },
                resolveTargetAt = { pos, excl -> resolveTargetAt(pos, excl) },
                density = density, pinHitR = pinHitR, termWireDotR = termWireDotR, isCompact = isCompact,
                pinOutsideDp = pinOutsideDp, termMinWpx = termMinWpx, termMaxWpx = termMaxWpx, pinOutsidePx = pinOutsidePx,
                isOn = outputValues[t.idx]?.let { it != 0 } ?: (desiredOutputValues[t.idx]?.let { it != 0 } ?: outputBitSlicesActual[t.idx]?.firstOrNull() == true)
            )
        }

        gateBoxes.forEach { gBox ->
            TuringGate(
                gateBox = gBox, chipDef = chipDefs[gBox.chip.instanceId], canvasSize = canvasSizePx,
                wiringFrom = wiringFrom, wiringAnchor = wiringAnchor, ghostEnd = dragGhostLineEnd,
                isSelected = selectedGateId == gBox.chip.instanceId,
                onSelect = onSelectGate,
                onMoveFinished = onGateMoveFinished,
                onMove = onGateMove,
                onDelete = onGateDelete,
                onStartWiring = onStartWiring,
                onCompleteWiring = onCreateWire,
                onGhost = onGhostLine,
                onCancel = { onCancelWiring(); onGhostLine(null) },
                resolveTargetAt = { pos, excl -> resolveTargetAt(pos, excl) },
                onAnchor = { pos -> wiringAnchor = pos },
                inDeleteZone = { y -> inDeleteZone(y) },
                onDragZone = { active, armed -> gateDragActive = active; gateDragArmed = armed },
                density = density, pinHitR = pinHitR, isCompact = isCompact
            )
        }
      }
      // Delete zone: drag a component here (toward the inventory) to remove it.
      if (gateDragActive) {
          Box(
              modifier = Modifier
                  .align(Alignment.BottomCenter)
                  .fillMaxWidth()
                  .height(with(density) { deleteBandPx.toDp() })
                  .background(if (gateDragArmed) Color(0xE6B91C1C) else Color(0x66B91C1C)),
              contentAlignment = Alignment.Center
          ) {
              androidx.compose.material3.Text(
                  text = if (gateDragArmed) "Release to delete" else "Drag here to delete",
                  color = Color.White,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Bold
              )
          }
      }
    }
}
