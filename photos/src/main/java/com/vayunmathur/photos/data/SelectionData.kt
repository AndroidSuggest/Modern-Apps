package com.vayunmathur.photos.data

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlin.math.roundToInt

/** How a new selection combines with the existing one. */
enum class SelectionCombine { New, Add, Subtract, Intersect }

/**
 * A normalized selection mask (values 0..1) at [width] x [height]. A regular class
 * (not data) so that equality compares the [mask] contents via contentEquals.
 */
class Selection(
    val mask: FloatArray,
    val width: Int,
    val height: Int,
    val featherRadius: Float = 0f,
) {
    fun isEmpty(): Boolean = mask.all { it <= 0f }

    fun invert(): Selection =
        Selection(FloatArray(mask.size) { 1f - mask[it] }, width, height, featherRadius)

    fun applyFeather(radius: Float): Selection {
        val r = radius.toInt()
        if (r <= 0) return Selection(mask.copyOf(), width, height, radius)
        val horizontal = FloatArray(mask.size)
        val windowSize = (2 * r + 1).toFloat()
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in -r..r) {
                    val sx = (x + k).coerceIn(0, width - 1)
                    sum += mask[rowOffset + sx]
                }
                horizontal[rowOffset + x] = sum / windowSize
            }
        }
        val blurred = FloatArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (k in -r..r) {
                    val sy = (y + k).coerceIn(0, height - 1)
                    sum += horizontal[sy * width + x]
                }
                blurred[y * width + x] = sum / windowSize
            }
        }
        return Selection(blurred, width, height, radius)
    }

    fun toLayerMask(): LayerMask = LayerMask(mask.copyOf(), width, height)

    /**
     * Combine this selection with [other] under [mode]. If sizes differ, [other]
     * is nearest-sampled onto this selection's grid.
     */
    fun combine(other: Selection, mode: SelectionCombine): Selection {
        if (mode == SelectionCombine.New) return other
        val out = FloatArray(mask.size)
        for (y in 0 until height) {
            val sy = if (other.height == height) y else (y * other.height / height).coerceIn(0, other.height - 1)
            for (x in 0 until width) {
                val sx = if (other.width == width) x else (x * other.width / width).coerceIn(0, other.width - 1)
                val a = mask[y * width + x]
                val b = other.mask[sy * other.width + sx]
                out[y * width + x] = when (mode) {
                    SelectionCombine.Add -> maxOf(a, b)
                    SelectionCombine.Subtract -> (a * (1f - b)).coerceIn(0f, 1f)
                    SelectionCombine.Intersect -> a * b
                    SelectionCombine.New -> b
                }
            }
        }
        return Selection(out, width, height)
    }

    override fun equals(other: Any?): Boolean =
        other is Selection &&
            width == other.width &&
            height == other.height &&
            mask.contentEquals(other.mask)

    override fun hashCode(): Int {
        var hash = mask.contentHashCode()
        hash = 31 * hash + width
        hash = 31 * hash + height
        return hash
    }

    companion object {
        fun rectangle(
            width: Int,
            height: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ): Selection {
            val mask = FloatArray(width * height)
            val l = (left * width).roundToInt()
            val t = (top * height).roundToInt()
            val rt = (right * width).roundToInt()
            val b = (bottom * height).roundToInt()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (x in l until rt && y in t until b) {
                        mask[y * width + x] = 1f
                    }
                }
            }
            return Selection(mask, width, height)
        }

        fun ellipse(
            width: Int,
            height: Int,
            cx: Float,
            cy: Float,
            rx: Float,
            ry: Float,
        ): Selection {
            val mask = FloatArray(width * height)
            val centerX = cx * width
            val centerY = cy * height
            val radX = rx * width
            val radY = ry * height
            if (radX > 0f && radY > 0f) {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val nx = (x - centerX) / radX
                        val ny = (y - centerY) / radY
                        if (nx * nx + ny * ny <= 1f) {
                            mask[y * width + x] = 1f
                        }
                    }
                }
            }
            return Selection(mask, width, height)
        }

        /**
         * Rasterize a closed polygon (freehand lasso / polygonal lasso). [points]
         * are normalized (0..1) vertices; filled via even-odd scanline test.
         */
        fun polygon(
            width: Int,
            height: Int,
            points: List<Pair<Float, Float>>,
        ): Selection {
            val mask = FloatArray(width * height)
            if (points.size < 3) return Selection(mask, width, height)
            val xs = FloatArray(points.size) { points[it].first * width }
            val ys = FloatArray(points.size) { points[it].second * height }
            for (y in 0 until height) {
                val py = y + 0.5f
                for (x in 0 until width) {
                    val px = x + 0.5f
                    var inside = false
                    var j = points.size - 1
                    for (i in points.indices) {
                        val yi = ys[i]; val yj = ys[j]
                        if ((yi > py) != (yj > py)) {
                            val xCross = xs[i] + (py - yi) / (yj - yi) * (xs[j] - xs[i])
                            if (px < xCross) inside = !inside
                        }
                        j = i
                    }
                    if (inside) mask[y * width + x] = 1f
                }
            }
            return Selection(mask, width, height)
        }

        /**
         * Magic-wand selection: flood from a seed pixel selecting neighbours whose
         * color is within [tolerance] (0..1) of the seed. [seedX]/[seedY] are
         * normalized. When [contiguous] is false, every matching pixel is selected.
         * The mask is built at [source]'s (optionally downscaled) resolution.
         */
        fun magicWand(
            source: Bitmap,
            seedX: Float,
            seedY: Float,
            tolerance: Float,
            contiguous: Boolean = true,
            maxDim: Int = 768,
        ): Selection {
            val scale = minOf(1f, maxDim.toFloat() / maxOf(source.width, source.height))
            val w = (source.width * scale).roundToInt().coerceAtLeast(1)
            val h = (source.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = if (w == source.width && h == source.height) source
            else source.scale(w, h)
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)

            val sx = (seedX * w).roundToInt().coerceIn(0, w - 1)
            val sy = (seedY * h).roundToInt().coerceIn(0, h - 1)
            val seed = px[sy * w + sx]
            val sr = (seed ushr 16) and 0xFF
            val sg = (seed ushr 8) and 0xFF
            val sb = seed and 0xFF
            // Max channel distance, scaled to 0..441 (sqrt(3)*255) space.
            val tol = tolerance.coerceIn(0f, 1f) * 441f

            fun matches(c: Int): Boolean {
                val dr = ((c ushr 16) and 0xFF) - sr
                val dg = ((c ushr 8) and 0xFF) - sg
                val db = (c and 0xFF) - sb
                return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) <= tol
            }

            val mask = FloatArray(w * h)
            if (contiguous) {
                val stack = ArrayDeque<Int>()
                stack.addLast(sy * w + sx)
                val visited = BooleanArray(w * h)
                visited[sy * w + sx] = true
                while (stack.isNotEmpty()) {
                    val idx = stack.removeLast()
                    if (!matches(px[idx])) continue
                    mask[idx] = 1f
                    val cx = idx % w; val cy = idx / w
                    if (cx > 0 && !visited[idx - 1]) { visited[idx - 1] = true; stack.addLast(idx - 1) }
                    if (cx < w - 1 && !visited[idx + 1]) { visited[idx + 1] = true; stack.addLast(idx + 1) }
                    if (cy > 0 && !visited[idx - w]) { visited[idx - w] = true; stack.addLast(idx - w) }
                    if (cy < h - 1 && !visited[idx + w]) { visited[idx + w] = true; stack.addLast(idx + w) }
                }
            } else {
                for (i in px.indices) if (matches(px[i])) mask[i] = 1f
            }
            return Selection(mask, w, h)
        }
    }
}
