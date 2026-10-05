package com.vayunmathur.games.logicgate.data

/**
 * Bus-aware circuit: Wire.busWidth 1=thin bit, 4=orange nibble bus, 8=blue byte bus.
 * JOIN_8 = Expander bits->BUS8 (0 cost, less tedious), SPLIT_8 = Contractor BUS8->bits.
 * Level I/O terminals also have widths: ADDER_8B uses A[8], B[8] not 16 thin wires.
 */

data class PlacedChip(val instanceId: String, val chipId: String, val x: Float = 0f, val y: Float = 0f)
data class WireEnd(val instanceId: String, val pinIndex: Int)
data class Wire(val id: String, val from: WireEnd, val to: WireEnd, val busWidth: Int = 1)
data class OutputMapping(val outputIndex: Int, val from: WireEnd)
data class IoPos(val x: Float, val y: Float)

data class Circuit(
    val gates: List<PlacedChip> = emptyList(),
    val wires: List<Wire> = emptyList(),
    val outputMappings: List<OutputMapping> = emptyList(),
    val inputPositions: Map<Int, IoPos> = emptyMap(),
    val outputPositions: Map<Int, IoPos> = emptyMap()
) { fun totalNandCost(): Int = gates.sumOf { ChipLibrary.get(it.chipId).nandCost } }

sealed class EvalResult {
    data class Success(val rows: List<List<Boolean>>) : EvalResult()
    data class Error(val message: String) : EvalResult()
    data class Cycle(val ids: List<String>) : EvalResult()
}

object CircuitEvaluator {
    internal sealed class ComboEval {
        data class Ok(val outputs: List<Boolean>) : ComboEval()
        data class Err(val msg: String) : ComboEval()
        data class Cycle(val ids: List<String>) : ComboEval()
    }

    fun evaluate(level: LevelDef, circuit: Circuit): EvalResult {
        if (isMemoryLevel(level) || containsSequentialGate(circuit)) {
            return evaluateIterativeFull(level, circuit)
        }
        val bits = level.totalInputBits
        val quick = if (bits == 0) emptyList() else List(bits) { false }
        val qc = evalCombo(level, circuit, quick)
        if (qc is ComboEval.Err) return EvalResult.Error(qc.msg)
        if (qc is ComboEval.Cycle) {
            return evaluateIterativeFull(level, circuit)
        }
        val res = mutableListOf<List<Boolean>>()
        for (c in CircuitSampling.comboIndices(bits)) {
            val flat = CircuitSampling.decodeCombo(c, bits)
            when (val r = evalCombo(level, circuit, flat)) {
                is ComboEval.Ok -> res.add(r.outputs)
                is ComboEval.Err -> return EvalResult.Error("Row ${formatCombo(flat)}: ${r.msg}")
                is ComboEval.Cycle -> return evaluateIterativeFull(level, circuit)
            }
        }
        return EvalResult.Success(res)
    }

    private fun evaluateIterativeFull(level: LevelDef, circuit: Circuit): EvalResult {
        val bits = level.totalInputBits
        val res = mutableListOf<List<Boolean>>()
        for (c in CircuitSampling.comboIndices(bits)) {
            val flat = CircuitSampling.decodeCombo(c, bits)
            when (val r = evalComboIterative(level, circuit, flat)) {
                is ComboEval.Ok -> res.add(r.outputs)
                is ComboEval.Err -> return EvalResult.Error("Row ${formatCombo(flat)}: ${r.msg}")
                is ComboEval.Cycle -> return EvalResult.Cycle(r.ids)
            }
        }
        return EvalResult.Success(res)
    }

    fun evaluateFullCorrectness(level: LevelDef, circuit: Circuit): Pair<Boolean, List<Int>> {
        // SR latch structural pass – 2 NOR cross-coupled = first memory, feedback cycle required
        if (level.id == "SR_LATCH" && isSrLatchStructureValid(circuit)) return true to emptyList()
        val target = ComboShared.chipOrNull(level.targetChipId) ?: return false to emptyList()
        val inBits = level.totalInputBits
        val outBits = level.totalOutputBits
        val vectors: List<List<Boolean>> = CircuitSampling.vectors(inBits)
        val expected = vectors.map { target.eval(it).take(outBits) }
        val actual = mutableListOf<List<Boolean>>()
        for (vec in vectors) {
            when (val r = evalComboWithMemoryFallback(level, circuit, vec)) {
                is ComboEval.Ok -> actual.add(r.outputs)
                else -> return false to listOf(0)
            }
        }
        return (collectFailing(level, vectors, expected, actual).isEmpty()) to
            collectFailing(level, vectors, expected, actual)
    }

    private fun collectFailing(
        level: LevelDef,
        vectors: List<List<Boolean>>,
        expected: List<List<Boolean>>,
        actual: List<List<Boolean>>,
    ): List<Int> {
        val failing = mutableListOf<Int>()
        for (i in expected.indices) {
            collectOneFailure(level, vectors, expected, actual, i)?.let { failing.add(it) }
        }
        return failing
    }

    private fun collectOneFailure(
        level: LevelDef,
        vectors: List<List<Boolean>>,
        expected: List<List<Boolean>>,
        actual: List<List<Boolean>>,
        i: Int,
    ): Int? {
        if (i >= actual.size) return i
        if (isHoldRow(level, vectors[i])) return null
        if (expected[i] != actual[i]) return i
        return null
    }

    private fun isHoldRow(level: LevelDef, inp: List<Boolean>): Boolean {
        if (level.id != "SR_LATCH") return false
        val s = inp.getOrElse(0) { false }
        val r = inp.getOrElse(1) { false }
        return !s && !r
    }

    fun isCorrect(level: LevelDef, circuit: Circuit): Pair<Boolean, List<Int>> = evaluateFullCorrectness(level, circuit)

    private fun isMemoryLevel(level: LevelDef): Boolean = level.chapter == ChapterId.MEMORY

    private fun containsSequentialGate(circuit: Circuit): Boolean {
        return circuit.gates.any { ComboShared.chipOrNull(it.chipId)?.isSequential == true }
    }

    private fun isSrLatchStructureValid(circuit: Circuit): Boolean {
        val norGates = circuit.gates.filter { it.chipId == "NOR" }
        if (norGates.size < 2) return false
        val ids = norGates.map { it.instanceId }.toSet()
        val crossWires = circuit.wires.filter {
            it.from.instanceId in ids && it.to.instanceId in ids && it.from.instanceId != it.to.instanceId
        }
        if (crossWires.size < 2) return false
        val fromTo = crossWires.map { it.from.instanceId to it.to.instanceId }.toSet()
        val hasBothDirections = norGates.any { a ->
            norGates.any { b ->
                a.instanceId != b.instanceId &&
                    (a.instanceId to b.instanceId) in fromTo &&
                    (b.instanceId to a.instanceId) in fromTo
            }
        }
        if (!hasBothDirections) return false
        val inputWires = circuit.wires.filter {
            it.from.instanceId.startsWith("__IN_") && it.to.instanceId in ids
        }
        if (inputWires.size < 2) return false
        if (circuit.outputMappings.size < 2) return false
        return true
    }

    private fun evalComboWithMemoryFallback(level: LevelDef, circuit: Circuit, flatInputs: List<Boolean>): ComboEval {
        val r = evalCombo(level, circuit, flatInputs)
        return if (r is ComboEval.Cycle && (isMemoryLevel(level) || containsSequentialGate(circuit))) {
            evalComboIterative(level, circuit, flatInputs)
        } else r
    }

    private fun evalComboIterative(
        level: LevelDef,
        circuit: Circuit,
        flatInputs: List<Boolean>,
    ): ComboEval = ComboIterative.eval(level, circuit, flatInputs)

    private fun evalCombo(level: LevelDef, circuit: Circuit, flatInputs: List<Boolean>): ComboEval {
        val computed = mutableMapOf<String, List<Boolean>>()
        ComboIterative.seedInputs(level, flatInputs, computed)
        val graph = ComboOrdered.buildGraph(circuit)
        val order = topoSort(circuit.gates.map { it.instanceId }, graph.deps)
            ?: return ComboEval.Cycle(circuit.gates.map { it.instanceId })
        return ComboOrdered.eval(level, circuit, order, graph, computed)
    }

    private fun topoSort(nodes: List<String>, deps: Map<String, Set<String>>): List<String>? {
        val inDegree = mutableMapOf<String, Int>()
        nodes.forEach { inDegree[it] = deps[it]?.size ?: 0 }
        val outEdges = mutableMapOf<String, MutableList<String>>()
        nodes.forEach { outEdges[it] = mutableListOf() }
        deps.forEach { (node, depSet) ->
            depSet.forEach { dep -> outEdges.getOrPut(dep) { mutableListOf() }.add(node) }
        }
        val q = ArrayDeque<String>()
        inDegree.filter { it.value == 0 }.keys.forEach { q.add(it) }
        val order = mutableListOf<String>()
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            order.add(n)
            outEdges[n]?.forEach { m ->
                inDegree[m] = (inDegree[m] ?: 1) - 1
                if (inDegree[m] == 0) q.add(m)
            }
        }
        return if (order.size == nodes.size) order else null
    }

    // Shared sampling util — same sequence used by evaluator and TruthTableView.
    // Previously TruthTableView used a different seed/pattern vs the evaluator,
    // so failingRows indices mismatched the display.
    fun generateSampleVectors(
        totalInputBits: Int,
        limit: Int = CircuitSampling.SAMPLE_LIMIT,
    ): List<List<Boolean>> {
        return CircuitSampling.vectors(totalInputBits, limit)
    }

    fun generateTruthTable(
        target: ChipDef,
        inputBits: Int,
        outputBits: Int,
        limit: Int = CircuitSampling.SAMPLE_LIMIT,
    ): List<List<Boolean>> {
        val exhaustive = CircuitSampling.EXHAUSTIVE_BITS
        val total = if (inputBits <= exhaustive) {
            1 shl inputBits
        } else {
            minOf(limit, 1 shl minOf(inputBits, CircuitSampling.HIGH_BIT_COUNT))
        }
        return if (inputBits <= exhaustive) {
            (0 until total).map { c ->
                val inp = List(inputBits) { i -> ((c shr i) and 1) == 1 }
                target.eval(inp).take(outputBits)
            }
        } else {
            val vecs = generateSampleVectors(inputBits, minOf(limit, total))
            vecs.map { inp -> target.eval(inp).take(outputBits) }
        }
    }

    fun formatCombo(bits: List<Boolean>): String = bits.joinToString("") { if (it) "1" else "0" }
}
