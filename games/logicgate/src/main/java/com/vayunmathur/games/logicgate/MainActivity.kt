package com.vayunmathur.games.logicgate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.logicgate.data.ChipCategory
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.CircuitEvaluator
import com.vayunmathur.games.logicgate.data.EvalResult
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.ui.CircuitCanvas
import com.vayunmathur.games.logicgate.ui.LogicGateTheme
import com.vayunmathur.games.logicgate.ui.ProgressionScreen
import com.vayunmathur.games.logicgate.ui.Turing
import com.vayunmathur.games.logicgate.util.AppBackupAgent
import com.vayunmathur.games.logicgate.util.EvalStatus
import com.vayunmathur.games.logicgate.util.LogicViewModel
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LogicGateTheme {
                val vm: LogicViewModel = viewModel()
                Navigation(vm)
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Progression : Route
    @Serializable data class Game(val levelId: String) : Route
    @Serializable data object GameCenter : Route
}

@Composable
fun Navigation(viewModel: LogicViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Progression)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Progression> { ProgressionScreen(backStack, viewModel) }
            entry<Route.Game> { GameScreen(backStack, viewModel, it.levelId) }
            entry<Route.GameCenter> {
                GameCenterScreen(backupAgent = AppBackupAgent(), manager = viewModel.achievementsManager, onBack = { backStack.pop() })
            }
        }
        newAchievement?.let { AchievementNotification(it) { viewModel.dismissAchievement() } }
    }
}

enum class ChipGroup { BIT, WORD, CUSTOM }

private fun groupForCategory(cat: ChipCategory): ChipGroup = when (cat) {
    ChipCategory.PRIMITIVE, ChipCategory.FOUNDATION, ChipCategory.ROUTING -> ChipGroup.BIT
    ChipCategory.BUS, ChipCategory.ARITH -> ChipGroup.WORD
    ChipCategory.MEMORY, ChipCategory.CPU -> ChipGroup.CUSTOM
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(backStack: NavBackStack<Route>, viewModel: LogicViewModel, levelId: String) {
    val level = Levels.get(levelId)
    val uiState by viewModel.uiState.collectAsState()
    val unlocked by viewModel.unlockedChips.collectAsState()
    val completed by viewModel.completedIds.collectAsState()

    LaunchedEffect(levelId) { viewModel.selectLevel(levelId) }
    val isCurrent = uiState.currentLevelId == levelId

    var canvasPosInWindow by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var draggingChipId by remember { mutableStateOf<String?>(null) }
    var draggingChipWindowPos by remember { mutableStateOf(Offset.Zero) }
    var selectedGroup by remember { mutableStateOf<ChipGroup?>(null) }
    val density = LocalDensity.current

    // representative vector
    val totalInBits = level.totalInputBits
    val displayLimit = if (totalInBits <= 10) minOf(64, (1 shl totalInBits)) else 28
    val inputVectors: List<List<Boolean>> = remember(totalInBits, displayLimit) {
        val seed = 1234L
        if (totalInBits <= 10) (0 until (1 shl totalInBits)).take(displayLimit).map { c -> List(totalInBits) { i -> ((c shr i) and 1) == 1 } }
        else {
            val rnd = java.util.Random(seed)
            val vs = mutableListOf<List<Boolean>>()
            vs.add(List(totalInBits) { false })
            vs.add(List(totalInBits) { true })
            vs.add(List(totalInBits) { it % 2 == 0 })
            while (vs.size < displayLimit) vs.add(List(totalInBits) { rnd.nextBoolean() })
            vs
        }
    }
    val selectedIdx = remember(uiState.evalStatus, inputVectors) {
        val failing = (uiState.evalStatus as? EvalStatus.Ok)?.failingRows
        if (failing != null && failing.isNotEmpty()) failing.first() % inputVectors.size else 0
    }
    val flatInSelected = remember(inputVectors, selectedIdx) { inputVectors.getOrNull(selectedIdx) ?: emptyList() }
    val inputDecimals: Map<Int, Int> = remember(flatInSelected, level) {
        level.inputs.indices.associateWith { idx ->
            val off = level.inputBitOffset(idx); val w = level.inputWidth(idx)
            val slice = if (off + w <= flatInSelected.size) flatInSelected.slice(off until off + w) else List(w) { false }
            ChipLibrary.bitsToInt(slice)
        }
    }
    val inputBitSlices: Map<Int, List<Boolean>> = remember(flatInSelected, level) {
        level.inputs.indices.associateWith { idx ->
            val off = level.inputBitOffset(idx); val w = level.inputWidth(idx)
            if (off + w <= flatInSelected.size) flatInSelected.slice(off until off + w) else List(w) { false }
        }
    }
    val targetDef = remember(level.targetChipId) { try { ChipLibrary.get(level.targetChipId) } catch (_: Exception) { null } }
    val desiredFlatOut: List<Boolean> = remember(flatInSelected, targetDef, level) {
        targetDef?.let { it.eval(flatInSelected).take(level.totalOutputBits) } ?: emptyList()
    }
    val desiredDecimals: Map<Int, Int> = remember(desiredFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val w = level.outputWidth(oi)
            val slice = if (off + w <= desiredFlatOut.size) desiredFlatOut.slice(off until off + w) else List(w) { false }
            ChipLibrary.bitsToInt(slice)
        }
    }
    val desiredBitSlices: Map<Int, List<Boolean>> = remember(desiredFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val w = level.outputWidth(oi)
            if (off + w <= desiredFlatOut.size) desiredFlatOut.slice(off until off + w) else List(w) { false }
        }
    }
    val actualEvalRows: List<List<Boolean>>? = remember(uiState.circuit, level) {
        val res = CircuitEvaluator.evaluate(level, uiState.circuit)
        if (res is EvalResult.Success) res.rows else null
    }
    val actualFlatOut: List<Boolean> = remember(actualEvalRows, selectedIdx) { actualEvalRows?.getOrNull(selectedIdx) ?: emptyList() }
    val actualDecimals: Map<Int, Int> = remember(actualFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val w = level.outputWidth(oi)
            val slice = if (off + w <= actualFlatOut.size) actualFlatOut.slice(off until off + w) else List(w) { false }
            ChipLibrary.bitsToInt(slice)
        }
    }
    val actualBitSlices: Map<Int, List<Boolean>> = remember(actualFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val w = level.outputWidth(oi)
            if (off + w <= actualFlatOut.size) actualFlatOut.slice(off until off + w) else List(w) { false }
        }
    }

    if (!isCurrent) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F1E2D)), contentAlignment = Alignment.Center) { Text(stringResource(R.string.loading)) }
        return
    }

    val isWiring = uiState.wiringFrom != null
    val gateCost = uiState.circuit.totalNandCost()
    val delayEst = remember(uiState.circuit) { estimateDelay(uiState.circuit) }
    val statusText = when (val s = uiState.evalStatus) {
        is EvalStatus.Ok -> if (s.isFullyCorrect) "PASS • ${level.unlocksChipId?.let { "Unlocked $it" } ?: "CORRECT"}"
        else "${s.passingRows}/${s.totalRows} FAIL ${s.failingRows.size}"
        is EvalStatus.Error -> s.msg.take(88)
        is EvalStatus.Cycle -> "CYCLE ${s.ids.take(2).joinToString()}"
        else -> "Drag chip • drag terminals • tap output->input to wire • tap wire delete • long-press gate delete"
    }
    val statusColor = when {
        uiState.evalStatus is EvalStatus.Ok && (uiState.evalStatus as EvalStatus.Ok).isFullyCorrect -> Color(0xFF22C55E)
        uiState.evalStatus is EvalStatus.Ok && (uiState.evalStatus as EvalStatus.Ok).passingRows > 0 -> Color(0xFFFBBF24)
        else -> Color(0xFF94A3B8)
    }

    // Full Turing Complete layout
    Column(modifier = Modifier.fillMaxSize().background(Turing.headerBg)) {
        // ----- TOP BAR (matches screenshot) -----
        TuringTopBar(
            levelName = level.displayName.uppercase(),
            gateCount = gateCost,
            delay = delayEst,
            onBack = { backStack.pop() },
            onClear = { viewModel.clearCircuit() },
            statusText = statusText,
            statusColor = statusColor,
            isWiring = isWiring,
            onCancelWiring = { viewModel.cancelWiring() }
        )

        // ----- MIDDLE: left panel + toolbar + canvas + right tabs -----
        Row(modifier = Modifier.weight(1f).fillMaxWidth().background(Turing.bg)) {
            // Left I/O panel (Tick, Sim speed, Inputs, Outputs) - as screenshot left dark card
            TuringLeftPanel(
                tick = selectedIdx,
                simSpeed = if (selectedIdx == 0) 0 else 20,
                level = level,
                inputDecimals = inputDecimals,
                inputBitSlices = inputBitSlices,
                desiredDecimals = desiredDecimals,
                desiredBitSlices = desiredBitSlices,
                actualDecimals = actualDecimals,
                modifier = Modifier.width(132.dp).fillMaxHeight()
            )

            // Vertical icon toolbar (magnify, play, etc)
            TuringMiddleToolbar(
                onClear = { viewModel.clearCircuit() },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                canUndo = uiState.canUndo,
                canRedo = uiState.canRedo,
                modifier = Modifier.width(48.dp).fillMaxHeight()
            )

            // Canvas
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { coords ->
                    canvasPosInWindow = coords.positionInWindow()
                    canvasSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                }
            ) {
                CircuitCanvas(
                    level = level,
                    gates = uiState.circuit.gates,
                    wires = uiState.circuit.wires,
                    outputMaps = uiState.circuit.outputMappings,
                    inputPositions = uiState.circuit.inputPositions,
                    outputPositions = uiState.circuit.outputPositions,
                    wiringFrom = uiState.wiringFrom,
                    onCreateWire = { f, t -> viewModel.createWire(f, t) },
                    onStartWiring = { viewModel.startWiring(it) },
                    onCancelWiring = { viewModel.cancelWiring() },
                    onGateMove = { id, x, y -> viewModel.onGateMoved(id, x, y) },
                    onGateMoveFinished = { id, x, y -> viewModel.onGateMoveFinished(id, x, y) },
                    onInputTermMove = { idx, x, y -> viewModel.onInputMoved(idx, x, y) },
                    onInputTermMoveFinished = { idx, x, y -> viewModel.onInputMoveFinished(idx, x, y) },
                    onOutputTermMove = { idx, x, y -> viewModel.onOutputMoved(idx, x, y) },
                    onOutputTermMoveFinished = { idx, x, y -> viewModel.onOutputMoveFinished(idx, x, y) },
                    onGateDelete = { viewModel.removeGate(it) },
                    onWireDelete = { viewModel.removeWire(it) },
                    onOutputMapDelete = { viewModel.removeOutputMapping(it) },
                    dragGhostLineEnd = uiState.dragGhostLineEnd,
                    onGhostLine = { viewModel.updateGhostLine(it) },
                    inputValues = inputDecimals,
                    desiredOutputValues = desiredDecimals,
                    outputValues = actualDecimals,
                    modifier = Modifier.fillMaxSize()
                )
                // Ghost for inventory drag
                draggingChipId?.let { chipId ->
                    val localOffset = Offset(draggingChipWindowPos.x - canvasPosInWindow.x, draggingChipWindowPos.y - canvasPosInWindow.y)
                    val isOver = localOffset.x >= 0f && localOffset.x <= canvasSize.width && localOffset.y >= 0f && localOffset.y <= canvasSize.height
                    val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
                    val ghostW = 84.dp; val ghostH = 36.dp
                    Box(
                        modifier = Modifier.offset {
                            IntOffset(
                                (localOffset.x - with(density) { ghostW.toPx() } / 2f).roundToInt(),
                                (localOffset.y - with(density) { ghostH.toPx() } / 2f).roundToInt()
                            )
                        }.size(ghostW, ghostH)
                            .background(if (isOver) Color(0xFFD1FAE5).copy(alpha = 0.92f) else Color(0xFF4B5563).copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                            .border(if (isOver) 2.dp else 1.dp, if (isOver) Color(0xFF22C55E) else Color.White.copy(alpha = 0.28f), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) { def?.let { Text(text = it.displayName.take(8), fontSize = 10.sp, color = if (isOver) Color(0xFF14532D) else Color.White) } }
                }
            }

            // Right BIT/WORD/CUSTOM tabs vertical (screenshot right)
            TuringRightTabs(
                selected = selectedGroup,
                onSelect = { selectedGroup = it },
                modifier = Modifier.width(52.dp).fillMaxHeight()
            )
        }

        // Inventory bar (chips) - horizontal, above bottom panel
        TuringInventoryBar(
            allowed = level.allowedChipIds,
            unlockedChips = unlocked,
            selectedGroup = selectedGroup,
            onChipDragStart = { chipId, windowOffset ->
                draggingChipId = chipId
                draggingChipWindowPos = windowOffset
            },
            onChipDrag = { chipId, windowOffset ->
                draggingChipId = chipId
                draggingChipWindowPos = windowOffset
            },
            onChipDrop = { chipId, windowOffset ->
                val local = Offset(windowOffset.x - canvasPosInWindow.x, windowOffset.y - canvasPosInWindow.y)
                val isOver = local.x >= 0f && local.x <= canvasSize.width && local.y >= 0f && local.y <= canvasSize.height
                if (isOver) viewModel.addGateAt(chipId, local.x, local.y)
                draggingChipId = null
                draggingChipWindowPos = Offset.Zero
            },
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1A2332))
        )

        // Bottom testbench matching screenshot
        TuringBottomTestbench(
            level = level,
            selectedIdx = selectedIdx,
            inputDecimals = inputDecimals,
            inputBitSlices = inputBitSlices,
            desiredDecimals = desiredDecimals,
            desiredBitSlices = desiredBitSlices,
            actualDecimals = actualDecimals,
            actualBitSlices = actualBitSlices,
            failingCount = (uiState.evalStatus as? EvalStatus.Ok)?.failingRows?.size ?: 0,
            onToggle = { viewModel.toggleTruthTable() },
            showTable = uiState.showTruthTable,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TuringTopBar(
    levelName: String,
    gateCount: Int,
    delay: Int,
    onBack: () -> Unit,
    onClear: () -> Unit,
    statusText: String,
    statusColor: Color,
    isWiring: Boolean,
    onCancelWiring: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().background(Turing.headerBg)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left icons mimicking screenshot: hamburger, chat, doc, book, hierarchy, chip, envelope, +255, bulb
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Turing.iconBg).clickable { onBack() }, contentAlignment = Alignment.Center) {
                    Text("☰", fontSize = 12.sp, color = Color(0xFF9AA3BB))
                }
                Text("💬", fontSize = 12.sp, color = Color(0xFF8AA0BB))
                Text("📄", fontSize = 11.sp, color = Color(0xFF8AA0BB))
                Text("📖", fontSize = 11.sp, color = Color(0xFF8AA0BB))
                Text("⯀", fontSize = 11.sp, color = Color(0xFF8AA0BB))
                Text("🖥", fontSize = 11.sp, color = Color(0xFF8AA0BB))
                Text("✉", fontSize = 11.sp, color = Color(0xFF8AA0BB))
                Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF3A3A52)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Text("+255", fontSize = 8.sp, color = Color.White)
                }
                Text("💡", fontSize = 11.sp, color = Color(0xFFF5C15A))
            }
            Spacer(modifier = Modifier.weight(1f))
            // Center title pink like screenshot
            Text(
                text = levelName,
                fontSize = 12.sp,
                color = Turing.headerPink,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            // Right Gate / Delay
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Gate: $gateCount", fontSize = 10.sp, color = Color.White)
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF4B5563))) {
                        Text("⌖", fontSize = 7.sp, color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Delay: $delay", fontSize = 10.sp, color = Color.White)
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF4B5563))) {
                        Text("◐", fontSize = 7.sp, color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
        // Second slim status row if needed
        if (isWiring) {
            Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E2A3A)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("Wiring: tap input dot (yellow) or empty to cancel", fontSize = 10.sp, color = Turing.wireYellow)
            }
        } else if (statusText.isNotBlank()) {
            Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E2A3A)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                Text(statusText, fontSize = 9.sp, color = statusColor)
            }
        }
    }
}

@Composable
private fun TuringLeftPanel(
    tick: Int,
    simSpeed: Int,
    level: com.vayunmathur.games.logicgate.data.LevelDef,
    inputDecimals: Map<Int, Int>,
    inputBitSlices: Map<Int, List<Boolean>>,
    desiredDecimals: Map<Int, Int>,
    desiredBitSlices: Map<Int, List<Boolean>>,
    actualDecimals: Map<Int, Int>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(Turing.leftPanelBg).verticalScroll(rememberScrollState()).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Tick / Sim speed card
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Turing.leftPanelCard).padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tick", fontSize = 9.sp, color = Color(0xFF8AA0BB))
                Text("$tick", fontSize = 9.sp, color = Color.White)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Sim speed", fontSize = 9.sp, color = Color(0xFF8AA0BB))
                Text("${simSpeed}hz", fontSize = 9.sp, color = Color.White)
            }
        }
        // Inputs
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Turing.leftPanelCard).padding(8.dp)) {
            Text("Inputs", fontSize = 11.sp, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp))
            level.inputs.forEachIndexed { idx, name ->
                val dec = inputDecimals[idx] ?: 0
                val bits = inputBitSlices[idx] ?: emptyList()
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name, fontSize = 9.sp, color = Color(0xFFB8C6D8))
                    Spacer(modifier = Modifier.height(3.dp))
                    TuringBitDotsRow(bits = bits, dotSize = 6.dp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("$dec", fontSize = 9.sp, color = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            // Outputs inside same card like screenshot
            Text("Outputs", fontSize = 11.sp, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp))
            level.outputs.forEachIndexed { idx, name ->
                val dec = actualDecimals[idx] ?: desiredDecimals[idx] ?: 0
                val bits = desiredBitSlices[idx] ?: emptyList()
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(name, fontSize = 9.sp, color = Color(0xFFB8C6D8))
                    Spacer(modifier = Modifier.height(3.dp))
                    TuringBitDotsRow(bits = bits, dotSize = 6.dp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text("$dec", fontSize = 9.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun TuringMiddleToolbar(
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(Turing.iconBarBg).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ToolbarBtn("⊕", onClick = {})
        ToolbarBtn("⊖", onClick = {})
        Spacer(modifier = Modifier.height(4.dp))
        ToolbarBtn("▶", sub = "${20}kHz", onClick = {})
        ToolbarBtn("↗", onClick = onRedo) // placeholder for step
        ToolbarBtn("↩", onClick = { if (canUndo) onUndo() })
        ToolbarBtn("■", onClick = onClear)
        Box(modifier = Modifier.height(1.dp).fillMaxWidth(0.6f).background(Color(0xFF2A3A50)))
        ToolbarBtn("⬚", onClick = {})
        ToolbarBtn("🗑", onClick = onClear)
        ToolbarBtn("✎", onClick = {})
        ToolbarBtn("◍", onClick = {})
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Turing.iconBg).border(0.6.dp, Color(0xFF2E425C), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            Text("8↔", fontSize = 9.sp, color = Color.White)
        }
    }
}

@Composable
private fun ToolbarBtn(text: String, sub: String? = null, onClick: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Turing.iconBg).border(0.6.dp, Color(0xFF2E425C), RoundedCornerShape(6.dp)).clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(text, fontSize = 12.sp, color = Color(0xFF9AA3BB))
        }
        if (sub != null) {
            Text(sub, fontSize = 7.sp, color = Color(0xFF7A8AA3))
        }
    }
}

@Composable
private fun TuringRightTabs(
    selected: ChipGroup?,
    onSelect: (ChipGroup?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(Color(0xFF11202E)).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Mimic screenshot BIT WORD CUSTOM vertical pills
        listOf<Triple<ChipGroup?, String, String>>(Triple(null, "ALL", "ALL"), Triple(ChipGroup.BIT, "BIT", "BIT"), Triple(ChipGroup.WORD, "WORD", "WORD"), Triple(ChipGroup.CUSTOM, "CUSTOM", "CUSTOM")).forEach { (g, label, _) ->
            val isSel = selected == g
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSel) Turing.rightTabOn else Turing.rightTabOff)
                    .border(0.6.dp, if (isSel) Color(0xFF5A667A) else Color(0xFF2A344A), RoundedCornerShape(6.dp))
                    .clickable { onSelect(g) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = 8.sp, color = if (isSel) Color.White else Color(0xFF8A97AD))
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TuringBottomTestbench(
    level: com.vayunmathur.games.logicgate.data.LevelDef,
    selectedIdx: Int,
    inputDecimals: Map<Int, Int>,
    inputBitSlices: Map<Int, List<Boolean>>,
    desiredDecimals: Map<Int, Int>,
    desiredBitSlices: Map<Int, List<Boolean>>,
    actualDecimals: Map<Int, Int>,
    actualBitSlices: Map<Int, List<Boolean>>,
    failingCount: Int,
    onToggle: () -> Unit,
    showTable: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(Turing.bottomBg).padding(vertical = 6.dp, horizontal = 8.dp)) {
        // header row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E1E32).copy(alpha = 0.9f)).clickable { onToggle() }, contentAlignment = Alignment.Center) {
                Text(if (showTable) "⌄" else "⌃", fontSize = 12.sp, color = Color(0xFF8A8DB0))
            }
            // empty center spacer
            Spacer(modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2E2E4A)), contentAlignment = Alignment.Center) { Text("◫", fontSize = 10.sp, color = Color.White) }
                Box(modifier = Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2E2E4A)), contentAlignment = Alignment.Center) { Text("⛶", fontSize = 12.sp, color = Color.White) }
            }
        }
        if (!showTable) return
        Spacer(modifier = Modifier.height(6.dp))
        // Centered test vector like screenshot: opcode OR with 3 dots, Input1, Input2, Desired, Current
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Try to map first input as opcode if exists
            level.inputs.forEachIndexed { idx, name ->
                val dec = inputDecimals[idx] ?: 0
                val bits = inputBitSlices[idx] ?: emptyList()
                val opMnemonic = if (name.lowercase().contains("opcode") || name.lowercase().contains("op")) decodeOpcode(dec) else ""
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(text = if (idx == 0 && level.inputs.size > 1) "Opcode" else if (level.inputs.size == 1) "Input" else "Input ${idx + 1}", fontSize = 11.sp, color = Turing.orangeLabel, modifier = Modifier.width(96.dp))
                    Text(text = if (opMnemonic.isNotEmpty()) opMnemonic else "$dec", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(36.dp))
                    TuringBitDotsRow(bits = bits, dotSize = 12.dp, spacing = 5.dp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(modifier = Modifier.fillMaxWidth(0.7f).height(1.dp).background(Color(0xFF3A3A52)))
            Spacer(modifier = Modifier.height(4.dp))
            level.outputs.forEachIndexed { idx, name ->
                val decD = desiredDecimals[idx] ?: 0
                val bitsD = desiredBitSlices[idx] ?: emptyList()
                val decA = actualDecimals[idx] ?: 0
                val bitsA = actualBitSlices[idx] ?: emptyList()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                    Text("Desired output", fontSize = 11.sp, color = Turing.orangeLabel, modifier = Modifier.width(96.dp))
                    Text("$decD", fontSize = 11.sp, color = Color.White, modifier = Modifier.width(36.dp))
                    TuringBitDotsRow(bits = bitsD, dotSize = 12.dp, spacing = 5.dp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                    val match = decD == decA
                    Text("Current output", fontSize = 11.sp, color = if (match) Turing.orangeLabel else Color(0xFFFF8A8A), modifier = Modifier.width(96.dp))
                    Text("$decA", fontSize = 11.sp, color = if (match) Color(0xFF8EF0B0) else Color(0xFFFF8A8A), modifier = Modifier.width(36.dp))
                    TuringBitDotsRow(bits = bitsA, dotSize = 12.dp, spacing = 5.dp)
                    if (!match) Text("✗ fail $failingCount", fontSize = 9.sp, color = Color(0xFFFF8A8A), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

private fun decodeOpcode(v: Int): String = when (v and 0b111) {
    0 -> "ADD"; 1 -> "SUB"; 2 -> "AND"; 3 -> "OR"; 4 -> "XOR"; 5 -> "NOT"; 6 -> "INC"; 7 -> "DEC"; else -> "$v"
}

private fun estimateDelay(circuit: com.vayunmathur.games.logicgate.data.Circuit): Int {
    // crude depth via topological longest path from inputs
    val gates = circuit.gates
    if (gates.isEmpty()) return 0
    val idToIdx = gates.mapIndexed { i, g -> g.instanceId to i }.toMap()
    val incoming = mutableMapOf<String, MutableList<String>>()
    gates.forEach { incoming[it.instanceId] = mutableListOf() }
    circuit.wires.forEach { w ->
        val to = w.to.instanceId
        val from = w.from.instanceId
        if (to.startsWith("__OUT_")) return@forEach
        if (from.startsWith("__IN_")) return@forEach
        if (incoming.containsKey(to)) incoming[to]?.add(from)
    }
    val depth = mutableMapOf<String, Int>()
    fun dfs(id: String, visited: MutableSet<String>): Int {
        if (id in depth) return depth[id]!!
        if (id in visited) return 0
        visited.add(id)
        val d = (incoming[id]?.maxOfOrNull { dfs(it, visited) } ?: -1) + 1
        visited.remove(id)
        depth[id] = d
        return d
    }
    var maxD = 0
    gates.forEach { maxD = maxOf(maxD, dfs(it.instanceId, mutableSetOf())) }
    return maxD.coerceAtLeast(0)
}

@Composable
fun BitDot(isOne: Boolean, dotSize: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(modifier = Modifier.size(dotSize).clip(CircleShape).background(if (isOne) Color(0xFF22C55E) else Color(0xFFEF4444)))
}

@Composable
fun BitDotRow(bits: List<Boolean>, dotSize: androidx.compose.ui.unit.Dp = 7.dp, maxDots: Int = 8) {
    val display = if (bits.size > 1) bits.reversed() else bits
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        display.take(maxDots).forEach { b -> BitDot(b, dotSize) }
        if (display.size > maxDots) Text(text = "+${display.size - maxDots}", fontSize = 8.sp, color = Color(0xFF6B7D96))
    }
}

@Composable
fun TuringBitDotsRow(bits: List<Boolean>, dotSize: androidx.compose.ui.unit.Dp = 8.dp, spacing: androidx.compose.ui.unit.Dp = 3.dp, maxDots: Int = 8) {
    val display = if (bits.size > 1) bits.reversed() else bits
    Row(horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
        display.take(maxDots).forEach { b ->
            Box(modifier = Modifier.size(dotSize).clip(CircleShape).background(if (b) Turing.bitGreen else Turing.bitRed).border(0.6.dp, Color.Black.copy(alpha = 0.3f), CircleShape))
        }
        if (display.size > maxDots) Text("+${display.size - maxDots}", fontSize = 8.sp, color = Color(0xFF6B7D96))
    }
}

@Composable
fun TuringInventoryBar(
    allowed: List<String>,
    unlockedChips: Set<String>,
    selectedGroup: ChipGroup?,
    onChipDragStart: (chipId: String, global: Offset) -> Unit,
    onChipDrag: (chipId: String, global: Offset) -> Unit,
    onChipDrop: (chipId: String, global: Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val rowScroll = rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(rowScroll).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val filtered = allowed.filter { it in unlockedChips }.filter { chipId ->
            if (selectedGroup == null) true else {
                val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
                def?.let { groupForCategory(it.category) == selectedGroup } ?: true
            }
        }.sortedBy { try { ChipLibrary.get(it).nandCost } catch (_: Exception) { 999 } }

        filtered.forEach { chipId ->
            TuringDraggableChipItem(
                chipId = chipId,
                chipOnDragStart = { id, g -> onChipDragStart(id, g) },
                chipOnDrag = { id, g -> onChipDrag(id, g) },
                chipOnDrop = { id, g -> onChipDrop(id, g) }
            )
        }
        if (filtered.isEmpty()) {
            Text("No chips • ALL", fontSize = 10.sp, color = Color(0xFF6B7D96), modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun TuringDraggableChipItem(
    chipId: String,
    chipOnDragStart: (String, Offset) -> Unit,
    chipOnDrag: (String, Offset) -> Unit,
    chipOnDrop: (String, Offset) -> Unit
) {
    val def = ChipLibrary.get(chipId)
    val baseCol = when (def.category) {
        ChipCategory.PRIMITIVE -> Color(0xFF153A45)
        ChipCategory.FOUNDATION -> Color(0xFF144A38)
        ChipCategory.ROUTING -> Color(0xFF4A3514)
        ChipCategory.BUS -> Color(0xFF2B284A)
        ChipCategory.ARITH -> Color(0xFF5A2A14)
        ChipCategory.MEMORY -> Color(0xFF3A1E52)
        ChipCategory.CPU -> Color(0xFF5E1840)
    }
    val busW = def.dominantBusWidth()
    val busColor = when (busW) { 4 -> Color(0xFFFFA126); 8 -> Color(0xFF4FC3FF); else -> Color(0xFF2BE4B8) }

    var chipPosInWindow by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .onGloballyPositioned { c -> chipPosInWindow = c.positionInWindow() }
            .clip(RoundedCornerShape(6.dp))
            .background(baseCol)
            .border(if (isDragging) 1.4.dp else if (def.isBus) 0.9.dp else 0.6.dp, if (isDragging) Color.White else busColor.copy(alpha = if (def.isBus) 0.65f else 0.28f), RoundedCornerShape(6.dp))
            .pointerInput(chipId) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDownGlobal()
                        val startWindow = chipPosInWindow + down.position
                        var dragActive = false
                        var curWindow = startWindow
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.changedToUpIgnoreConsumed()) {
                                if (dragActive) chipOnDrop(chipId, curWindow)
                                isDragging = false
                                break
                            }
                            val total = ch.position - down.position
                            curWindow = startWindow + total
                            if (!dragActive && total.getDistance() > 14f) {
                                dragActive = true
                                isDragging = true
                                chipOnDragStart(chipId, curWindow)
                            }
                            if (dragActive) {
                                ch.consume()
                                chipOnDrag(chipId, curWindow)
                            }
                        }
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (busW > 1) Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(busColor))
                Text(text = def.displayName, fontSize = 10.sp, color = Color.White)
            }
            Text(text = "${def.inputs.size}→${def.outputs.size} ${def.nandCost}N", fontSize = 7.sp, color = Color.White.copy(alpha = 0.55f))
        }
    }
}

private suspend fun AwaitPointerEventScope.awaitFirstDownGlobal(): PointerInputChange {
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.type == PointerEventType.Press) {
            val d = ev.changes.firstOrNull { !it.isConsumed } ?: continue
            return d
        }
    }
}
