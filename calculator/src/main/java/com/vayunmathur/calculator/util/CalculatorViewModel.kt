package com.vayunmathur.calculator.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import kotlin.math.hypot

/** One plotted curve on the graph screen — Cartesian `y = f(x)` or polar `r = f(θ)`. */
data class GraphFunction(
    val id: Long,
    val text: String = "",
    val color: Color,
    val enabled: Boolean = true,
    val polar: Boolean = false,
)

/**
 * A notable point the user revealed by touching near it. Held as the feature itself rather
 * than as pre-rendered text so the UI can colour and localise the label.
 */
data class GraphMarker(
    val point: GraphPoint,
    val kind: FeatureKind,
    /** The curve it belongs to, or the two curves that cross there. */
    val curveIds: List<Long>,
)

/**
 * Holds all calculator state at the Activity scope so it survives switching between the
 * Calculator and Graph tabs (which reset the nav back stack).
 */
class CalculatorViewModel : ViewModel(), CalculatorActions, GraphActions {

    // ---- Shared ----
    var angleMode by mutableStateOf(AngleMode.RADIANS)
        private set

    override fun toggleAngleMode() {
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

    /**
     * Snapshot of everything [com.vayunmathur.calculator.ui.CalculatorScreen] draws. Read
     * during composition, so the underlying `mutableStateOf` reads are tracked normally.
     */
    val calculatorUiState: CalculatorUiState
        get() = CalculatorUiState(
            input = input,
            preview = preview,
            memory = memory,
            angleMode = angleMode,
            history = history,
        )

    /** Snapshot of everything [com.vayunmathur.calculator.ui.GraphScreen] draws. */
    val graphUiState: GraphUiState
        get() = GraphUiState(
            functions = functions,
            markers = markers,
            angleMode = angleMode,
            viewport = GraphViewport(centerX, centerY, scale),
        )

    fun updateInput(value: String) {
        input = value
        preview = computePreview(value)
    }

    override fun append(text: String) = updateInput(input + text)

    override fun backspace() {
        if (input.isNotEmpty()) updateInput(input.dropLast(1))
    }

    override fun clear() = updateInput("")

    /** Evaluate the current input, push it to history, store `ans`, show the result. */
    override fun evaluate() {
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

    override fun useHistory(entry: HistoryEntry) = updateInput(entry.result)

    override fun clearHistory() = history.clear()

    // Memory register operations. M+/M- fold the current preview (or input) into memory.
    override fun memoryClear() { memory = 0.0 }
    override fun memoryRecall() = append(formatResult(memory))
    override fun memoryAdd() { currentValue()?.let { memory += it } }
    override fun memorySubtract() { currentValue()?.let { memory -= it } }

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

    override fun addFunction() {
        val color = FunctionColors[functions.size % FunctionColors.size]
        functions.add(GraphFunction(id = nextId++, text = "", color = color))
    }

    override fun updateFunction(id: Long, text: String) {
        dropMarkersFor(id)
        mutate(id) { it.copy(text = text) }
    }

    override fun toggleFunction(id: Long) {
        dropMarkersFor(id)
        mutate(id) { it.copy(enabled = !it.enabled) }
    }

    override fun togglePolar(id: Long) {
        dropMarkersFor(id)
        mutate(id) { it.copy(polar = !it.polar) }
    }

    override fun removeFunction(id: Long) {
        functions.removeAll { it.id == id }
        if (functions.isEmpty()) addFunction()
        dropMarkersFor(id)
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

    /** Notable points the user has revealed by touching near them. */
    val markers = mutableStateListOf<GraphMarker>()

    override fun setViewSize(widthPx: Float, heightPx: Float) {
        viewWidthPx = widthPx
        viewHeightPx = heightPx
    }

    override fun setViewport(centerX: Double, centerY: Double, scale: Double) {
        this.centerX = centerX
        this.centerY = centerY
        this.scale = scale
    }

    private fun dropMarkersFor(id: Long) = markers.removeAll { id in it.curveIds }

    /**
     * Samples every visible curve over the current viewport. Drawing and analysis share this
     * so a marker always lands exactly on the line that was drawn.
     */
    fun sampleCurves(widthPx: Float, heightPx: Float): List<SampledCurve> {
        if (widthPx <= 0f || heightPx <= 0f) return emptyList()
        val xMin = centerX - (widthPx / 2) / scale
        val xMax = centerX + (widthPx / 2) / scale
        val yMin = centerY - (heightPx / 2) / scale
        val yMax = centerY + (heightPx / 2) / scale
        return functions.filter { it.enabled && it.text.isNotBlank() }.mapNotNull { fn ->
            val expr = runCatching { Expression.parse(fn.text) }.getOrNull() ?: return@mapNotNull null
            GraphAnalysis.sample(fn.id, expr, fn.polar, angleMode, xMin, xMax, yMin, yMax, widthPx.toInt())
        }
    }

    /**
     * Handles a tap on the graph, [radius] being the touch slop in graph units. Touching a
     * marker removes it; touching anywhere else reveals the nearest notable point in range,
     * across every curve on screen — Cartesian, polar, and crossings between the two.
     */
    override fun tapGraph(at: GraphPoint, radius: Double) {
        val hit = markers.minByOrNull { hypot(it.point.x - at.x, it.point.y - at.y) }
        if (hit != null && hypot(hit.point.x - at.x, hit.point.y - at.y) <= radius) {
            markers.remove(hit)
            return
        }
        val feature = GraphAnalysis
            .featuresNear(sampleCurves(viewWidthPx, viewHeightPx), at, radius, angleMode)
            .firstOrNull() ?: return
        markers.add(GraphMarker(feature.point, feature.kind, feature.curveIds))
    }

    companion object {
        /** Distinct, colour-blind-friendly curve colours, reused cyclically. */
        val FunctionColors = listOf(
            Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFF34A853),
            Color(0xFFFF9800), Color(0xFF9C27B0), Color(0xFF00BCD4),
        )
    }
}
