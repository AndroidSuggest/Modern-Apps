package com.vayunmathur.games.logicgate.data

import com.vayunmathur.games.logicgate.data.CircuitEvaluator.ComboEval
import com.vayunmathur.library.log.Log

/**
 * Combo-evaluation strategies for [CircuitEvaluator].
 *
 * Split out: TooManyFunctions cap (25/class). Same module and package, so
 * internal access is unchanged; behavior identical.
 *
 * - [ComboShared]: wire-slice resolution and output reading used by both strategies.
 * - [ComboIterative]: fixed-point settling for sequential/feedback circuits.
 * - [ComboOrdered]: single topological pass for combinational circuits.
 */
internal object ComboShared {
    private const val TAG = "ComboShared"

    internal fun chipOrNull(chipId: String): ChipDef? {
        // Unknown chip ids come from stale saves; callers treat null as Unevaluated.
        @Suppress("TooGenericExceptionCaught")
        fun lookup(): ChipDef? {
            return try {
                ChipLibrary.get(chipId)
            } catch (e: Exception) {
                Log.debug(TAG, "unknown chip $chipId", e)
                null
            }
        }
        return lookup()
    }

    internal fun sliceOrBlank(src: List<Boolean>, off: Int, width: Int): List<Boolean> {
        if (off + width <= src.size) return src.slice(off until off + width)
        return List(width) { k -> src.getOrElse(off + k) { false } }
    }

    internal fun padOutputs(outVals: List<Boolean>, totalBits: Int): List<Boolean> {
        if (outVals.size < totalBits) return outVals + List(totalBits - outVals.size) { false }
        return outVals.take(totalBits)
    }

    internal fun sourceDef(circuit: Circuit, wire: Wire): ChipDef? {
        if (wire.from.instanceId.startsWith("__IN_")) return null
        val placed = circuit.gates.find { it.instanceId == wire.from.instanceId } ?: return null
        return chipOrNull(placed.chipId)
    }

    internal fun sourceDefFor(from: WireEnd, circuit: Circuit): ChipDef? {
        if (from.instanceId.startsWith("__IN_")) return null
        val placed = circuit.gates.find { it.instanceId == from.instanceId } ?: return null
        return chipOrNull(placed.chipId)
    }

    internal fun readOutputs(
        level: LevelDef,
        circuit: Circuit,
        computed: Map<String, List<Boolean>>,
    ): ComboEval {
        val outMap = circuit.outputMappings.associateBy { it.outputIndex }
        val flatOut = mutableListOf<Boolean>()
        for (oi in 0 until level.outputs.size) {
            val slice = readOneOutput(level, circuit, computed, outMap, oi)
                ?: return readOutputError(level, outMap, oi)
            flatOut.addAll(slice)
        }
        return ComboEval.Ok(flatOut)
    }

    private fun readOneOutput(
        level: LevelDef,
        circuit: Circuit,
        computed: Map<String, List<Boolean>>,
        outMap: Map<Int, OutputMapping>,
        oi: Int,
    ): List<Boolean>? {
        val map = outMap[oi] ?: return null
        val srcVals = computed[map.from.instanceId] ?: return null
        val expectedW = level.outputWidth(oi)
        val srcDef = sourceDefFor(map.from, circuit)
        val srcOff = srcDef?.outputPinBitOffset(map.from.pinIndex) ?: 0
        return sliceOrBlank(srcVals, srcOff, expectedW)
    }

    private fun readOutputError(
        level: LevelDef,
        outMap: Map<Int, OutputMapping>,
        oi: Int,
    ): ComboEval {
        val map = outMap[oi]
        if (map == null) {
            return ComboEval.Err(
                "Output ${level.outputs[oi]} not connected – " +
                    "drag from gate output dot to output terminal pill",
            )
        }
        return ComboEval.Err("Output ${level.outputs[oi]} source not computed")
    }
}

internal object ComboIterative {
    private const val TAG = "ComboIterative"
    internal fun eval(level: LevelDef, circuit: Circuit, flatInputs: List<Boolean>): ComboEval {
        val computed = mutableMapOf<String, List<Boolean>>()
        seedInputs(level, flatInputs, computed)
        seedGatesBlank(circuit, computed)
        val incoming = buildIncoming(circuit)
        settleGates(circuit, computed, incoming)
        return ComboShared.readOutputs(level, circuit, computed)
    }

    internal fun seedInputs(
        level: LevelDef,
        flatInputs: List<Boolean>,
        computed: MutableMap<String, List<Boolean>>,
    ) {
        for (idx in level.inputs.indices) {
            val off = level.inputBitOffset(idx)
            val w = level.inputWidth(idx)
            computed["__IN_$idx"] = ComboShared.sliceOrBlank(flatInputs, off, w)
        }
    }

    private fun seedGatesBlank(circuit: Circuit, computed: MutableMap<String, List<Boolean>>) {
        for (g in circuit.gates) {
            val def = ComboShared.chipOrNull(g.chipId) ?: continue
            computed[g.instanceId] = List(def.totalOutputBits) { false }
        }
    }

    private fun buildIncoming(circuit: Circuit): Map<String, MutableMap<Int, Wire>> {
        val incoming = mutableMapOf<String, MutableMap<Int, Wire>>()
        circuit.gates.forEach { g -> incoming[g.instanceId] = mutableMapOf() }
        for (w in circuit.wires) {
            val dst = w.to.instanceId
            if (dst.startsWith("__OUT_")) continue
            incoming[dst]?.put(w.to.pinIndex, w)
        }
        return incoming
    }

    private fun settleGates(
        circuit: Circuit,
        computed: MutableMap<String, List<Boolean>>,
        incoming: Map<String, MutableMap<Int, Wire>>,
    ) {
        var changed: Boolean
        var iters = 0
        do {
            changed = false
            iters++
            for (placed in circuit.gates) {
                if (settleOneGate(circuit, placed, computed, incoming)) changed = true
            }
        } while (changed && iters < CircuitSampling.MAX_SETTLE_ITERS)
    }

    private fun settleOneGate(
        circuit: Circuit,
        placed: PlacedChip,
        computed: MutableMap<String, List<Boolean>>,
        incoming: Map<String, MutableMap<Int, Wire>>,
    ): Boolean {
        val def = ComboShared.chipOrNull(placed.chipId) ?: return false
        val flatIn = MutableList(def.totalInputBits) { false }
        if (!gatherGateInputs(circuit, placed, def, computed, incoming, flatIn)) return false
        // A throwing chip eval is a skipped update here; the fixed-point loop
        // converges around it and readOutputs reports what never settled.
        @Suppress("TooGenericExceptionCaught")
        fun evalLenient(): List<Boolean>? {
            return try {
                def.eval(flatIn)
            } catch (e: Exception) {
                Log.debug(TAG, "chip ${def.id} eval failed; skipping update", e)
                null
            }
        }
        val outVals = evalLenient() ?: return false
        val padded = ComboShared.padOutputs(outVals, def.totalOutputBits)
        if (computed[placed.instanceId] == padded) return false
        computed[placed.instanceId] = padded
        return true
    }

    private fun gatherGateInputs(
        circuit: Circuit,
        placed: PlacedChip,
        def: ChipDef,
        computed: Map<String, List<Boolean>>,
        incoming: Map<String, MutableMap<Int, Wire>>,
        flatIn: MutableList<Boolean>,
    ): Boolean {
        for (pin in 0 until def.inputCount) {
            if (!gatherOnePin(circuit, placed, def, computed, incoming, flatIn, pin)) return false
        }
        return true
    }

    private fun gatherOnePin(
        circuit: Circuit,
        placed: PlacedChip,
        def: ChipDef,
        computed: Map<String, List<Boolean>>,
        incoming: Map<String, MutableMap<Int, Wire>>,
        flatIn: MutableList<Boolean>,
        pin: Int,
    ): Boolean {
        val wire = incoming[placed.instanceId]?.get(pin) ?: return false
        val srcVals = computed[wire.from.instanceId] ?: return false
        val slice = resolveSourceSlice(circuit, wire, srcVals)
        val dstOff = def.inputPinBitOffset(pin)
        for (k in slice.indices) {
            if (dstOff + k < flatIn.size) flatIn[dstOff + k] = slice[k]
        }
        return true
    }

    private fun resolveSourceSlice(
        circuit: Circuit,
        wire: Wire,
        srcVals: List<Boolean>,
    ): List<Boolean> {
        val srcDef = ComboShared.sourceDef(circuit, wire)
        val srcOff = srcDef?.outputPinBitOffset(wire.from.pinIndex) ?: 0
        return ComboShared.sliceOrBlank(srcVals, srcOff, wire.busWidth)
    }
}

internal object ComboOrdered {
    internal class ComboGraph(
        val deps: Map<String, Set<String>>,
        val incoming: Map<String, Map<Int, Wire>>,
    )

    internal fun eval(
        level: LevelDef,
        circuit: Circuit,
        order: List<String>,
        graph: ComboGraph,
        computed: MutableMap<String, List<Boolean>>,
    ): ComboEval {
        for (gid in order) {
            val step = evalOrderedGate(level, circuit, computed, graph.incoming, gid) ?: continue
            if (step is ComboEval.Err || step is ComboEval.Cycle) return step
        }
        return ComboShared.readOutputs(level, circuit, computed)
    }

    internal fun buildGraph(circuit: Circuit): ComboGraph {
        val deps = mutableMapOf<String, MutableSet<String>>()
        val incoming = mutableMapOf<String, MutableMap<Int, Wire>>()
        circuit.gates.forEach { g ->
            deps[g.instanceId] = mutableSetOf()
            incoming[g.instanceId] = mutableMapOf()
        }
        for (w in circuit.wires) {
            val dst = w.to.instanceId
            if (dst.startsWith("__OUT_")) continue
            if (incoming.containsKey(dst)) {
                incoming[dst]!![w.to.pinIndex] = w
                if (!w.from.instanceId.startsWith("__IN_")) deps[dst]?.add(w.from.instanceId)
            }
        }
        return ComboGraph(deps, incoming)
    }

    /**
     * Evaluates one gate in topological order. Returns null to advance,
     * or a terminal [ComboEval] to stop the whole combo.
     */
    private fun evalOrderedGate(
        level: LevelDef,
        circuit: Circuit,
        computed: MutableMap<String, List<Boolean>>,
        incoming: Map<String, Map<Int, Wire>>,
        gid: String,
    ): ComboEval? {
        val placed = circuit.gates.find { it.instanceId == gid } ?: return null
        val def = ChipLibrary.get(placed.chipId)
        val flatIn = MutableList(def.totalInputBits) { false }
        for (pin in 0 until def.inputCount) {
            val err = gatherComboPin(level, circuit, computed, incoming, def, flatIn, gid, pin)
            if (err != null) return err
        }
        // Chip eval lambdas are arbitrary code; any failure is an error row, not a crash.
        @Suppress("TooGenericExceptionCaught")
        fun evalLenient(): ComboEval? {
            return try {
                computed[gid] = ComboShared.padOutputs(def.eval(flatIn), def.totalOutputBits)
                null
            } catch (e: Exception) {
                ComboEval.Err("Eval ${def.id}: ${e.message}")
            }
        }
        return evalLenient()
    }

    private fun gatherComboPin(
        level: LevelDef,
        circuit: Circuit,
        computed: Map<String, List<Boolean>>,
        incoming: Map<String, Map<Int, Wire>>,
        def: ChipDef,
        flatIn: MutableList<Boolean>,
        gid: String,
        pin: Int,
    ): ComboEval.Err? {
        val wire = incoming[gid]?.get(pin) ?: return pinError(def, pin, "unconnected")
        val need = def.inputPinWidth(pin)
        if (wire.busWidth != need) return ComboEval.Err(widthHint(def, pin, need, wire.busWidth))
        val srcVals = computed[wire.from.instanceId]
            ?: return ComboEval.Err("Source ${wire.from.instanceId} not computed (cycle?)")
        checkSourceWidth(level, circuit, wire)?.let { return it }
        val srcDef = ComboShared.sourceDef(circuit, wire)
        val srcOff = srcDef?.outputPinBitOffset(wire.from.pinIndex) ?: 0
        val slice = ComboShared.sliceOrBlank(srcVals, srcOff, wire.busWidth)
        copySlice(flatIn, def.inputPinBitOffset(pin), slice)
        return null
    }

    private fun pinError(def: ChipDef, pin: Int, reason: String): ComboEval.Err {
        val label = def.inputs.getOrElse(pin) { "in$pin" }
        return ComboEval.Err(
            "Gate ${def.displayName}[$label] $reason – " +
                "${def.inputs.size} pins need wires",
        )
    }

    private fun widthHint(def: ChipDef, pin: Int, need: Int, got: Int): String {
        val hint = when {
            need == CircuitSampling.BUS_8 && got == CircuitSampling.BIT ->
                "JOIN_8 expander (8 bits → BUS8[8] thick blue)"
            need == CircuitSampling.BIT && got == CircuitSampling.BUS_8 ->
                "SPLIT_8 contractor (BUS8 → bits)"
            need == CircuitSampling.BUS_4 && got == CircuitSampling.BIT ->
                "JOIN_4 (4 bits → BUS4[4] orange)"
            need == CircuitSampling.BIT && got == CircuitSampling.BUS_4 ->
                "SPLIT_4 (BUS4 → bits)"
            else -> "JOIN/SPLIT expander/contractor to convert"
        }
        return "Width mismatch ${def.displayName}.${def.inputs[pin]}: " +
            "need ${need}b got ${got}b. Use $hint."
    }

    private fun checkSourceWidth(
        level: LevelDef,
        circuit: Circuit,
        wire: Wire,
    ): ComboEval.Err? {
        val srcDef = ComboShared.sourceDef(circuit, wire)
        val srcW = srcDef?.outputPinWidth(wire.from.pinIndex) ?: inputSourceWidth(level, wire)
        if (srcW == wire.busWidth) return null
        return ComboEval.Err(
            "Src width mismatch ${wire.from.instanceId}[${wire.from.pinIndex}] " +
                "is ${srcW}b vs wire ${wire.busWidth}b",
        )
    }

    private fun inputSourceWidth(level: LevelDef, wire: Wire): Int {
        val ti = wire.from.instanceId.removePrefix("__IN_").toIntOrNull() ?: -1
        if (ti >= 0) return level.inputWidth(ti)
        return CircuitSampling.BIT
    }

    private fun copySlice(flatIn: MutableList<Boolean>, dstOff: Int, slice: List<Boolean>) {
        for (k in slice.indices) {
            if (dstOff + k < flatIn.size) flatIn[dstOff + k] = slice[k]
        }
    }
}
