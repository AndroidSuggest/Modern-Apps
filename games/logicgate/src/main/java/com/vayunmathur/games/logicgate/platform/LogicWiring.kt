package com.vayunmathur.games.logicgate.platform

import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.Circuit
import com.vayunmathur.games.logicgate.data.CircuitSampling
import com.vayunmathur.games.logicgate.data.LevelDef
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.data.OutputMapping
import com.vayunmathur.games.logicgate.data.Wire
import com.vayunmathur.games.logicgate.data.WireEnd
import kotlin.uuid.Uuid

/**
 * Wire creation for [LogicViewModel].
 *
 * Split out: TooManyFunctions cap (25/class). Same module and package, so
 * internal access is unchanged; behavior identical.
 */
internal class LogicWiring internal constructor(private val vm: LogicViewModel) {
    internal fun createWire(from: WireEnd, to: WireEnd) {
        val s = vm.uiStateValue.circuit
        val level = currentLevel()
        if (from.instanceId == to.instanceId) {
            clearWiring()
            return
        }

        // v2 bidirectional: auto-orient input<->output
        val oriented = orientEnds(from, to) ?: run {
            clearWiring()
            return
        }
        val (realFrom, realTo) = oriented

        val srcW = outputPinWidth(realFrom, level)
        val dstW = inputPinWidth(realTo, level)

        if (srcW != dstW) {
            vm.updateUiState {
                it.copy(
                    evalStatus = EvalStatus.Error(widthMismatchMessage(srcW, dstW)),
                    wiringFrom = null,
                    dragGhostLineEnd = null,
                )
            }
            return
        }

        vm.engine.pushHistory(s)
        attachWire(s, realFrom, realTo, srcW)
        clearWiring()
    }

    private fun outputPinWidth(end: WireEnd, level: LevelDef?): Int {
        if (end.instanceId.startsWith("__IN_")) {
            val idx = end.instanceId.removePrefix("__IN_").toIntOrNull() ?: return 1
            return level?.inputWidth(idx) ?: 1
        }
        val gate = vm.uiStateValue.circuit.gates.find { it.instanceId == end.instanceId } ?: return 1
        return ChipLibrary.get(gate.chipId).outputPinWidth(end.pinIndex)
    }

    private fun inputPinWidth(end: WireEnd, level: LevelDef?): Int {
        if (end.instanceId.startsWith("__OUT_")) {
            val idx = end.instanceId.removePrefix("__OUT_").toIntOrNull() ?: return 1
            return level?.outputWidth(idx) ?: 1
        }
        val gate = vm.uiStateValue.circuit.gates.find { it.instanceId == end.instanceId } ?: return 1
        return ChipLibrary.get(gate.chipId).inputPinWidth(end.pinIndex)
    }

    private fun currentLevel(): LevelDef? {
        val lvlId = vm.uiStateValue.currentLevelId ?: return null
        return try {
            Levels.get(lvlId)
        } catch (_: Exception) {
            null
        }
    }

    private fun clearWiring() {
        vm.updateUiState { it.copy(wiringFrom = null, dragGhostLineEnd = null) }
    }

    private fun orientEnds(from: WireEnd, to: WireEnd): Pair<WireEnd, WireEnd>? {
        val srcIsOut = isOutputEnd(from)
        val dstIsIn = isInputEnd(to)
        val srcIsIn = isInputEnd(from)
        val dstIsOut = isOutputEnd(to)
        return when {
            srcIsOut && dstIsIn -> from to to
            srcIsIn && dstIsOut -> to to from
            else -> null
        }
    }

    private fun widthMismatchMessage(srcW: Int, dstW: Int): String {
        val bus8 = CircuitSampling.BUS_8
        val bus4 = CircuitSampling.BUS_4
        val bit = CircuitSampling.BIT
        return when {
            srcW == bus8 && dstW == bit ->
                "Cannot wire BUS8[8] thick blue directly to 1-bit pin – " +
                    "use SPLIT_8 contractor to split BUS8 -> 8 bits, or use bus target."
            dstW == bus8 && srcW == bit ->
                "Cannot wire 1-bit thin green into BUS8[8] thick blue – " +
                    "use JOIN_8 expander (bits->BUS8[8]) to combine 8 bits into one bus wire (less tedious)."
            srcW == bus4 && dstW == bit ->
                "BUS4[4] orange thick -> bit: use SPLIT_4 contractor."
            dstW == bus4 && srcW == bit ->
                "Bit -> BUS4[4] orange: use JOIN_4 expander."
            else -> "Width mismatch: source ${srcW}b vs sink ${dstW}b. " +
                "Use JOIN/SPLIT expander/contractor to convert between bus and bit."
        }
    }

    private fun attachWire(s: Circuit, realFrom: WireEnd, realTo: WireEnd, srcW: Int) {
        if (realTo.instanceId.startsWith("__OUT_")) {
            val outIdx = realTo.instanceId.removePrefix("__OUT_").toIntOrNull() ?: return
            val existing = s.outputMappings.filterNot { it.outputIndex == outIdx }
            val newMap = existing + OutputMapping(outIdx, realFrom)
            vm.updateCircuit(s.copy(outputMappings = newMap))
        } else {
            val existingWires = s.wires.filterNot { it.to == realTo }
            val newWire = Wire(
                id = "W_${Uuid.random().toString().take(LogicViewModel.WIRE_ID_SUFFIX)}",
                from = realFrom,
                to = realTo,
                busWidth = srcW,
            )
            vm.updateCircuit(s.copy(wires = existingWires + newWire))
        }
    }

    private fun isOutputEnd(end: WireEnd): Boolean {
        if (end.instanceId.startsWith("__IN_")) return true
        if (end.instanceId.startsWith("__OUT_")) return false
        val gate = vm.uiStateValue.circuit.gates.find {
            it.instanceId == end.instanceId
        } ?: return false
        val def = ChipLibrary.get(gate.chipId)
        return end.pinIndex in 0 until def.outputCount
    }

    private fun isInputEnd(end: WireEnd): Boolean {
        if (end.instanceId.startsWith("__OUT_")) return true
        if (end.instanceId.startsWith("__IN_")) return false
        val gate = vm.uiStateValue.circuit.gates.find {
            it.instanceId == end.instanceId
        } ?: return false
        val def = ChipLibrary.get(gate.chipId)
        return end.pinIndex in 0 until def.inputCount
    }
}
