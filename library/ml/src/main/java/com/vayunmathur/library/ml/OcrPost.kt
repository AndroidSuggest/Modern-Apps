package com.vayunmathur.library.ml

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Runtime-agnostic PP-OCR post-processing: DBNet regions, the rotated crop, CTC helpers and
 * reading order.
 *
 * Extracted verbatim from `TextRecognizer`'s private pipeline so the ExecuTorch drop-in
 * (`TextRecognizerEt`) can share it without touching the LiteRT path: detection geometry,
 * the warp and the row-banding do not depend on which runtime produced the tensors.
 * `TextRecognizer` keeps its own copies for now; unify the two once the ET `.pte` ships.
 */
internal object OcrPost {
    const val REC_HEIGHT = 48
    const val REC_WIDTH = 320
    const val WIDTH_MULTIPLE = 8

    private const val DB_THRESHOLD = 0.3f
    private const val BOX_THRESHOLD = 0.6f
    private const val ENLARGE_RATIO = 1.95f
    private const val MIN_SIZE = 3.0f
    private const val MAX_CANDIDATES = 1000
    private const val ELONGATED = 2.7f
    private const val ROW_BAND = 16f

    data class RotatedRect(
        var cx: Float,
        var cy: Float,
        var width: Float,
        var height: Float,
        var angle: Float,
    ) {
        fun normalise() {
            while (angle >= 0f) {
                angle -= 90f
                val t = width; width = height; height = t
            }
            while (angle < -90f) {
                angle += 90f
                val t = width; width = height; height = t
            }
        }
    }

    data class Region(val rect: RotatedRect, val score: Float, val vertical: Boolean)

    fun dbnetRegions(
        probability: FloatArray,
        width: Int,
        height: Int,
        fit: OnnxPreprocess.SquareFit,
    ): List<Region> {
        val cells = width * height
        val text = BooleanArray(cells) { probability[it] >= DB_THRESHOLD }
        val labelled = BooleanArray(cells)
        val stack = ArrayList<Int>(1024)
        val boundary = ArrayList<Pair<Float, Float>>(256)
        val found = ArrayList<Region?>(64)
        for (start in 0 until cells) {
            if (found.size >= MAX_CANDIDATES) break
            if (!text[start] || labelled[start]) continue
            labelled[start] = true
            stack.clear()
            stack += start
            boundary.clear()
            var total = 0.0
            var count = 0
            while (stack.isNotEmpty()) {
                val at = stack.removeAt(stack.lastIndex)
                val x = at % width
                val y = at / width
                total += probability[at]
                count++
                val edge = x == 0 || y == 0 || x + 1 == width || y + 1 == height ||
                    !text[at - 1] || !text[at + 1] || !text[at - width] || !text[at + width]
                if (edge) boundary += Pair(x.toFloat(), y.toFloat())
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val next = ny * width + nx
                        if (text[next] && !labelled[next]) {
                            labelled[next] = true
                            stack += next
                        }
                    }
                }
            }
            val score = if (count > 0) (total / count).toFloat() else 0f
            if (score < BOX_THRESHOLD) {
                found += null
                continue
            }
            val rect = minAreaRect(boundary) ?: run { found += null; continue }
            if (max(rect.width, rect.height) < MIN_SIZE) {
                found += null
                continue
            }
            if (rect.width > rect.height) {
                val t = rect.width; rect.width = rect.height; rect.height = t
                rect.angle += 90f
            }
            val vertical = rect.height > rect.width * ELONGATED && rect.angle in -30f..30f
            if (vertical || rect.angle <= 0f) rect.angle += 180f
            rect.height += rect.width * (ENLARGE_RATIO - 1f)
            rect.width *= ENLARGE_RATIO
            rect.cx = (rect.cx - fit.offsetX) / fit.scale
            rect.cy = (rect.cy - fit.offsetY) / fit.scale
            rect.width /= fit.scale
            rect.height /= fit.scale
            found += Region(rect, score, vertical)
        }
        return found.filterNotNull()
    }

    fun convexHull(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.size < 3) return points.toList()
        val sorted = points.sortedWith(compareBy({ it.first }, { it.second })).distinct()
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val hull = ArrayList<Pair<Float, Float>>(sorted.size + 1)
        for (pass in 0..1) {
            val lower = hull.size
            val iter = if (pass == 0) sorted else sorted.asReversed()
            for (point in iter) {
                while (hull.size >= lower + 2) {
                    val a = hull[hull.size - 2]
                    val b = hull[hull.size - 1]
                    if (cross(a, b, point) > 0f) break
                    hull.removeAt(hull.lastIndex)
                }
                hull += point
            }
            hull.removeAt(hull.lastIndex)
        }
        return hull
    }

    fun minAreaRect(points: List<Pair<Float, Float>>): RotatedRect? {
        val hull = convexHull(points)
        if (hull.isEmpty()) return null
        val first = hull[0]
        if (hull.size == 1) {
            return RotatedRect(first.first, first.second, 0f, 0f, 0f).also { it.normalise() }
        }
        var best: RotatedRect? = null
        var bestArea = Float.MAX_VALUE
        for (index in hull.indices) {
            val a = hull[index]
            val b = hull[(index + 1) % hull.size]
            val dx = b.first - a.first
            val dy = b.second - a.second
            val length = sqrt(dx * dx + dy * dy)
            if (length < 1e-9f) continue
            val ux = dx / length
            val uy = dy / length
            var lowU = Float.MAX_VALUE; var highU = -Float.MAX_VALUE
            var lowV = Float.MAX_VALUE; var highV = -Float.MAX_VALUE
            for ((x, y) in hull) {
                val u = x * ux + y * uy
                val v = -x * uy + y * ux
                lowU = min(lowU, u); highU = max(highU, u)
                lowV = min(lowV, v); highV = max(highV, v)
            }
            val area = (highU - lowU) * (highV - lowV)
            if (area >= bestArea) continue
            bestArea = area
            val midU = (lowU + highU) * 0.5f
            val midV = (lowV + highV) * 0.5f
            best = RotatedRect(
                midU * ux - midV * uy, midU * uy + midV * ux,
                highU - lowU, highV - lowV,
                atan2(dy, dx) * 180f / Math.PI.toFloat(),
            ).also { it.normalise() }
        }
        return best ?: RotatedRect(first.first, first.second, 0f, 0f, 0f).also { it.normalise() }
    }

    fun rectCorners(rect: RotatedRect): Array<Pair<Float, Float>> {
        val radians = rect.angle * Math.PI.toFloat() / 180f
        val sin = sin(radians)
        val cos = cos(radians)
        val along = rect.height * 0.5f
        val across = rect.width * 0.5f
        val alongX = -sin; val alongY = cos
        val acrossX = cos; val acrossY = sin
        fun corner(a: Float, b: Float) = Pair(
            rect.cx + alongX * a + acrossX * b,
            rect.cy + alongY * a + acrossY * b,
        )
        return arrayOf(
            corner(-along, -across), corner(along, -across),
            corner(along, across), corner(-along, across),
        )
    }

    fun cropWidth(region: Region): Int {
        val short = max(region.rect.width, 1f)
        val long = max(region.rect.height, 1f)
        val scaled = max(REC_HEIGHT * long / short, 1f)
        val wanted = min(kotlin.math.round(scaled).toInt(), REC_WIDTH).coerceAtLeast(WIDTH_MULTIPLE)
        return ((wanted + WIDTH_MULTIPLE - 1) / WIDTH_MULTIPLE) * WIDTH_MULTIPLE
    }

    fun warp(
        pixels: IntArray,
        width: Int,
        height: Int,
        corners: Array<Pair<Float, Float>>,
        outW: Int,
        outH: Int,
    ): IntArray {
        val origin = corners[0]
        val along = Pair(corners[1].first - corners[0].first, corners[1].second - corners[0].second)
        val across = Pair(corners[3].first - corners[0].first, corners[3].second - corners[0].second)
        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            val v = (y + 0.5f) / outH
            for (x in 0 until outW) {
                val u = (x + 0.5f) / outW
                val sx = origin.first + along.first * u + across.first * v
                val sy = origin.second + along.second * u + across.second * v
                out[y * outW + x] = bilinear(pixels, width, height, sx, sy)
            }
        }
        return out
    }

    fun bilinear(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        val cx = x.coerceIn(0f, (width - 1).toFloat())
        val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val wx = cx - x0
        val wy = cy - y0
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        fun at(px: Int, py: Int): Int = pixels[py * width + px]
        fun channel(shift: Int): Int {
            fun take(packed: Int): Float = ((packed shr shift) and 0xFF).toFloat()
            val top = take(at(x0, y0)) + (take(at(x1, y0)) - take(at(x0, y0))) * wx
            val bottom = take(at(x0, y1)) + (take(at(x1, y1)) - take(at(x0, y1))) * wx
            return (top + (bottom - top) * wy).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun orderReading(lines: MutableList<RecognizedLine>) {
        fun centre(line: RecognizedLine): Pair<Float, Float> {
            val sx = line.corners.sumOf { it.first.toDouble() }.toFloat() / 4f
            val sy = line.corners.sumOf { it.second.toDouble() }.toFloat() / 4f
            return Pair(sx, sy)
        }
        // Row-band reading order: lines whose centres fall in the same 16 px horizontal
        // band sort left-to-right; bands sort top-to-bottom.
        lines.sortWith { a, b ->
            val (ax, ay) = centre(a)
            val (bx, by) = centre(b)
            val bandA = (ay / ROW_BAND).toInt()
            val bandB = (by / ROW_BAND).toInt()
            if (bandA != bandB) bandA - bandB else ax.compareTo(bx)
        }
    }
}
