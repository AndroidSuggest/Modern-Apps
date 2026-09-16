package com.vayunmathur.library.ml

/**
 * One recognised line of text, in source-bitmap pixel coordinates.
 *
 * [corners] are the four corners of the region's oriented quad, ordered so that corner 0 to
 * 1 runs along the text and 0 to 3 spans its height. A detected region is a *rotated*
 * rectangle, not an upright one, so the quad carries information a rect would lose - and
 * [left]/[top]/[right]/[bottom] are its axis-aligned bounds for callers that only need one.
 */
data class RecognizedLine(
    val text: String,
    val confidence: Float,
    val corners: List<Pair<Float, Float>>,
    val vertical: Boolean,
) {
    val left: Float get() = corners.minOf { it.first }
    val top: Float get() = corners.minOf { it.second }
    val right: Float get() = corners.maxOf { it.first }
    val bottom: Float get() = corners.maxOf { it.second }
}
