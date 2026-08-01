package com.vayunmathur.calculator.util

/**
 * The UI contract between [CalculatorViewModel] and the screens.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the ViewModel implements these interfaces.
 */

/** One evaluated expression, kept for the history sheet. */
data class HistoryEntry(val expression: String, val result: String)

/** Where the graph is looking: centre in graph units, and pixels per unit. */
data class GraphViewport(
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    val scale: Double = 60.0,
)

/** Everything the keypad screen draws. */
data class CalculatorUiState(
    val input: String = "",
    val preview: String = "",
    val memory: Double = 0.0,
    val angleMode: AngleMode = AngleMode.RADIANS,
    val history: List<HistoryEntry> = emptyList(),
)

/** Everything the graph screen draws. */
data class GraphUiState(
    val functions: List<GraphFunction> = emptyList(),
    val markers: List<GraphMarker> = emptyList(),
    val angleMode: AngleMode = AngleMode.RADIANS,
    val viewport: GraphViewport = GraphViewport(),
)

/**
 * Keypad callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface CalculatorActions {
    fun append(text: String) {}
    fun clear() {}
    fun backspace() {}
    fun evaluate() {}
    fun toggleAngleMode() {}
    fun memoryClear() {}
    fun memoryRecall() {}
    fun memoryAdd() {}
    fun memorySubtract() {}
    fun useHistory(entry: HistoryEntry) {}
    fun clearHistory() {}

    companion object {
        val Noop: CalculatorActions = object : CalculatorActions {}
    }
}

/** Graph callbacks. Same no-op-default arrangement as [CalculatorActions]. */
interface GraphActions {
    fun toggleAngleMode() {}
    fun addFunction() {}
    fun updateFunction(id: Long, text: String) {}
    fun toggleFunction(id: Long) {}
    fun togglePolar(id: Long) {}
    fun removeFunction(id: Long) {}

    /** Report the canvas size so tap handling can convert pixels to graph units. */
    fun setViewSize(widthPx: Float, heightPx: Float) {}

    /** Pan/zoom result from a transform gesture. */
    fun setViewport(centerX: Double, centerY: Double, scale: Double) {}

    /** Reveal or dismiss the notable point nearest [at], within [radius] graph units. */
    fun tapGraph(at: GraphPoint, radius: Double) {}

    companion object {
        val Noop: GraphActions = object : GraphActions {}
    }
}
