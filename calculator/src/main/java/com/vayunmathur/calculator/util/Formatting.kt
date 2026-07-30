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
