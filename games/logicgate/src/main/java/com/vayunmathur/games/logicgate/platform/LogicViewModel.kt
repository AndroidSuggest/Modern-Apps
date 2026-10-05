@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.games.logicgate.platform

import kotlin.uuid.Uuid
import android.app.Application
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.logicgate.data.ChapterId
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.Circuit
import com.vayunmathur.games.logicgate.data.CircuitSampling
import com.vayunmathur.games.logicgate.data.IoPos
import com.vayunmathur.games.logicgate.data.LevelDef
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.data.LogicProgressRepository
import com.vayunmathur.games.logicgate.data.OutputMapping
import com.vayunmathur.games.logicgate.data.PlacedChip
import com.vayunmathur.games.logicgate.data.Wire
import com.vayunmathur.games.logicgate.data.WireEnd
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Persisted format v2 with busWidth + lenient json
@Serializable
data class PersistedWire(
    val id: String,
    val fromInstance: String,
    val fromPin: Int,
    val toInstance: String,
    val toPin: Int,
    val busWidth: Int = 1,
)
@Serializable
data class PersistedGate(val instanceId: String, val chipId: String, val x: Float, val y: Float)
@Serializable
data class PersistedOutputMap(val outputIndex: Int, val fromInstance: String, val fromPin: Int)
@Serializable
data class PersistedIoPos(val x: Float, val y: Float)
@Serializable
data class PersistedCircuit(
    val gates: List<PersistedGate>,
    val wires: List<PersistedWire>,
    val outputs: List<PersistedOutputMap>,
    val inputPos: Map<Int, PersistedIoPos> = emptyMap(),
    val outputPos: Map<Int, PersistedIoPos> = emptyMap()
)
@Serializable
data class AllSavedCircuits(val map: Map<String, PersistedCircuit> = emptyMap())

data class UiState(
    val selectedChapter: ChapterId = ChapterId.FOUNDATION,
    val currentLevelId: String? = null,
    val circuit: Circuit = Circuit(),
    val evalStatus: EvalStatus = EvalStatus.Idle,
    val selectedGateInstanceId: String? = null,
    val wiringFrom: WireEnd? = null,
    val dragGhostLineEnd: Offset? = null,
    val showTruthTable: Boolean = true,
    val draggedChipGhost: DraggedChipGhost? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

data class DraggedChipGhost(val chipId: String, val offset: Offset)

sealed class EvalStatus {
    object Idle : EvalStatus()
    data class Ok(
        val passingRows: Int,
        val totalRows: Int,
        val isFullyCorrect: Boolean,
        val failingRows: List<Int>,
    ) : EvalStatus()
    data class Error(val msg: String) : EvalStatus()
    data class Cycle(val ids: List<String>) : EvalStatus()
}

class LogicViewModel(application: Application) : AndroidViewModel(application), LogicActions {
    internal val progressRepo = LogicProgressRepository(application)
    internal val dataStore = DataStoreUtils.getInstance(application)
    internal val appContext: Context = application.applicationContext
    private val ctx: Context get() = getApplication()

    val achievementsManager: LogicAchievementsManager = run {
        val json = engine.loadAchievementsJson()
        LogicAchievementsManager(ctx, json, progressRepo)
    }

    /** Engine owning history, persistence and evaluation (TooManyFunctions cap). */
    internal val engine by lazy { LogicEngine(this) }

    private val _completedIds = MutableStateFlow(progressRepo.getCompletedLevelIds())
    val completedIds: StateFlow<Set<String>> = _completedIds.asStateFlow()
    internal val completedIdsMutable: MutableStateFlow<Set<String>> get() = _completedIds

    private val _unlockedChips = MutableStateFlow(progressRepo.unlockedChipIds())
    val unlockedChips: StateFlow<Set<String>> = _unlockedChips.asStateFlow()
    internal val unlockedChipsMutable: MutableStateFlow<Set<String>> get() = _unlockedChips

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    internal val uiStateValue: UiState get() = _uiState.value
    internal fun updateUiState(transform: (UiState) -> UiState) {
        _uiState.update(transform)
    }

    internal var allCircuits: MutableMap<String, Circuit> = mutableMapOf()
    internal var persistJob: Job? = null
    internal val persistMutex = Mutex()
    internal val undoStacks: MutableMap<String, MutableList<Circuit>> = mutableMapOf()
    internal val redoStacks: MutableMap<String, MutableList<Circuit>> = mutableMapOf()
    internal val maxHistory = 24
    internal val vmScope get() = viewModelScope

    internal val jsonLenient = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            engine.loadAllCircuits()
            achievementsManager.checkExistingAchievements()
        }
    }

    override fun selectLevel(levelId: String) {
        val loaded = allCircuits[levelId] ?: Circuit()
        undoStacks.getOrPut(levelId) { mutableListOf() }
        redoStacks.getOrPut(levelId) { mutableListOf() }
        val canUndo = undoStacks[levelId]?.isNotEmpty() == true
        val canRedo = redoStacks[levelId]?.isNotEmpty() == true
        _uiState.update {
            it.copy(
                currentLevelId = levelId,
                circuit = loaded,
                evalStatus = EvalStatus.Idle,
                wiringFrom = null,
                selectedGateInstanceId = null,
                dragGhostLineEnd = null,
                canUndo = canUndo,
                canRedo = canRedo,
            )
        }
        engine.evaluateCurrent()
    }

    // NOTE: selectChapter/addGate/deleteSelected/completeWiring/toggleTruthTable and the
    // chip-palette drag handlers were removed 2026-10-05: orphaned by the LogicActions
    // refactor, zero callers module-wide (UI drives everything through LogicActions).
    // showTruthTable stays true; selectedChapter stays FOUNDATION.

    override fun addGateAt(chipId: String, x: Float?, y: Float?): String {
        val state = _uiState.value
        val newId = "G_${Uuid.random().toString().take(GATE_ID_SUFFIX)}"
        val cnt = state.circuit.gates.size
        // Alchemist-style responsive placement: use window-relative defaults clamped to sane visible range
        // instead of 0..3000 which places off-screen on small portrait
        val px = (x ?: engine.defaultGateX(cnt)).coerceIn(PLACEMENT_MIN, PLACEMENT_MAX_X)
        val py = (y ?: engine.defaultGateY(cnt)).coerceIn(PLACEMENT_MIN, PLACEMENT_MAX_Y)
        val gate = PlacedChip(newId, chipId, x = px, y = py)
        val newCircuit = state.circuit.copy(gates = state.circuit.gates + gate)
        engine.pushHistory(state.circuit)
        updateCircuit(newCircuit, immediatePersist = true)
        return newId
    }

    override fun removeGate(instanceId: String) {
        val s = _uiState.value.circuit
        engine.pushHistory(s)
        val newGates = s.gates.filterNot { it.instanceId == instanceId }
        val newWires = s.wires.filterNot {
            it.from.instanceId == instanceId || it.to.instanceId == instanceId
        }
        val newOuts = s.outputMappings.filterNot { it.from.instanceId == instanceId }
        updateCircuit(s.copy(gates = newGates, wires = newWires, outputMappings = newOuts))
    }

    override fun clearCircuit() {
        val s = _uiState.value.circuit
        if (s.gates.isEmpty() && s.wires.isEmpty() && s.outputMappings.isEmpty()) return
        engine.pushHistory(s)
        updateCircuit(Circuit())
    }

    override fun selectGate(id: String?) {
        _uiState.update { it.copy(selectedGateInstanceId = id) }
    }

    override fun undo() {
        val lvl = _uiState.value.currentLevelId ?: return
        val uStack = undoStacks[lvl] ?: return
        if (uStack.isEmpty()) return
        val rStack = redoStacks.getOrPut(lvl) { mutableListOf() }
        rStack.add(_uiState.value.circuit)
        val prev = uStack.removeAt(uStack.lastIndex)
        allCircuits[lvl] = prev
        _uiState.update {
            it.copy(circuit = prev, canUndo = uStack.isNotEmpty(), canRedo = rStack.isNotEmpty())
        }
        engine.schedulePersist()
        engine.evaluateCurrent()
    }

    override fun redo() {
        val lvl = _uiState.value.currentLevelId ?: return
        val rStack = redoStacks[lvl] ?: return
        if (rStack.isEmpty()) return
        val uStack = undoStacks.getOrPut(lvl) { mutableListOf() }
        uStack.add(_uiState.value.circuit)
        val next = rStack.removeAt(rStack.lastIndex)
        allCircuits[lvl] = next
        _uiState.update {
            it.copy(circuit = next, canUndo = uStack.isNotEmpty(), canRedo = rStack.isNotEmpty())
        }
        engine.schedulePersist()
        engine.evaluateCurrent()
    }

    override fun onGateMoved(instanceId: String, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val newGates = s.gates.map {
            if (it.instanceId == instanceId) {
                it.copy(x = engine.clampCanvasX(x), y = engine.clampCanvasY(y))
            } else {
                it
            }
        }
        _uiState.update { it.copy(circuit = it.circuit.copy(gates = newGates)) }
    }
    override fun onGateMoveFinished(instanceId: String, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val prevCircuit = allCircuits[_uiState.value.currentLevelId] ?: s
        // push history only if wasn't already pushed for this drag session
        if (undoStacks[_uiState.value.currentLevelId]?.lastOrNull() != prevCircuit) {
            engine.pushHistory(prevCircuit)
        }
        val newGates = s.gates.map {
            if (it.instanceId == instanceId) {
                it.copy(x = engine.clampCanvasX(x), y = engine.clampCanvasY(y))
            } else {
                it
            }
        }
        val newCircuit = s.copy(gates = newGates)
        val lvl = _uiState.value.currentLevelId
        if (lvl != null) allCircuits[lvl] = newCircuit
        _uiState.update { it.copy(circuit = newCircuit) }
        engine.schedulePersist()
        engine.evaluateCurrent()
    }

    override fun onInputMoved(idx: Int, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val newMap = s.inputPositions.toMutableMap()
        newMap[idx] = IoPos(engine.clampCanvasX(x), engine.clampCanvasY(y))
        _uiState.update { it.copy(circuit = s.copy(inputPositions = newMap)) }
    }
    override fun onInputMoveFinished(idx: Int, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val prevCircuit = allCircuits[_uiState.value.currentLevelId] ?: s
        engine.pushHistory(prevCircuit)
        val newMap = s.inputPositions.toMutableMap()
        newMap[idx] = IoPos(engine.clampCanvasX(x), engine.clampCanvasY(y))
        val newCircuit = s.copy(inputPositions = newMap)
        val lvl = _uiState.value.currentLevelId
        if (lvl != null) allCircuits[lvl] = newCircuit
        _uiState.update { it.copy(circuit = newCircuit) }
        engine.schedulePersist()
    }
    override fun onOutputMoved(idx: Int, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val newMap = s.outputPositions.toMutableMap()
        newMap[idx] = IoPos(engine.clampCanvasX(x), engine.clampCanvasY(y))
        _uiState.update { it.copy(circuit = s.copy(outputPositions = newMap)) }
    }
    override fun onOutputMoveFinished(idx: Int, x: Float, y: Float) {
        val s = _uiState.value.circuit
        val prevCircuit = allCircuits[_uiState.value.currentLevelId] ?: s
        engine.pushHistory(prevCircuit)
        val newMap = s.outputPositions.toMutableMap()
        newMap[idx] = IoPos(engine.clampCanvasX(x), engine.clampCanvasY(y))
        val newCircuit = s.copy(outputPositions = newMap)
        val lvl = _uiState.value.currentLevelId
        if (lvl != null) allCircuits[lvl] = newCircuit
        _uiState.update { it.copy(circuit = newCircuit) }
        engine.schedulePersist()
    }
        _uiState.update { it.copy(circuit = newCircuit) }
        engine.schedulePersist()
    }

    override fun startWiring(from: WireEnd) {
        _uiState.update { it.copy(wiringFrom = from) }
    }
    override fun cancelWiring() {
        _uiState.update { it.copy(wiringFrom = null, dragGhostLineEnd = null) }
    }
    override fun updateGhostLine(end: Offset?) {
        _uiState.update { it.copy(dragGhostLineEnd = end) }
    }

    override fun createWire(from: WireEnd, to: WireEnd) = engine.wiring.createWire(from, to)

    override fun removeWire(wireId: String) {
        val s = _uiState.value.circuit
        engine.pushHistory(s)
        updateCircuit(s.copy(wires = s.wires.filterNot { it.id == wireId }))
    }
    override fun removeOutputMapping(outIdx: Int) {
        val s = _uiState.value.circuit
        engine.pushHistory(s)
        updateCircuit(s.copy(outputMappings = s.outputMappings.filterNot { it.outputIndex == outIdx }))
    }

    internal fun updateCircuit(newCircuit: Circuit, immediatePersist: Boolean = false) {
        val lvlId = _uiState.value.currentLevelId
        if (lvlId != null) {
            allCircuits[lvlId] = newCircuit
            if (immediatePersist) engine.saveAllCircuitsNow() else engine.schedulePersist()
        }
        val canUndoFlag = lvlId?.let { undoStacks[it]?.isNotEmpty() } ?: false
        val canRedoFlag = lvlId?.let { redoStacks[it]?.isNotEmpty() } ?: false
        _uiState.update {
            it.copy(circuit = newCircuit, canUndo = canUndoFlag, canRedo = canRedoFlag)
        }
        engine.evaluateCurrent()
    }

    fun dismissAchievement() = achievementsManager.dismissNotification()

    companion object {
        private const val KEY_CIRCUITS = "logicgate_circuits_v1"
        private const val ACHIEVEMENTS_ASSET = "achievements.json"
        private const val EMPTY_ACHIEVEMENTS_JSON = "[]"

        /** Canvas drag clamp range (world coordinates). */
        private const val CANVAS_MIN = -4000f
        private const val CANVAS_MAX_X = 6000f

        /** Random suffix length for generated wire ids. */
        private const val WIRE_ID_SUFFIX = 6

        /** Random suffix length for generated gate ids. */
        private const val GATE_ID_SUFFIX = 6

        /** Minimum CPU<->RAM wires before the COMPUTER level checks bus usage. */
        private const val MIN_CPU_RAM_WIRES = 2

        /** Responsive default gate placement grid (world coordinates). */
        private const val PLACEMENT_COLS = 4
        private const val PLACEMENT_BASE_X = 80f
        private const val PLACEMENT_BASE_Y = 100f
        private const val PLACEMENT_DX = 140f
        private const val PLACEMENT_DY = 110f
        private const val PLACEMENT_MIN = 8f
        private const val PLACEMENT_MAX_X = 1200f
        private const val PLACEMENT_MAX_Y = 2000f
    }
}

private fun PersistedCircuit.toCircuit(): Circuit {
    return Circuit(
        gates = gates.map { PlacedChip(it.instanceId, it.chipId, it.x, it.y) },
        wires = wires.map {
            Wire(
                it.id,
                WireEnd(it.fromInstance, it.fromPin),
                WireEnd(it.toInstance, it.toPin),
                busWidth = it.busWidth.coerceIn(CircuitSampling.BIT, CircuitSampling.BUS_8),
            )
        },
        outputMappings = outputs.map { OutputMapping(it.outputIndex, WireEnd(it.fromInstance, it.fromPin)) },
        inputPositions = inputPos.mapValues { IoPos(it.value.x, it.value.y) },
        outputPositions = outputPos.mapValues { IoPos(it.value.x, it.value.y) },
    )
}
private fun Circuit.toPersisted(): PersistedCircuit {
    return PersistedCircuit(
        gates = gates.map { PersistedGate(it.instanceId, it.chipId, it.x, it.y) },
        wires = wires.map {
            PersistedWire(
                it.id,
                it.from.instanceId,
                it.from.pinIndex,
                it.to.instanceId,
                it.to.pinIndex,
                busWidth = it.busWidth,
            )
        },
        outputs = outputMappings.map { PersistedOutputMap(it.outputIndex, it.from.instanceId, it.from.pinIndex) },
        inputPos = inputPositions.mapValues { PersistedIoPos(it.value.x, it.value.y) },
        outputPos = outputPositions.mapValues { PersistedIoPos(it.value.x, it.value.y) },
    )
}
