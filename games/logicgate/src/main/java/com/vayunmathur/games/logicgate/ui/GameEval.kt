package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.CircuitEvaluator
import com.vayunmathur.games.logicgate.data.EvalResult
import com.vayunmathur.games.logicgate.data.LevelDef
import com.vayunmathur.games.logicgate.platform.UiState

/** All input vectors shown in the testbench: exhaustive up to 10 bits, seeded samples beyond. */
@Composable
internal fun rememberInputVectors(totalInBits: Int, displayLimit: Int, levelId: String): List<List<Boolean>> =
    remember(totalInBits, displayLimit, levelId) {
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

internal data class DerivedEval(
    val inputSlices: Map<Int, List<Boolean>>,
    val inputOn: Map<Int, Boolean>,
    val inputDecimals: Map<Int, Int>,
    val desiredSlices: Map<Int, List<Boolean>>,
    val desiredDecimals: Map<Int, Int>,
    val actualSlices: Map<Int, List<Boolean>>,
    val actualDecimals: Map<Int, Int>,
    val evalRows: List<List<Boolean>>?,
)

@Composable
internal fun rememberDerivedEval(
    level: LevelDef,
    state: UiState,
    effectiveFlat: List<Boolean>,
): DerivedEval {
    val inputSlicesMap: Map<Int, List<Boolean>> = remember(effectiveFlat, level) {
        level.inputs.indices.associateWith { idx ->
            val off = level.inputBitOffset(idx); val wd = level.inputWidth(idx)
            if (off + wd <= effectiveFlat.size) effectiveFlat.slice(off until off + wd) else List(wd) { false }
        }
    }
    val inputOnMap: Map<Int, Boolean> = remember(effectiveFlat, level) {
        level.inputs.indices.associateWith { idx ->
            val bits = inputSlicesMap[idx] ?: listOf(false)
            bits.any { it }
        }
    }
    val inputDecimals: Map<Int, Int> = remember(effectiveFlat, level) {
        level.inputs.indices.associateWith { idx ->
            val off = level.inputBitOffset(idx); val wd = level.inputWidth(idx)
            val slice = if (off + wd <= effectiveFlat.size) effectiveFlat.slice(off until off + wd) else List(wd) { false }
            ChipLibrary.bitsToInt(slice)
        }
    }
    val targetDef = remember(level.targetChipId) { try { ChipLibrary.get(level.targetChipId) } catch (_: Exception) { null } }
    val desiredFlatOut: List<Boolean> = remember(effectiveFlat, targetDef, level) {
        targetDef?.eval(effectiveFlat)?.take(level.totalOutputBits) ?: emptyList()
    }
    val desiredBitSlices: Map<Int, List<Boolean>> = remember(desiredFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
            if (off + wd <= desiredFlatOut.size) desiredFlatOut.slice(off until off + wd) else List(wd) { false }
        }
    }
    val desiredDecimals: Map<Int, Int> = remember(desiredFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
            val slice = if (off + wd <= desiredFlatOut.size) desiredFlatOut.slice(off until off + wd) else List(wd) { false }
            ChipLibrary.bitsToInt(slice)
        }
    }
    val actualEvalRows: List<List<Boolean>>? = remember(state.circuit, level) {
        val res = CircuitEvaluator.evaluate(level, state.circuit)
        if (res is EvalResult.Success) res.rows else null
    }
    return DerivedEval(inputSlicesMap, inputOnMap, inputDecimals, desiredBitSlices, desiredDecimals, emptyMap(), emptyMap(), actualEvalRows)
}

@Composable
internal fun rememberActualSlices(
    level: LevelDef,
    evalRows: List<List<Boolean>>?,
    selectedIdx: Int,
    liveFlatInputs: List<Boolean>?,
    inputVectors: List<List<Boolean>>,
): Pair<Map<Int, List<Boolean>>, Map<Int, Int>> {
    val actualFlatOut: List<Boolean> = remember(evalRows, selectedIdx, liveFlatInputs) {
        if (liveFlatInputs != null) {
            val foundIdx = inputVectors.indexOfFirst { it == liveFlatInputs }
            if (foundIdx >= 0) evalRows?.getOrNull(foundIdx) ?: emptyList() else evalRows?.getOrNull(selectedIdx) ?: emptyList()
        } else evalRows?.getOrNull(selectedIdx) ?: emptyList()
    }
    val actualBitSlices: Map<Int, List<Boolean>> = remember(actualFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
            if (off + wd <= actualFlatOut.size) actualFlatOut.slice(off until off + wd) else List(wd) { false }
        }
    }
    val actualDecimals: Map<Int, Int> = remember(actualFlatOut, level) {
        level.outputs.indices.associateWith { oi ->
            val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
            val slice = if (off + wd <= actualFlatOut.size) actualFlatOut.slice(off until off + wd) else List(wd) { false }
            ChipLibrary.bitsToInt(slice)
        }
    }
    return actualBitSlices to actualDecimals
}

@Composable
internal fun rememberTableRows(
    inputVectors: List<List<Boolean>>,
    level: LevelDef,
    actualEvalRows: List<List<Boolean>>?,
): List<TableRowUi> {
    val targetDef = remember(level.targetChipId) { try { ChipLibrary.get(level.targetChipId) } catch (_: Exception) { null } }
    return remember(inputVectors, level, targetDef, actualEvalRows) {
        inputVectors.mapIndexed { rowIdx, flat ->
            val inSlices = level.inputs.indices.associateWith { i ->
                val off = level.inputBitOffset(i); val wd = level.inputWidth(i)
                if (off + wd <= flat.size) flat.slice(off until off + wd) else List(wd) { false }
            }
            val desiredFlat = targetDef?.eval(flat)?.take(level.totalOutputBits) ?: emptyList()
            val desSlices = level.outputs.indices.associateWith { oi ->
                val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
                if (off + wd <= desiredFlat.size) desiredFlat.slice(off until off + wd) else List(wd) { false }
            }
            val actualFlat = actualEvalRows?.getOrNull(rowIdx)
            val actSlices = actualFlat?.let { af ->
                level.outputs.indices.associateWith { oi ->
                    val off = level.outputBitOffset(oi); val wd = level.outputWidth(oi)
                    if (off + wd <= af.size) af.slice(off until off + wd) else List(wd) { false }
                }
            }
            TableRowUi(inSlices, desSlices, actSlices)
        }
    }
}
