package com.vayunmathur.calculator.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel

/** One plotted curve on the graph screen — Cartesian `y = f(x)` or polar `r = f(θ)`. */
data class GraphFunction(
    val id: Long,
    val text: String = "",
    val color: Color,
    val enabled: Boolean = true,
    val polar: Boolean = false,
)

/** A point found by graph analysis (root, extremum, intersection…), drawn + listed. */
data class AnalysisPoint(
    val x: Double,
    val y: Double,
    val label: String,
    val color: Color,
)

/** The kinds of numeric analysis the graph screen can run over the visible window. */
enum class AnalysisKind(val title: String) {
    ROOTS("Roots (x-intercepts)"),
    MINIMA("Minima"),
    MAXIMA("Maxima"),
    INTERSECTIONS("Intersections"),
    Y_INTERCEPT("y-intercepts"),
}

/**
 * Holds all calculator state at the Activity scope so it survives switching between the
 * Calculator and Graph tabs (which reset the nav back stack).
 */
class CalculatorViewModel : ViewModel() {

    // ---- Shared ----
    var angleMode by mutableStateOf(AngleMode.RADIANS)
        private set

    fun toggleAngleMode() {
        angleMode = if (angleMode == AngleMode.RADIANS) AngleMode.DEGREES else AngleMode.RADIANS
    }

    // ---- Calculator tab ----
    var input by mutableStateOf("")
        private set

    /** Live-evaluated preview of [input]; empty when blank or invalid. */
    var preview by mutableStateOf("")
        private set

    /** The last successfully computed value, exposed to expressions as `ans`. */
    var lastAnswer by mutableStateOf(0.0)
        private set

    /** The single memory register (M+ / M- / MR / MC). */
    var memory by mutableStateOf(0.0)
        private set

    val history = mutableStateListOf<HistoryEntry>()

    data class HistoryEntry(val expression: String, val result: String)

    fun updateInput(value: String) {
        input = value
        preview = computePreview(value)
    }

    fun append(text: String) = updateInput(input + text)

    fun backspace() {
        if (input.isNotEmpty()) updateInput(input.dropLast(1))
    }

    fun clear() = updateInput("")

    /** Evaluate the current input, push it to history, store `ans`, show the result. */
    fun evaluate() {
        if (input.isBlank()) return
        val value = try {
            Expression.parse(input).eval(angle = angleMode, ans = lastAnswer)
        } catch (e: ExpressionError) {
            return // leave input untouched; the preview already flagged the problem
        }
        if (value.isNaN()) return
        val result = formatResult(value)
        history.add(0, HistoryEntry(input, result))
        lastAnswer = value
        updateInput(result)
    }

    fun useHistory(entry: HistoryEntry) = updateInput(entry.result)

    fun clearHistory() = history.clear()

    // Memory register operations. M+/M- fold the current preview (or input) into memory.
    fun memoryClear() { memory = 0.0 }
    fun memoryRecall() = append(formatResult(memory))
    fun memoryAdd() { currentValue()?.let { memory += it } }
    fun memorySubtract() { currentValue()?.let { memory -= it } }

    private fun currentValue(): Double? = try {
        Expression.parse(input.ifBlank { "0" }).eval(angle = angleMode, ans = lastAnswer)
            .takeIf { !it.isNaN() }
    } catch (e: ExpressionError) { null }

    private fun computePreview(value: String): String {
        if (value.isBlank()) return ""
        return try {
            val r = Expression.parse(value).eval(angle = angleMode, ans = lastAnswer)
            if (r.isNaN()) "" else formatResult(r)
        } catch (e: ExpressionError) {
            ""
        }
    }

    // ---- Graph tab ----
    val functions = mutableStateListOf(
        GraphFunction(id = 0, text = "x", color = FunctionColors[0]),
    )
    private var nextId = 1L

    fun addFunction() {
        val color = FunctionColors[functions.size % FunctionColors.size]
        functions.add(GraphFunction(id = nextId++, text = "", color = color))
    }

    fun updateFunction(id: Long, text: String) = mutate(id) { it.copy(text = text) }
    fun toggleFunction(id: Long) = mutate(id) { it.copy(enabled = !it.enabled) }
    fun togglePolar(id: Long) = mutate(id) { it.copy(polar = !it.polar) }

    fun removeFunction(id: Long) {
        functions.removeAll { it.id == id }
        if (functions.isEmpty()) addFunction()
        clearAnalysis()
    }

    private inline fun mutate(id: Long, transform: (GraphFunction) -> GraphFunction) {
        val index = functions.indexOfFirst { it.id == id }
        if (index >= 0) functions[index] = transform(functions[index])
    }

    // ---- Viewport (hoisted here so it survives tab switches and analysis can read it) ----
    var centerX by mutableStateOf(0.0)
    var centerY by mutableStateOf(0.0)
    var scale by mutableStateOf(60.0) // pixels per unit
    var viewWidthPx by mutableStateOf(0f)
    var viewHeightPx by mutableStateOf(0f)

    val analysisPoints = mutableStateListOf<AnalysisPoint>()
    var analysisSummary by mutableStateOf<List<String>>(emptyList())
        private set

    fun clearAnalysis() {
        analysisPoints.clear()
        analysisSummary = emptyList()
    }

    /**
     * Run [kind] over the currently visible x-range for the enabled Cartesian functions
     * (polar curves are skipped — the analyses are Cartesian). Populates [analysisPoints]
     * (drawn on the graph) and [analysisSummary] (shown in a dialog).
     */
    fun runAnalysis(kind: AnalysisKind) {
        analysisPoints.clear()
        if (viewWidthPx <= 0f) { analysisSummary = listOf("Open the graph first."); return }
        val xMin = centerX - (viewWidthPx / 2) / scale
        val xMax = centerX + (viewWidthPx / 2) / scale

        val cartesian = functions.filter { it.enabled && !it.polar && it.text.isNotBlank() }
            .mapNotNull { fn -> runCatching { fn to Expression.parse(fn.text) }.getOrNull() }

        val summary = mutableListOf<String>()
        when (kind) {
            AnalysisKind.INTERSECTIONS -> {
                for (i in cartesian.indices) for (j in i + 1 until cartesian.size) {
                    val (fa, ea) = cartesian[i]
                    val (_, eb) = cartesian[j]
                    for (x in GraphAnalysis.intersections(ea, eb, angleMode, xMin, xMax)) {
                        val y = ea.eval(x, angleMode)
                        analysisPoints.add(AnalysisPoint(x, y, "(${fmt(x)}, ${fmt(y)})", fa.color))
                        summary.add("Intersection: (${fmt(x)}, ${fmt(y)})")
                    }
                }
            }
            else -> for ((fn, expr) in cartesian) {
                val xs = when (kind) {
                    AnalysisKind.ROOTS -> GraphAnalysis.roots(expr, angleMode, xMin, xMax)
                    AnalysisKind.MINIMA -> GraphAnalysis.extrema(expr, angleMode, xMin, xMax, wantMax = false)
                    AnalysisKind.MAXIMA -> GraphAnalysis.extrema(expr, angleMode, xMin, xMax, wantMax = true)
                    AnalysisKind.Y_INTERCEPT -> if (0.0 in xMin..xMax) listOf(0.0) else emptyList()
                    AnalysisKind.INTERSECTIONS -> emptyList()
                }
                for (x in xs) {
                    val y = expr.eval(x, angleMode)
                    if (y.isNaN() || y.isInfinite()) continue
                    analysisPoints.add(AnalysisPoint(x, y, "(${fmt(x)}, ${fmt(y)})", fn.color))
                    summary.add("${kind.title.removeSuffix("s")}: (${fmt(x)}, ${fmt(y)})")
                }
            }
        }
        analysisSummary = if (summary.isEmpty()) listOf("None found in the visible window.") else summary
    }

    private fun fmt(v: Double) = formatResult(v)

    companion object {
        /** Distinct, colour-blind-friendly curve colours, reused cyclically. */
        val FunctionColors = listOf(
            Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFF34A853),
            Color(0xFFFF9800), Color(0xFF9C27B0), Color(0xFF00BCD4),
        )
    }
}
