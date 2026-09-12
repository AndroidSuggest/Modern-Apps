package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.platform.EvalStatus
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.platform.UiState
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults

/**
 * The circuit editor, with no dependency on the ViewModel or the back stack so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from. Everything it derives (truth table, evaluation, terminal layout) is a
 * pure function of [state] and the static level table.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    levelId: String,
    state: UiState,
    unlockedChips: Set<String>,
    actions: LogicActions,
    onBack: () -> Unit = {},
    onOpenLevel: (String) -> Unit = {},
    /**
     * Seeds the win dialog, which the app only ever opens from the evaluation result. A
     * preview sets it so the "level complete" moment can be captured directly.
     */
    initialShowWinDialog: Boolean = false,
) {
    val level = Levels.get(levelId)

    var canvasPosInWindow by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var canvasScale by remember { mutableStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }
    var draggingChipId by remember { mutableStateOf<String?>(null) }
    var draggingChipWindowPos by remember { mutableStateOf(Offset.Zero) }
    var selectedGroup by remember { mutableStateOf<ChipGroup?>(null) }
    var showIoSheet by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val totalInBits = level.totalInputBits
    val displayLimit = if (totalInBits <= 10) minOf(64, (1 shl totalInBits)) else 28
    val inputVectors = rememberInputVectors(totalInBits, displayLimit, levelId)

    var selectedIdxMutable by remember(levelId) { mutableStateOf(0) }
    var liveFlatInputs by remember(levelId) { mutableStateOf<List<Boolean>?>(null) }

    LaunchedEffect(levelId) {
        actions.selectLevel(levelId)
        liveFlatInputs = null
        selectedIdxMutable = 0
    }
    LaunchedEffect(state.evalStatus) {
        if (liveFlatInputs == null) {
            val failing = (state.evalStatus as? EvalStatus.Ok)?.failingRows
            selectedIdxMutable = if (failing != null && failing.isNotEmpty()) failing.first() % inputVectors.size else 0
        }
    }

    val flatInSelectedBase = remember(inputVectors, selectedIdxMutable) { inputVectors.getOrNull(selectedIdxMutable) ?: emptyList() }
    val effectiveFlat = liveFlatInputs ?: flatInSelectedBase

    val derived = rememberDerivedEval(level, state, effectiveFlat)
    val (actualBitSlices, actualDecimals) = rememberActualSlices(level, derived.evalRows, selectedIdxMutable, liveFlatInputs, inputVectors)

    if (state.currentLevelId != levelId) {
        LoadingState(
            message = stringResource(R.string.loading),
            modifier = Modifier.fillMaxSize().background(Color(0xFF0F1E2D)),
        )
        return
    }

    val gateCost = state.circuit.totalNandCost()

    fun toggleInput(idx: Int) {
        val off = level.inputBitOffset(idx)
        val wd = level.inputWidth(idx)
        if (off >= effectiveFlat.size && effectiveFlat.isNotEmpty()) return
        val baseFlat = if (effectiveFlat.size == totalInBits) effectiveFlat else flatInSelectedBase.ifEmpty { List(totalInBits) { false } }
        val mutable = baseFlat.toMutableList()
        if (wd == 1) {
            if (off < mutable.size) mutable[off] = !mutable[off]
        } else {
            val slice = if (off + wd <= mutable.size) mutable.slice(off until off + wd) else List(wd) { false }
            var v = ChipLibrary.bitsToInt(slice)
            v = (v + 1) % (1 shl wd)
            for (k in 0 until wd) if (off + k < mutable.size) mutable[off + k] = ((v shr k) and 1) == 1
        }
        liveFlatInputs = mutable.toList()
        val found = inputVectors.indexOfFirst { it == mutable }
        if (found >= 0) selectedIdxMutable = found
    }

    val tableRows = rememberTableRows(inputVectors, level, derived.evalRows)
    val failingSet = (state.evalStatus as? EvalStatus.Ok)?.failingRows?.toSet() ?: emptySet()

    // Win popup: surface as soon as the level is fully correct.
    val isWon = (state.evalStatus as? EvalStatus.Ok)?.isFullyCorrect == true
    var showWinDialog by remember(levelId) { mutableStateOf(initialShowWinDialog) }
    var winDismissed by remember(levelId) { mutableStateOf(false) }
    LaunchedEffect(isWon) { if (isWon && !winDismissed) showWinDialog = true }
    val nextLevelId = remember(levelId) {
        val idx = Levels.all.indexOfFirst { it.id == levelId }
        if (idx >= 0) Levels.all.getOrNull(idx + 1)?.id else null
    }
    val availableGroups = remember(level.allowedChipIds, unlockedChips) {
        level.allowedChipIds.filter { it in unlockedChips }
            .mapNotNull { try { groupForCategory(ChipLibrary.get(it).category) } catch (_: Exception) { null } }
            .toSet()
    }

    val layoutData = GameLayoutData(
        level = level, state = state, unlockedChips = unlockedChips,
        selectedGroup = selectedGroup, availableGroups = availableGroups,
        inputVectors = inputVectors, selectedIdx = selectedIdxMutable,
        tableRows = tableRows, failingSet = failingSet,
        inputDecimals = derived.inputDecimals, inputBitSlices = derived.inputSlices,
        inputOnMap = derived.inputOn, desiredDecimals = derived.desiredDecimals,
        desiredBitSlices = derived.desiredSlices, actualDecimals = actualDecimals,
        actualBitSlices = actualBitSlices, draggingChipId = draggingChipId,
        draggingChipWindowPos = draggingChipWindowPos,
        canvasPosInWindow = canvasPosInWindow, canvasSize = canvasSize,
        canvasScale = canvasScale, canvasOffset = canvasOffset, isCompact = false,
    )
    val layoutEvents = GameLayoutEvents(
        actions = actions,
        onSelectGroup = { selectedGroup = it },
        onChipDragStart = { chipId, windowOffset -> draggingChipId = chipId; draggingChipWindowPos = windowOffset },
        onChipDrag = { chipId, windowOffset -> draggingChipId = chipId; draggingChipWindowPos = windowOffset },
        onChipDropAt = { chipId, windowOffset ->
            chipDropContent(chipId, windowOffset, canvasPosInWindow, canvasSize, canvasOffset, canvasScale, density, textMeasurer)
                ?.let { content -> actions.addGateAt(chipId, content.x, content.y) }
            draggingChipId = null; draggingChipWindowPos = Offset.Zero
        },
        onSelectRow = { rowIdx ->
            selectedIdxMutable = rowIdx
            liveFlatInputs = inputVectors.getOrNull(rowIdx)
        },
        onToggleInput = { idx -> toggleInput(idx) },
        onViewportChange = { s, o -> canvasScale = s; canvasOffset = o },
        onCanvasPositioned = { pos, size -> canvasPosInWindow = pos; canvasSize = size },
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val maxW = maxWidth
        val maxH = maxHeight
        val isCompact = maxW < 600.dp
        val isPortrait = maxH > maxW
        val isCompactPortrait = isCompact && isPortrait
        // RAW SCAFFOLD EXCEPTION: bespoke circuit-editor surface. The top bar uses a
        // custom Turing dark palette (TopAppBarColors) and the whole scaffold a custom
        // containerColor, neither of which AppScaffold can express, and the canvas hosts
        // window-coordinate drag/ghost overlays. A shared scaffold does not fit here.
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = level.displayName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Turing.headerPink, maxLines = 1)
                    },
                    navigationIcon = { IconNavigation(onBack) },
                    actions = {
                        androidx.compose.foundation.layout.Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Turing.rightTabOn).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(stringResource(R.string.gate, gateCost), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        AppBarActionBtn(glyph = "↩", enabled = state.canUndo) { actions.undo() }
                        AppBarActionBtn(glyph = "↪", enabled = state.canRedo) { actions.redo() }
                        Spacer(modifier = Modifier.width(4.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Turing.headerBg, titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White)
                )
            },
            containerColor = Turing.headerBg
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).background(Turing.headerBg)) {
                val data = layoutData.copy(isCompact = isCompact)
                if (isCompactPortrait) {
                    GamePortraitLayout(data = data, events = layoutEvents, modifier = Modifier.weight(1f).fillMaxWidth())
                } else {
                    GameLandscapeLayout(data = data, events = layoutEvents, modifier = Modifier.weight(1f).fillMaxWidth())
                }
                if (showIoSheet) {
                    MobileIoSheet(level = level, inputDecimals = derived.inputDecimals, inputBitSlices = derived.inputSlices, desiredDecimals = derived.desiredDecimals, desiredBitSlices = derived.desiredSlices, actualDecimals = actualDecimals, actualBitSlices = actualBitSlices, onDismiss = { showIoSheet = false })
                }
            }
        }
        if (showWinDialog) {
            WinDialog(
                levelName = level.displayName,
                nextLevelId = nextLevelId,
                onDismiss = { showWinDialog = false; winDismissed = true },
                onOpenLevel = { id -> showWinDialog = false; onOpenLevel(id) },
                onBack = { showWinDialog = false; onBack() },
            )
        }
    }
}
