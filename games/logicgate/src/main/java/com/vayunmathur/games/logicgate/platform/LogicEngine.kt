package com.vayunmathur.games.logicgate.platform

import com.vayunmathur.games.logicgate.data.Circuit
import com.vayunmathur.games.logicgate.data.LevelDef
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Internal engine behind [LogicViewModel].
 *
 * Split out: TooManyFunctions cap (25/class). Same module and package, so
 * internal access is unchanged; behavior identical. The ViewModel keeps the
 * [LogicActions] overrides (thin, delegating here), the UI-observed state,
 * and the two members the UI touches directly
 * ([LogicViewModel.dismissAchievement], `achievementsManager`).
 */
internal class LogicEngine internal constructor(internal val vm: LogicViewModel) {
    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    internal fun pushHistory(c: Circuit) {
        val lvl = vm.uiStateValue.currentLevelId ?: return
        val stack = vm.undoStacks.getOrPut(lvl) { mutableListOf() }
        // Alchemist-style dedup: avoid filling undo stack with micro-moves (<1f jitter)
        val prev = stack.lastOrNull()
        if (prev != null && circuitsAreSameIgnoringJitter(prev, c)) return
        stack.add(c)
        if (stack.size > vm.maxHistory) stack.removeAt(0)
        vm.redoStacks[lvl]?.clear()
        vm.updateUiState { it.copy(canUndo = stack.isNotEmpty(), canRedo = false) }
    }

    internal fun circuitsAreSameIgnoringJitter(a: Circuit, b: Circuit, thresh: Float = 1f): Boolean {
        if (!sameTopology(a, b)) return false
        if (!sameGates(a, b, thresh)) return false
        if (a.wires != b.wires) return false
        if (a.outputMappings != b.outputMappings) return false
        if (!samePositions(a.inputPositions, b.inputPositions, thresh)) return false
        return samePositions(a.outputPositions, b.outputPositions, thresh)
    }

    private fun sameTopology(a: Circuit, b: Circuit): Boolean {
        return a.gates.size == b.gates.size &&
            a.wires.size == b.wires.size &&
            a.outputMappings.size == b.outputMappings.size
    }

    private fun sameGates(a: Circuit, b: Circuit, thresh: Float): Boolean {
        for (i in a.gates.indices) {
            val ga = a.gates[i]
            val gb = b.gates[i]
            if (ga.instanceId != gb.instanceId || ga.chipId != gb.chipId) return false
            if (!withinJitter(ga.x, gb.x, thresh) || !withinJitter(ga.y, gb.y, thresh)) return false
        }
        return true
    }

    private fun samePositions(
        a: Map<Int, com.vayunmathur.games.logicgate.data.IoPos>,
        b: Map<Int, com.vayunmathur.games.logicgate.data.IoPos>,
        thresh: Float,
    ): Boolean {
        if (a.size != b.size) return false
        for ((k, v) in a) {
            val vb = b[k] ?: return false
            if (!withinJitter(v.x, vb.x, thresh) || !withinJitter(v.y, vb.y, thresh)) return false
        }
        return true
    }

    private fun withinJitter(a: Float, b: Float, thresh: Float): Boolean =
        kotlin.math.abs(a - b) <= thresh

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    internal suspend fun loadAllCircuits() {
        val raw = vm.dataStore.getString(LogicViewModel.KEY_CIRCUITS) ?: return
        try {
            val parsed = vm.jsonLenient.decodeFromString<AllSavedCircuits>(raw)
            parsed.map.forEach { (lvlId, pc) -> vm.allCircuits[lvlId] = pc.toCircuit() }
        } catch (_: Exception) {
            try {
                val parsedOld = kotlinx.serialization.json.Json.decodeFromString<AllSavedCircuits>(raw)
                parsedOld.map.forEach { (lvlId, pc) -> vm.allCircuits[lvlId] = pc.toCircuit() }
            } catch (_: Exception) { }
        }
    }

    internal fun saveAllCircuitsNow() {
        vm.vmScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            vm.persistMutex.withLock {
                val toSave = AllSavedCircuits(vm.allCircuits.mapValues { it.value.toPersisted() })
                val json = vm.jsonLenient.encodeToString(toSave)
                vm.dataStore.setString(LogicViewModel.KEY_CIRCUITS, json)
            }
        }
    }

    internal fun schedulePersist(delayMs: Long = 400L) {
        vm.persistJob?.cancel()
        vm.persistJob = vm.vmScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(delayMs)
            vm.persistMutex.withLock {
                val toSave = AllSavedCircuits(vm.allCircuits.mapValues { it.value.toPersisted() })
                val json = vm.jsonLenient.encodeToString(toSave)
                vm.dataStore.setString(LogicViewModel.KEY_CIRCUITS, json)
            }
        }
    }

    // ------------------------------------------------------------------
    // Placement
    // ------------------------------------------------------------------

    internal fun defaultGateX(cnt: Int): Float =
        LogicViewModel.PLACEMENT_BASE_X + (cnt % LogicViewModel.PLACEMENT_COLS) * LogicViewModel.PLACEMENT_DX

    internal fun defaultGateY(cnt: Int): Float =
        LogicViewModel.PLACEMENT_BASE_Y + (cnt / LogicViewModel.PLACEMENT_COLS) * LogicViewModel.PLACEMENT_DY

    internal fun clampCanvasX(x: Float): Float =
        x.coerceIn(LogicViewModel.CANVAS_MIN, LogicViewModel.CANVAS_MAX_X)

    internal fun clampCanvasY(y: Float): Float =
        y.coerceIn(LogicViewModel.CANVAS_MIN, LogicViewModel.CANVAS_MAX_X)

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    internal fun loadAchievementsJson(): String {
        return try {
            vm.appContext.assets.open(LogicViewModel.ACHIEVEMENTS_ASSET).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            LogicViewModel.EMPTY_ACHIEVEMENTS_JSON
        }
    }
    // ------------------------------------------------------------------
    // Wiring (see LogicWiring)
    // ------------------------------------------------------------------

    internal val wiring by lazy { LogicWiring(vm) }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    internal fun evaluateCurrent() {
        val state = vm.uiStateValue
        val lvlId = state.currentLevelId ?: return
        val level = currentLevel(lvlId) ?: return
        val circuit = state.circuit
        if (level.id == "COMPUTER") {
            evaluateComputerBranch(level, circuit)
            return
        }
        val evalResult = com.vayunmathur.games.logicgate.data.CircuitEvaluator.evaluate(level, circuit)
        val (fullyCorrect, failing) = com.vayunmathur.games.logicgate.data.CircuitEvaluator.isCorrect(level, circuit)
        when (evalResult) {
            is com.vayunmathur.games.logicgate.data.EvalResult.Error ->
                vm.updateUiState { it.copy(evalStatus = EvalStatus.Error(evalResult.message)) }
            is com.vayunmathur.games.logicgate.data.EvalResult.Cycle ->
                vm.updateUiState { it.copy(evalStatus = EvalStatus.Cycle(evalResult.ids)) }
            is com.vayunmathur.games.logicgate.data.EvalResult.Success -> {
                val total = if (level.totalInputBits <=
                    com.vayunmathur.games.logicgate.data.CircuitSampling.EXHAUSTIVE_BITS
                ) {
                    1 shl level.totalInputBits
                } else {
                    evalResult.rows.size
                }
                val passing = total - failing.size
                vm.updateUiState {
                    it.copy(evalStatus = EvalStatus.Ok(passing, total, fullyCorrect, failing))
                }
                if (fullyCorrect) onLevelWon(level)
            }
        }
    }

    private fun currentLevel(lvlId: String): LevelDef? {
        return try {
            com.vayunmathur.games.logicgate.data.Levels.get(lvlId)
        } catch (_: Exception) {
            null
        }
    }

    private fun evaluateComputerBranch(level: LevelDef, circuit: Circuit) {
        val msg = evaluateComputerLevel(circuit)
        if (msg == null) {
            val evalResult =
                com.vayunmathur.games.logicgate.data.CircuitEvaluator.evaluate(level, circuit)
            when (evalResult) {
                is com.vayunmathur.games.logicgate.data.EvalResult.Success -> {
                    vm.updateUiState {
                        it.copy(
                            evalStatus = EvalStatus.Ok(
                                passingRows = evalResult.rows.size,
                                totalRows = evalResult.rows.size,
                                isFullyCorrect = true,
                                failingRows = emptyList(),
                            ),
                        )
                    }
                    onLevelWon(level)
                }
                is com.vayunmathur.games.logicgate.data.EvalResult.Error ->
                    vm.updateUiState { it.copy(evalStatus = EvalStatus.Error(evalResult.message)) }
                is com.vayunmathur.games.logicgate.data.EvalResult.Cycle -> {
                    vm.updateUiState {
                        it.copy(
                            evalStatus = EvalStatus.Ok(
                                passingRows = 1,
                                totalRows = 1,
                                isFullyCorrect = true,
                                failingRows = emptyList(),
                            ),
                        )
                    }
                    onLevelWon(level)
                }
            }
        } else {
            vm.updateUiState { it.copy(evalStatus = EvalStatus.Error(msg)) }
        }
    }

    private fun evaluateComputerLevel(circuit: Circuit): String? {
        val ids = circuit.gates.map { it.chipId }.toSet()
        return listOfNotNull(
            missingCpuMessage(ids),
            missingRamMessage(ids),
            missingWiresMessage(circuit),
            missingBusMessage(circuit),
            missingMuxMessage(ids),
            missingSplitMessage(ids),
            missingOutputMessage(circuit),
        ).firstOrNull()
    }

    private fun missingCpuMessage(ids: Set<String>): String? {
        if (ids.contains("CPU") || ids.contains("CPU_8")) return null
        return "Add CPU bus chip – it does fetch-decode-execute from RAM. " +
            "It needs OPCODE[4] orange bus via SPLIT_4 and ADDR[8] blue bus."
    }

    private fun missingRamMessage(ids: Set<String>): String? {
        if (ids.contains("RAM_256B") || ids.contains("RAM_256")) return null
        return "Add RAM_256B Main Memory [8] – unified 256x8 where program lives " +
            "alongside data (von Neumann)."
    }

    private fun missingWiresMessage(circuit: Circuit): String? {
        if (circuit.wires.size >= LogicViewModel.MIN_CPU_RAM_WIRES) return null
        return "Wire CPU <-> RAM with BUS wires: CPU.PC[8] blue thick -> MUX_B8 A[8] " +
            "(fetch phase), CPU.ADDR_M[8] blue -> MUX_B8 B[8] (data phase). " +
            "MUX_B8 OUT[8] blue -> RAM ADDR[8]. Use bus [8] wires."
    }

    private fun missingBusMessage(circuit: Circuit): String? {
        val hasBusWire = circuit.wires.any {
            it.busWidth == com.vayunmathur.games.logicgate.data.CircuitSampling.BUS_8
        }
        if (hasBusWire) return null
        return "Use 8-bit BUS wires [8] thick blue – they cut tedium from 8 thin green " +
            "wires to 1 thick. Use JOIN_8 expander to build bus."
    }

    private fun missingMuxMessage(ids: Set<String>): String? {
        if (ids.contains("MUX_B8") || ids.contains("MUX4_B8") || ids.contains("MUX8_B8")) return null
        return "Add MUX_B8 bus address MUX – it chooses PC[8] for fetch vs ADDR_M[8] " +
            "for data – key for shared RAM program-from-RAM."
    }

    private fun missingSplitMessage(ids: Set<String>): String? {
        if (ids.contains("SPLIT_4") || ids.contains("JOIN_4")) return null
        return "Add SPLIT_4 contractor for opcode decode – " +
            "CPU needs OPCODE[4] orange bus via SPLIT_4 from RAM data."
    }

    private fun missingOutputMessage(circuit: Circuit): String? {
        if (circuit.outputMappings.isNotEmpty()) return null
        return "Connect final OUT[8] bus – drag from RAM or CPU output bus dot to output terminal."
    }

    private fun onLevelWon(level: LevelDef) {
        vm.vmScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vm.progressRepo.markCompleted(level.id)
            }
            vm.completedIdsMutable.value = vm.progressRepo.getCompletedLevelIds()
            vm.unlockedChipsMutable.value = vm.progressRepo.unlockedChipIds()
            vm.progressRepo.incCircuitsChecked()
            vm.achievementsManager.onAchievementUnlocked("first_gate")
            val allCompleted = vm.completedIdsMutable.value
            vm.achievementsManager.onProgressUpdated("all_levels", vm.progressRepo.totalCompleted())
            com.vayunmathur.games.logicgate.data.Levels.chapters.forEach { ch ->
                val cnt = allCompleted.count {
                    com.vayunmathur.games.logicgate.data.Levels.byId[it]?.chapter == ch.id
                }
                vm.achievementsManager.onProgressUpdated(chapterAchievementKey(ch.id), cnt)
            }
        }
    }

    private fun chapterAchievementKey(
        id: com.vayunmathur.games.logicgate.data.ChapterId,
    ): String = when (id) {
        com.vayunmathur.games.logicgate.data.ChapterId.FOUNDATION -> "foundation_complete"
        com.vayunmathur.games.logicgate.data.ChapterId.ROUTING -> "routing_complete"
        com.vayunmathur.games.logicgate.data.ChapterId.ARITH -> "arith_complete"
        com.vayunmathur.games.logicgate.data.ChapterId.MEMORY -> "memory_complete"
        com.vayunmathur.games.logicgate.data.ChapterId.CPU -> "cpu_complete"
    }
}
