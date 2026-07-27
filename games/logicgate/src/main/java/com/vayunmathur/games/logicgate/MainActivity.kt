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
import com.vayunmathur.games.logicgate.util.AppBackupAgent
import com.vayunmathur.games.logicgate.util.EvalStatus
import com.vayunmathur.games.logicgate.util.LogicViewModel
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
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

    // ---- Representative test vector for live I/O values (mirrors evaluator seed 1234) ----
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "${level.displayName}  ${if (levelId in completed) "✓" else ""}", fontSize = 16.sp) },
                navigationIcon = { IconNavigation(backStack) },
                actions = {
                    Row(modifier = Modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (uiState.canUndo) Button(onClick = { viewModel.undo() }) { Text(stringResource(R.string.undo), fontSize = 10.sp) }
                        if (uiState.canRedo) Button(onClick = { viewModel.redo() }) { Text(stringResource(R.string.redo), fontSize = 10.sp) }
                        Button(onClick = { viewModel.toggleTruthTable() }) { Text(text = if (uiState.showTruthTable) "Hide" else "Show", fontSize = 10.sp) }
                    }
                }
            )
        },
        bottomBar = {
            if (isCurrent) {
                Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E2433))) {
                    // BIT / WORD / CUSTOM filter — horizontal for mobile (like screenshot right vertical tabs)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf<Pair<ChipGroup?, String>>(null to "ALL", ChipGroup.BIT to "BIT", ChipGroup.WORD to "WORD", ChipGroup.CUSTOM to "CUSTOM").forEach { (g, label) ->
                            val isSel = selectedGroup == g
                            Button(onClick = { selectedGroup = g }) {
                                Text(text = label, fontSize = 10.sp, color = if (isSel) Color(0xFF2BE4B8) else Color(0xFF9AA3BB))
                            }
                        }
                    }
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
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) { padding ->
        if (!isCurrent) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.loading)) }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFF0F1E2D))) {
            val isWiring = uiState.wiringFrom != null
            val statusText = when (val s = uiState.evalStatus) {
                is EvalStatus.Ok -> if (s.isFullyCorrect) "✓ CORRECT — ${level.unlocksChipId?.let { "Unlocked $it!" } ?: "PASS"}"
                else "${s.passingRows}/${s.totalRows} — ${s.failingRows.size} failing • sel #${selectedIdx}"
                is EvalStatus.Error -> "Error: ${s.msg}"
                is EvalStatus.Cycle -> "Cycle: ${s.ids.take(3).joinToString()} — needs latch"
                else -> "Drag from inventory • Move items on grid • Tap output→input to wire • Tap wire to delete • Long-press gate delete"
            }
            val statusColor = when {
                uiState.evalStatus is EvalStatus.Ok && (uiState.evalStatus as EvalStatus.Ok).isFullyCorrect -> Color(0xFF22C55E)
                uiState.evalStatus is EvalStatus.Ok && (uiState.evalStatus as EvalStatus.Ok).passingRows > 0 -> Color(0xFFFBBF24)
                else -> Color(0xFF94A3B8)
            }

            // Status bar — dark compact with wiring crossfade (like Alchemist trash vs inventory)
            Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF16202B)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Crossfade(targetState = isWiring, label = "wiring_cf") { wiring ->
                            if (wiring) Text(text = "Wiring: tap input dot (yellow) or empty to cancel", fontSize = 11.sp, color = Color(0xFFFFFF00))
                            else Text(text = statusText, fontSize = 11.sp, color = statusColor)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LegendDot(Color(0xFF22C55E), "1b ON") // green = 1 like screenshot
                            LegendDot(Color(0xFFEF4444), "0 OFF")
                            LegendDot(Color(0xFFFFA126), "4b")
                            LegendDot(Color(0xFF4FC3FF), "8b")
                            Text(text = level.description.take(52), fontSize = 9.sp, color = Color(0xFF6B7D96))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { viewModel.clearCircuit() }) { Text(stringResource(R.string.clear), fontSize = 9.sp) }
                        if (isWiring) Button(onClick = { viewModel.cancelWiring() }) { Text(stringResource(R.string.cancel_wire), fontSize = 9.sp) }
                        Card(modifier = Modifier.padding(start = 2.dp)) {
                            Text(stringResource(R.string.gates, uiState.circuit.gates.size), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                }
            }

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth > maxHeight

                @Composable
                fun GameCanvas(mod: Modifier) {
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
                        modifier = mod
                    )
                }

                // Wide: Row [I/O panel | toolbar | canvas | truth panel]
                // Portrait: Column [canvas with floating toolbar | truth panel]
                if (wide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        TuringIOPanel(
                            level = level,
                            inputDecimals = inputDecimals,
                            inputBitSlices = inputBitSlices,
                            desiredDecimals = desiredDecimals,
                            desiredBitSlices = desiredBitSlices,
                            actualDecimals = actualDecimals,
                            actualBitSlices = actualBitSlices,
                            modifier = Modifier.width(150.dp).fillMaxHeight().background(Color(0xFF1B2636))
                        )
                        TuringVerticalToolbar(
                            onClear = { viewModel.clearCircuit() },
                            onUndo = { viewModel.undo() },
                            onRedo = { viewModel.redo() },
                            canUndo = uiState.canUndo,
                            canRedo = uiState.canRedo,
                            modifier = Modifier.width(52.dp).fillMaxHeight().background(Color(0xFF121B27))
                        )
                        // Canvas Box tracks its own positionInWindow for inventory drop — fixes wide misalignment
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { coords ->
                                canvasPosInWindow = coords.positionInWindow()
                                canvasSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                            }
                        ) {
                            GameCanvas(Modifier.fillMaxSize())
                            // Global ghost overlay inside canvas Box (Alchemist IntOffset pattern)
                            draggingChipId?.let { chipId ->
                                val localOffset = Offset(draggingChipWindowPos.x - canvasPosInWindow.x, draggingChipWindowPos.y - canvasPosInWindow.y)
                                val isOver = localOffset.x >= 0f && localOffset.x <= canvasSize.width && localOffset.y >= 0f && localOffset.y <= canvasSize.height
                                val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
                                val ghostW = 96.dp; val ghostH = 44.dp
                                Box(
                                    modifier = Modifier.offset {
                                        IntOffset(
                                            (localOffset.x - with(density) { ghostW.toPx() } / 2f).roundToInt(),
                                            (localOffset.y - with(density) { ghostH.toPx() } / 2f).roundToInt()
                                        )
                                    }.size(ghostW, ghostH)
                                        .background(if (isOver) Color(0xFFD1FAE5).copy(alpha = 0.92f) else Color(0xFF4B5563).copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .border(if (isOver) 2.dp else 1.dp, if (isOver) Color(0xFF22C55E) else Color.White.copy(alpha = 0.28f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) { def?.let { Text(text = it.displayName.take(8), fontSize = 10.sp, color = if (isOver) Color(0xFF14532D) else Color.White) } }
                            }
                        }
                        if (uiState.showTruthTable) {
                            TuringBottomTruthPanel(
                                level = level,
                                selectedIdx = selectedIdx,
                                inputDecimals = inputDecimals,
                                inputBitSlices = inputBitSlices,
                                desiredDecimals = desiredDecimals,
                                desiredBitSlices = desiredBitSlices,
                                actualDecimals = actualDecimals,
                                actualBitSlices = actualBitSlices,
                                failingCount = (uiState.evalStatus as? EvalStatus.Ok)?.failingRows?.size ?: 0,
                                modifier = Modifier.width(260.dp).fillMaxHeight().background(Color(0xFF212636))
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { coords ->
                                canvasPosInWindow = coords.positionInWindow()
                                canvasSize = Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                            }
                        ) {
                            GameCanvas(Modifier.fillMaxSize())
                            // Floating vertical toolbar top-start for portrait (like screenshot left of canvas icons)
                            Column(
                                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xFF1B2636).copy(alpha = 0.92f), RoundedCornerShape(8.dp)).padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ToolbarIcon("🗑", onClick = { viewModel.clearCircuit() })
                                if (uiState.canUndo) ToolbarIcon("↩", onClick = { viewModel.undo() })
                                if (uiState.canRedo) ToolbarIcon("↪", onClick = { viewModel.redo() })
                                ToolbarIcon("8") {}
                            }
                            draggingChipId?.let { chipId ->
                                val localOffset = Offset(draggingChipWindowPos.x - canvasPosInWindow.x, draggingChipWindowPos.y - canvasPosInWindow.y)
                                val isOver = localOffset.x >= 0f && localOffset.x <= canvasSize.width && localOffset.y >= 0f && localOffset.y <= canvasSize.height
                                val def = try { ChipLibrary.get(chipId) } catch (_: Exception) { null }
                                val ghostW = 96.dp; val ghostH = 44.dp
                                Box(
                                    modifier = Modifier.offset {
                                        IntOffset(
                                            (localOffset.x - with(density) { ghostW.toPx() } / 2f).roundToInt(),
                                            (localOffset.y - with(density) { ghostH.toPx() } / 2f).roundToInt()
                                        )
                                    }.size(ghostW, ghostH)
                                        .background(if (isOver) Color(0xFFD1FAE5).copy(alpha = 0.92f) else Color(0xFF4B5563).copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                        .border(if (isOver) 2.dp else 1.dp, if (isOver) Color(0xFF22C55E) else Color.White.copy(alpha = 0.28f), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) { def?.let { Text(text = it.displayName.take(8), fontSize = 10.sp, color = if (isOver) Color(0xFF14532D) else Color.White) } }
                            }
                        }
                        if (uiState.showTruthTable) {
                            TuringBottomTruthPanel(
                                level = level,
                                selectedIdx = selectedIdx,
                                inputDecimals = inputDecimals,
                                inputBitSlices = inputBitSlices,
                                desiredDecimals = desiredDecimals,
                                desiredBitSlices = desiredBitSlices,
                                actualDecimals = actualDecimals,
                                actualBitSlices = actualBitSlices,
                                failingCount = (uiState.evalStatus as? EvalStatus.Ok)?.failingRows?.size ?: 0,
                                modifier = Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF212636))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(col: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(col))
        Text(text = label, fontSize = 8.sp, color = Color(0xFF8AA0BA))
    }
}

@Composable
private fun TuringVerticalToolbar(
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ToolbarIcon("⌕") {}
        ToolbarIcon("▶") {}
        ToolbarIcon("■", onClick = onClear)
        Box(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color(0xFF2A3A50)))
        ToolbarIcon("⬚") {}
        ToolbarIcon("🗑", onClick = onClear)
        ToolbarIcon("✎") {}
        ToolbarIcon("🎨") {}
        if (canUndo) ToolbarIcon("↩", onClick = onUndo)
        if (canRedo) ToolbarIcon("↪", onClick = onRedo)
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A3042)), contentAlignment = Alignment.Center) {
            Text("8", fontSize = 10.sp, color = Color.White)
        }
    }
}

@Composable
private fun ToolbarIcon(symbol: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF222C3E)).border(0.6.dp, Color(0xFF32415C), RoundedCornerShape(6.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(symbol, fontSize = 12.sp, color = Color(0xFF9AA3BB)) }
}

@Composable
private fun TuringIOPanel(
    level: com.vayunmathur.games.logicgate.data.LevelDef,
    inputDecimals: Map<Int, Int>,
    inputBitSlices: Map<Int, List<Boolean>>,
    desiredDecimals: Map<Int, Int>,
    desiredBitSlices: Map<Int, List<Boolean>>,
    actualDecimals: Map<Int, Int>,
    actualBitSlices: Map<Int, List<Boolean>>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
        Text("Inputs", fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(bottom = 4.dp))
        level.inputs.forEachIndexed { idx, name ->
            val dec = inputDecimals[idx] ?: 0
            val bits = inputBitSlices[idx] ?: emptyList()
            IOPanelRow(name, dec, bits, Color(0xFF38BDF8))
            Spacer(modifier = Modifier.height(6.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Outputs", fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(bottom = 4.dp))
        level.outputs.forEachIndexed { idx, name ->
            val decD = desiredDecimals[idx] ?: 0
            val bitsD = desiredBitSlices[idx] ?: emptyList()
            IOPanelRow("$name exp", decD, bitsD, Color(0xFFFBBF24))
            val decA = actualDecimals[idx] ?: 0
            val bitsA = actualBitSlices[idx] ?: emptyList()
            IOPanelRow("$name cur", decA, bitsA, Color(0xFF22C55E))
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun IOPanelRow(name: String, dec: Int, bits: List<Boolean>, col: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(col))
            Text(text = name.take(14), fontSize = 9.sp, color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(start = 4.dp, top = 2.dp)) {
            BitDotRow(bits, dotSize = 6.dp)
        }
        Text(text = "$dec", fontSize = 9.sp, color = Color(0xFF9AA3BB), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun TuringBottomTruthPanel(
    level: com.vayunmathur.games.logicgate.data.LevelDef,
    selectedIdx: Int,
    inputDecimals: Map<Int, Int>,
    inputBitSlices: Map<Int, List<Boolean>>,
    desiredDecimals: Map<Int, Int>,
    desiredBitSlices: Map<Int, List<Boolean>>,
    actualDecimals: Map<Int, Int>,
    actualBitSlices: Map<Int, List<Boolean>>,
    failingCount: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Test #${selectedIdx} • ${level.displayName} • fail $failingCount", fontSize = 9.sp, color = Color(0xFF94A3B8))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tick 0", fontSize = 8.sp, color = Color(0xFF6B7D96))
                Text("0hz", fontSize = 8.sp, color = Color(0xFF6B7D96))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        level.inputs.forEachIndexed { idx, name ->
            val dec = inputDecimals[idx] ?: 0
            val bits = inputBitSlices[idx] ?: emptyList()
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = name, fontSize = 10.sp, color = Color(0xFFFBBF24), modifier = Modifier.width(86.dp))
                Text(text = "$dec", fontSize = 10.sp, color = Color.White, modifier = Modifier.width(34.dp))
                BitDotRow(bits, dotSize = 8.dp)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A3042)))
        Spacer(modifier = Modifier.height(4.dp))
        level.outputs.forEachIndexed { idx, name ->
            val decD = desiredDecimals[idx] ?: 0
            val bitsD = desiredBitSlices[idx] ?: emptyList()
            val decA = actualDecimals[idx] ?: 0
            val bitsA = actualBitSlices[idx] ?: emptyList()
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Desired $name", fontSize = 9.sp, color = Color(0xFFF59E0B), modifier = Modifier.width(86.dp))
                Text(text = "$decD", fontSize = 9.sp, color = Color.White, modifier = Modifier.width(34.dp))
                BitDotRow(bitsD, dotSize = 8.dp)
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val match = decD == decA
                Text(text = "Current $name", fontSize = 9.sp, color = if (match) Color(0xFF22C55E) else Color(0xFFFF8A80), modifier = Modifier.width(86.dp))
                Text(text = "$decA", fontSize = 9.sp, color = if (match) Color(0xFF86EFAC) else Color(0xFFFF8A80), modifier = Modifier.width(34.dp))
                BitDotRow(bitsA, dotSize = 8.dp)
                if (!match) Text(text = " ✗", fontSize = 10.sp, color = Color(0xFFFF8A80))
            }
        }
    }
}

@Composable
fun BitDot(isOne: Boolean, dotSize: androidx.compose.ui.unit.Dp = 8.dp) {
    // Green = 1 (ON) matches Turing Complete screenshot where green dot = 1
    Box(modifier = Modifier.size(dotSize).clip(CircleShape).background(if (isOne) Color(0xFF22C55E) else Color(0xFFEF4444)))
}

@Composable
fun BitDotRow(bits: List<Boolean>, dotSize: androidx.compose.ui.unit.Dp = 7.dp, maxDots: Int = 8) {
    val display = if (bits.size > 1) bits.reversed() else bits // MSB left like screenshot
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        display.take(maxDots).forEach { b -> BitDot(b, dotSize) }
        if (display.size > maxDots) Text(text = "+${display.size - maxDots}", fontSize = 8.sp, color = Color(0xFF6B7D96))
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
    Column(modifier = modifier.background(Color(0xFF131C26), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).padding(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rowScroll),
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
                Text("No chips in filter • select ALL", fontSize = 10.sp, color = Color(0xFF6B7D96), modifier = Modifier.padding(8.dp))
            }
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
        ChipCategory.PRIMITIVE -> Color(0xFF1B3A4A)
        ChipCategory.FOUNDATION -> Color(0xFF134E2D)
        ChipCategory.ROUTING -> Color(0xFF5A3812)
        ChipCategory.BUS -> Color(0xFF312A4A)
        ChipCategory.ARITH -> Color(0xFF6B2A12)
        ChipCategory.MEMORY -> Color(0xFF3A1A52)
        ChipCategory.CPU -> Color(0xFF6B1437)
    }
    val busW = def.dominantBusWidth()
    val busColor = when (busW) { 4 -> Color(0xFFFFA126); 8 -> Color(0xFF4FC3FF); else -> Color(0xFF2BE4B8) }

    var chipPosInWindow by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .onGloballyPositioned { c -> chipPosInWindow = c.positionInWindow() }
            .clip(RoundedCornerShape(8.dp))
            .background(baseCol)
            .border(if (isDragging) 1.6.dp else if (def.isBus) 1.dp else 0.6.dp, if (isDragging) Color.White else busColor.copy(alpha = if (def.isBus) 0.7f else 0.25f), RoundedCornerShape(8.dp))
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
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (busW > 1) Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(busColor))
                Text(text = def.displayName, fontSize = 10.sp, color = Color.White)
            }
            Text(text = "${def.inputs.size}→${def.outputs.size} ${def.nandCost}N", fontSize = 8.sp, color = Color.White.copy(alpha = 0.6f))
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
