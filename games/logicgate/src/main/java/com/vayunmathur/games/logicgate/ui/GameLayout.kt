package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.LevelDef
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.platform.UiState
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import kotlin.math.roundToInt

/** Values the portrait/landscape layouts read; built once per composition in [GameScreen]. */
internal data class GameLayoutData(
    val level: LevelDef,
    val state: UiState,
    val unlockedChips: Set<String>,
    val selectedGroup: ChipGroup?,
    val availableGroups: Set<ChipGroup>,
    val inputVectors: List<List<Boolean>>,
    val selectedIdx: Int,
    val tableRows: List<TableRowUi>,
    val failingSet: Set<Int>,
    val inputDecimals: Map<Int, Int>,
    val inputBitSlices: Map<Int, List<Boolean>>,
    val inputOnMap: Map<Int, Boolean>,
    val desiredDecimals: Map<Int, Int>,
    val desiredBitSlices: Map<Int, List<Boolean>>,
    val actualDecimals: Map<Int, Int>,
    val actualBitSlices: Map<Int, List<Boolean>>,
    val draggingChipId: String?,
    val draggingChipWindowPos: Offset,
    val canvasPosInWindow: Offset,
    val canvasSize: Size,
    val canvasScale: Float,
    val canvasOffset: Offset,
    val isCompact: Boolean,
)

/** Callbacks the layouts fire; thin wrappers over [GameScreen]'s local state and [LogicActions]. */
internal data class GameLayoutEvents(
    val actions: LogicActions,
    val onSelectGroup: (ChipGroup?) -> Unit,
    val onChipDragStart: (String, Offset) -> Unit,
    val onChipDrag: (String, Offset) -> Unit,
    val onChipDropAt: (chipId: String, windowOffset: Offset) -> Unit,
    val onSelectRow: (Int) -> Unit,
    val onToggleInput: (Int) -> Unit,
    val onViewportChange: (Float, Offset) -> Unit,
    val onCanvasPositioned: (Offset, Size) -> Unit,
)

@Composable
internal fun GamePortraitLayout(data: GameLayoutData, events: GameLayoutEvents, modifier: Modifier = Modifier) {
    Column(modifier) {
        // Phone portrait – solid blocks canvas hero
        Box(modifier = Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { coords ->
            events.onCanvasPositioned(coords.positionInWindow(), Size(coords.size.width.toFloat(), coords.size.height.toFloat()))
        }) {
            GameCircuitCanvas(data = data, events = events, isCompact = true, modifier = Modifier.fillMaxSize())
            DragGhostOverlay(
                chipId = data.draggingChipId,
                windowPos = data.draggingChipWindowPos,
                canvasPos = data.canvasPosInWindow,
                canvasSize = data.canvasSize,
                canvasScale = data.canvasScale,
            )
        }
        MobileFilterRow(selected = data.selectedGroup, onSelect = events.onSelectGroup, availableGroups = data.availableGroups, modifier = Modifier.fillMaxWidth())
        MobileInventoryBar(
            allowed = data.level.allowedChipIds, unlockedChips = data.unlockedChips, selectedGroup = data.selectedGroup,
            onChipDragStart = events.onChipDragStart,
            onChipDrag = events.onChipDrag,
            onChipDrop = { chipId, windowOffset -> events.onChipDropAt(chipId, windowOffset) },
            modifier = Modifier.fillMaxWidth()
        )
        MobileTestbench(
            level = data.level,
            tableRows = data.tableRows,
            selectedIdx = data.selectedIdx,
            onSelectRow = events.onSelectRow,
            outputsConnected = data.state.circuit.outputMappings.isNotEmpty(),
            failingSet = data.failingSet,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun GameLandscapeLayout(data: GameLayoutData, events: GameLayoutEvents, modifier: Modifier = Modifier) {
    // Tablet/landscape – keep side panels but still solid blocks
    Column(modifier = modifier.navigationBarsPadding()) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth().background(Turing.bg)) {
            MobileLeftPanel(tick = data.selectedIdx, simSpeed = if (data.selectedIdx == 0) 0 else 20, level = data.level, inputDecimals = data.inputDecimals, inputBitSlices = data.inputBitSlices, desiredDecimals = data.desiredDecimals, desiredBitSlices = data.desiredBitSlices, actualDecimals = data.actualDecimals, modifier = Modifier.width(MobileDimens.leftPanelW).fillMaxHeight())
            MobileMiddleToolbar(onClear = { events.actions.clearCircuit() }, onUndo = { events.actions.undo() }, onRedo = { events.actions.redo() }, canUndo = data.state.canUndo, canRedo = data.state.canRedo, modifier = Modifier.width(MobileDimens.toolbarWidth).fillMaxHeight())
            Box(modifier = Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { coords ->
                events.onCanvasPositioned(coords.positionInWindow(), Size(coords.size.width.toFloat(), coords.size.height.toFloat()))
            }) {
                GameCircuitCanvas(data = data, events = events, isCompact = data.isCompact, modifier = Modifier.fillMaxSize())
                DragGhostOverlay(
                    chipId = data.draggingChipId,
                    windowPos = data.draggingChipWindowPos,
                    canvasPos = data.canvasPosInWindow,
                    canvasSize = data.canvasSize,
                    canvasScale = data.canvasScale,
                )
            }
        }
        MobileFilterRow(selected = data.selectedGroup, onSelect = events.onSelectGroup, availableGroups = data.availableGroups, modifier = Modifier.fillMaxWidth())
        MobileInventoryBar(
            allowed = data.level.allowedChipIds, unlockedChips = data.unlockedChips, selectedGroup = data.selectedGroup,
            onChipDragStart = events.onChipDragStart,
            onChipDrag = events.onChipDrag,
            onChipDrop = { chipId, windowOffset -> events.onChipDropAt(chipId, windowOffset) },
            modifier = Modifier.fillMaxWidth()
        )
        MobileTestbench(
            level = data.level,
            tableRows = data.tableRows,
            selectedIdx = data.selectedIdx,
            onSelectRow = events.onSelectRow,
            outputsConnected = data.state.circuit.outputMappings.isNotEmpty(),
            failingSet = data.failingSet,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** The shared circuit canvas wiring: identical in portrait and landscape, only [isCompact] differs. */
@Composable
private fun GameCircuitCanvas(data: GameLayoutData, events: GameLayoutEvents, isCompact: Boolean, modifier: Modifier = Modifier) {
    val actions = events.actions
    CircuitCanvas(
        level = data.level, gates = data.state.circuit.gates, wires = data.state.circuit.wires, outputMaps = data.state.circuit.outputMappings,
        inputPositions = data.state.circuit.inputPositions, outputPositions = data.state.circuit.outputPositions,
        wiringFrom = data.state.wiringFrom,
        onCreateWire = { f: com.vayunmathur.games.logicgate.data.WireEnd, t: com.vayunmathur.games.logicgate.data.WireEnd -> actions.createWire(f, t) },
        onStartWiring = { end: com.vayunmathur.games.logicgate.data.WireEnd -> actions.startWiring(end) },
        onCancelWiring = { actions.cancelWiring() },
        onGateMoveFinished = { id: String, x: Float, y: Float -> actions.onGateMoveFinished(id, x, y) },
        onInputTermMoveFinished = { idx: Int, x: Float, y: Float -> actions.onInputMoveFinished(idx, x, y) },
        onOutputTermMoveFinished = { idx: Int, x: Float, y: Float -> actions.onOutputMoveFinished(idx, x, y) },
        onGateMove = { id: String, x: Float, y: Float -> actions.onGateMoved(id, x, y) },
        onInputTermMove = { idx: Int, x: Float, y: Float -> actions.onInputMoved(idx, x, y) },
        onOutputTermMove = { idx: Int, x: Float, y: Float -> actions.onOutputMoved(idx, x, y) },
        onGateDelete = { id: String -> actions.removeGate(id) },
        onWireDelete = { id: String -> actions.removeWire(id) },
        onOutputMapDelete = { idx: Int -> actions.removeOutputMapping(idx) },
        dragGhostLineEnd = data.state.dragGhostLineEnd,
        onGhostLine = { off: Offset? -> actions.updateGhostLine(off) },
        inputValues = data.inputDecimals, desiredOutputValues = data.desiredDecimals, outputValues = data.actualDecimals,
        modifier = modifier, isCompact = isCompact,
        onToggleInput = events.onToggleInput,
        inputOnMap = data.inputOnMap,
        inputBitSlices = data.inputBitSlices,
        outputBitSlicesActual = data.actualBitSlices,
        onViewportChange = events.onViewportChange,
        selectedGateId = data.state.selectedGateInstanceId,
        onSelectGate = { id: String? -> actions.selectGate(id) }
    )
}

/** Ghost preview of the dragged chip at real placed size, scaled to the canvas zoom. */
@Composable
private fun DragGhostOverlay(
    chipId: String?,
    windowPos: Offset,
    canvasPos: Offset,
    canvasSize: Size,
    canvasScale: Float,
) {
    val id = chipId ?: return
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val localOffset = Offset(windowPos.x - canvasPos.x, windowPos.y - canvasPos.y)
    val isOver = localOffset.x >= 0f && localOffset.x <= canvasSize.width && localOffset.y >= 0f && localOffset.y <= canvasSize.height
    val def = try { ChipLibrary.get(id) } catch (_: Exception) { null } ?: return
    // Ghost previews the actual placed component (blue rect at real size, scaled to the canvas zoom).
    val (gwPx, ghPx) = gatePlacedSizePx(def, density, textMeasurer)
    val vw = gwPx * canvasScale; val vh = ghPx * canvasScale
    Box(modifier = Modifier.offset {
        androidx.compose.ui.unit.IntOffset((localOffset.x - vw / 2f).roundToInt(), (localOffset.y - vh / 2f).roundToInt())
    }.size(with(density) { vw.toDp() }, with(density) { vh.toDp() }).clip(RoundedCornerShape(8.dp)).background(Turing.gateBlue.copy(alpha = if (isOver) 0.95f else 0.55f)).border(if (isOver) 2.dp else 1.dp, if (isOver) Color(0xFF22C55E) else Turing.gateBlueStroke, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(text = def.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/**
 * Maps a screen drop to content coords (undo pan/zoom), centered under the finger using the real
 * gate size. Returns null when the drop is outside the canvas.
 */
internal fun chipDropContent(
    chipId: String,
    windowOffset: Offset,
    canvasPos: Offset,
    canvasSize: Size,
    canvasOffset: Offset,
    canvasScale: Float,
    density: androidx.compose.ui.unit.Density,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
): Offset? {
    val local = Offset(windowOffset.x - canvasPos.x, windowOffset.y - canvasPos.y)
    val isOver = local.x >= 0f && local.x <= canvasSize.width && local.y >= 0f && local.y <= canvasSize.height
    if (!isOver) return null
    val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
    val (gwPx, ghPx) = if (def != null) gatePlacedSizePx(def, density, textMeasurer) else 0f to 0f
    return Offset((local.x - canvasOffset.x) / canvasScale - gwPx / 2f, (local.y - canvasOffset.y) / canvasScale - ghPx / 2f)
}

@Composable
internal fun WinDialog(
    levelName: String,
    nextLevelId: String?,
    onDismiss: () -> Unit,
    onOpenLevel: (String) -> Unit,
    onBack: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.level_complete_title), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF22C55E)) },
        text = { Text(stringResource(R.string.level_complete_body, levelName), fontSize = 15.sp, color = Color(0xFFB8C6D8)) },
        confirmButton = {
            if (nextLevelId != null) {
                Button(onClick = { onOpenLevel(nextLevelId) }) {
                    Text(stringResource(R.string.next_level), fontWeight = FontWeight.Bold)
                }
            } else {
                Button(onClick = onBack) {
                    Text(stringResource(R.string.back_to_map), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.keep_editing))
            }
        }
    )
}
