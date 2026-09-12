package com.vayunmathur.games.logicgate.ui

import com.vayunmathur.games.logicgate.data.ChipCategory
import com.vayunmathur.games.logicgate.data.LevelDef

enum class ChipGroup { BIT, WORD, CUSTOM }

internal fun groupForCategory(cat: ChipCategory): ChipGroup = when (cat) {
    ChipCategory.PRIMITIVE, ChipCategory.FOUNDATION, ChipCategory.ROUTING -> ChipGroup.BIT
    ChipCategory.BUS, ChipCategory.ARITH -> ChipGroup.WORD
    ChipCategory.MEMORY, ChipCategory.CPU -> ChipGroup.CUSTOM
}

fun displayInputLabel(level: LevelDef, idx: Int): String {
    val raw = level.inputs.getOrNull(idx) ?: return ""
    val low = raw.lowercase()
    return when (low) {
        "a" -> "Input 1"
        "b" -> "Input 2"
        "c" -> "Input 3"
        "d" -> "Input 4"
        "e" -> "Input 5"
        "f" -> "Input 6"
        "g" -> "Input 7"
        "h" -> "Input 8"
        "in" -> "Input"
        "sel", "select" -> "Select"
        "sel0", "select0", "s0" -> "Select 0"
        "sel1", "select1", "s1" -> "Select 1"
        "sel2", "select2", "s2" -> "Select 2"
        "opcode" -> "Opcode"
        else -> {
            if (low.length == 1 && low[0] in 'a'..'h') "Input ${low[0] - 'a' + 1}"
            else raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

fun displayOutputLabel(level: LevelDef, idx: Int): String {
    val raw = level.outputs.getOrNull(idx) ?: return ""
    val low = raw.lowercase()
    return when {
        low == "out" || low == "output" -> if (level.outputs.size == 1) "Output" else "Output ${idx + 1}"
        low == "sum" -> "Sum"
        low in listOf("carry", "cout", "borrow") -> "Carry"
        low == "q" -> "Q"
        low == "nq" -> "nQ"
        else -> raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

data class TableRowUi(
    val inputSlices: Map<Int, List<Boolean>>,
    val desiredSlices: Map<Int, List<Boolean>>,
    val actualSlices: Map<Int, List<Boolean>>?
)
