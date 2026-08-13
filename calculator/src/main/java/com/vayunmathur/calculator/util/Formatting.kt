package com.vayunmathur.calculator.util

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

/**
 * Formats a computed [Double] for display: trims floating-point noise, uses plain
 * decimal notation for "normal" magnitudes and scientific notation for very large or
 * very small values, and reports non-finite results as human-readable errors.
 */
fun formatResult(value: Double): String {
    if (value.isNaN()) return "Undefined"
    if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
    if (value == 0.0) return "0"

    val magnitude = abs(value)
    // Round to 12 significant digits to hide binary-floating-point artefacts
    // (e.g. 0.1 + 0.2 → 0.30000000000000004).
    val rounded = BigDecimal(value).round(MathContext(12)).stripTrailingZeros()

    return if (magnitude >= 1e12 || magnitude < 1e-6) {
        // Scientific notation, e.g. 1.23e+15.
        rounded.toString().replace("E", "e")
    } else {
        rounded.toPlainString()
    }
}

/** The magnitude of [q] expressed in [unit] (handling affine temperature and deltas). */
fun displayValueIn(q: Quantity, unit: UnitDef): Double =
    if (q.dimension == Dimension.TEMPERATURE) {
        if (q.tempOffsetK != null) unit.fromBase(q.value + q.tempOffsetK) // absolute
        else q.value / unit.factorToBase // delta: degree-size only, no offset
    } else {
        unit.fromBase(q.value)
    }

/**
 * Formats a [Quantity] for display, converting it into [unit] and appending a label. A
 * dimensionless quantity (or a null [unit]) is just its number. When [useToken] is set the
 * ASCII parse token and a plain space are used so the string re-parses (for result round-trips);
 * otherwise the pretty [UnitDef.symbol] and a thin space are used for display.
 */
fun formatQuantity(q: Quantity, unit: UnitDef?, useToken: Boolean = false): String {
    if (q.isDimensionless || unit == null) return formatResult(q.value)
    val label = if (useToken) unit.token else unit.symbol
    val separator = if (useToken) " " else "\u202F"
    return formatResult(displayValueIn(q, unit)) + separator + label
}
