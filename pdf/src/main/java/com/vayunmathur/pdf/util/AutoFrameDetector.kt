package com.vayunmathur.pdf.util

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.scale
import com.vayunmathur.pdf.model.Quadrilateral
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object AutoFrameDetector {

    private const val MAX_DIM = 500
    private const val MIN_AREA_FRACTION = 0.05f

    fun detect(bitmap: Bitmap): Quadrilateral? {
        val maxDim = max(bitmap.width, bitmap.height)
        val scale = if (maxDim > MAX_DIM) MAX_DIM.toFloat() / maxDim else 1f
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(10)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(10)
        val scaled = if (scale < 1f) bitmap.scale(w, h) else bitmap

        return try {
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            val gray = grayscale(pixels)
            val blurred = gaussianBlur(gray, w, h)

            detectFromCanny(blurred, w, h, 75, 200)
                ?: detectFromCanny(blurred, w, h, 50, 150)
                ?: detectFromCanny(blurred, w, h, 30, 100)
                ?: detectFromAdaptiveThreshold(blurred, w, h)
        } catch (_: Exception) {
            null
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun detectFromCanny(gray: IntArray, w: Int, h: Int, t1: Int, t2: Int): Quadrilateral? {
        val edges = canny(gray, w, h, t1, t2)
        dilate(edges, w, h)
        return findBestQuad(edges, w, h)
    }

    private fun detectFromAdaptiveThreshold(gray: IntArray, w: Int, h: Int): Quadrilateral? {
        val binary = adaptiveThreshold(gray, w, h)
        morphClose(binary, w, h)
        return findBestQuad(binary, w, h)
    }

    // --- Canny edge detection ---

    private fun canny(gray: IntArray, w: Int, h: Int, lowThresh: Int, highThresh: Int): BooleanArray {
        val gx = IntArray(w * h)
        val gy = IntArray(w * h)
        val mag = IntArray(w * h)
        // Sobel gradients
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val sx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] +
                gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1]
            val sy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] +
                gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
            gx[i] = sx; gy[i] = sy
            mag[i] = min(255, hypot(sx.toFloat(), sy.toFloat()).roundToInt())
        }
        // Non-maximum suppression
        val nms = IntArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val m = mag[i]
            if (m == 0) continue
            val angle = ((atan2(gy[i].toFloat(), gx[i].toFloat()) * 4 / Math.PI).roundToInt() + 4) % 4
            val (n1, n2) = when (angle) {
                0 -> mag[i - 1] to mag[i + 1]           // horizontal edge → compare left/right
                1 -> mag[i - w + 1] to mag[i + w - 1]   // 45°
                2 -> mag[i - w] to mag[i + w]            // vertical edge → compare above/below
                else -> mag[i - w - 1] to mag[i + w + 1] // 135°
            }
            nms[i] = if (m >= n1 && m >= n2) m else 0
        }
        // Hysteresis thresholding
        val result = BooleanArray(w * h)
        val strong = BooleanArray(w * h) { nms[it] >= highThresh }
        val queue = ArrayDeque<Int>()
        for (i in strong.indices) if (strong[i]) { result[i] = true; queue.add(i) }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % w; val y = i / w
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val ni = ny * w + nx
                if (!result[ni] && nms[ni] >= lowThresh) { result[ni] = true; queue.add(ni) }
            }
        }
        return result
    }

    // --- Adaptive threshold ---

    private fun adaptiveThreshold(gray: IntArray, w: Int, h: Int): BooleanArray {
        val radius = 5
        val c = 2
        // Integral image for fast box mean
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) for (x in 0 until w) {
            val iw = w + 1
            integral[(y + 1) * iw + (x + 1)] = gray[y * w + x].toLong() +
                integral[y * iw + (x + 1)] + integral[(y + 1) * iw + x] - integral[y * iw + x]
        }
        val result = BooleanArray(w * h)
        val iw = w + 1
        for (y in 0 until h) for (x in 0 until w) {
            val x1 = max(0, x - radius); val y1 = max(0, y - radius)
            val x2 = min(w - 1, x + radius); val y2 = min(h - 1, y + radius)
            val count = (x2 - x1 + 1) * (y2 - y1 + 1)
            val sum = integral[(y2 + 1) * iw + (x2 + 1)] - integral[y1 * iw + (x2 + 1)] -
                integral[(y2 + 1) * iw + x1] + integral[y1 * iw + x1]
            val mean = sum / count
            result[y * w + x] = gray[y * w + x] < mean - c
        }
        return result
    }

    // --- Morphology ---

    private fun dilate(edges: BooleanArray, w: Int, h: Int) {
        val copy = edges.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if (copy[y * w + x] || copy[(y - 1) * w + x] || copy[(y + 1) * w + x] ||
                copy[y * w + x - 1] || copy[y * w + x + 1]) edges[y * w + x] = true
        }
    }

    private fun morphClose(binary: BooleanArray, w: Int, h: Int) {
        dilate(binary, w, h)
        dilate(binary, w, h)
        erode(binary, w, h)
        erode(binary, w, h)
    }

    private fun erode(edges: BooleanArray, w: Int, h: Int) {
        val copy = edges.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            if (!(copy[y * w + x] && copy[(y - 1) * w + x] && copy[(y + 1) * w + x] &&
                    copy[y * w + x - 1] && copy[y * w + x + 1])) edges[y * w + x] = false
        }
    }

    // --- Contour tracing (Suzuki-Abe / Moore boundary tracing) ---

    private fun findBestQuad(binary: BooleanArray, w: Int, h: Int): Quadrilateral? {
        val contours = traceContours(binary, w, h)
        val minArea = w * h * MIN_AREA_FRACTION

        var best: Quadrilateral? = null
        var bestScore = 0.0

        for (contour in contours) {
            val area = contourArea(contour)
            if (area < minArea) continue

            val peri = contourPerimeter(contour)
            val approx = approxPolyDP(contour, 0.02 * peri)
            if (approx.size != 4) continue
            if (!isConvex(approx)) continue

            val score = scoreCandidate(approx, area, w, h)
            if (score > bestScore) {
                bestScore = score
                val corners = sortCorners(approx)
                best = Quadrilateral(
                    topLeft = Offset(corners[0].first.toFloat() / w, corners[0].second.toFloat() / h),
                    topRight = Offset(corners[1].first.toFloat() / w, corners[1].second.toFloat() / h),
                    bottomRight = Offset(corners[2].first.toFloat() / w, corners[2].second.toFloat() / h),
                    bottomLeft = Offset(corners[3].first.toFloat() / w, corners[3].second.toFloat() / h)
                )
            }
        }
        return best
    }

    private fun traceContours(binary: BooleanArray, w: Int, h: Int): List<List<Pair<Int, Int>>> {
        val visited = BooleanArray(w * h)
        val contours = mutableListOf<List<Pair<Int, Int>>>()
        // Moore neighbor offsets (clockwise from right)
        val dx = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val dy = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!binary[i] || visited[i]) continue
            // Only start from outer boundary pixels (pixel to the left is background)
            if (x > 0 && binary[i - 1]) continue

            val contour = mutableListOf<Pair<Int, Int>>()
            var cx = x; var cy = y
            var dir = 0 // start looking right

            do {
                contour.add(cx to cy)
                visited[cy * w + cx] = true
                var found = false
                // Search neighbors starting from (dir + 6) % 8 (backtrack direction + 1)
                val startDir = (dir + 6) % 8
                for (k in 0 until 8) {
                    val d = (startDir + k) % 8
                    val nx = cx + dx[d]; val ny = cy + dy[d]
                    if (nx in 0 until w && ny in 0 until h && binary[ny * w + nx]) {
                        cx = nx; cy = ny; dir = d; found = true; break
                    }
                }
                if (!found) break
            } while (cx != x || cy != y)

            // Subsample long contours to keep things fast
            val step = max(1, contour.size / 1000)
            val sampled = if (step > 1) contour.filterIndexed { idx, _ -> idx % step == 0 } else contour
            if (sampled.size >= 4) contours.add(sampled)
        }

        return contours.sortedByDescending { contourArea(it) }
    }

    // --- Ramer-Douglas-Peucker polygon approximation ---

    private fun approxPolyDP(points: List<Pair<Int, Int>>, epsilon: Double): List<Pair<Int, Int>> {
        if (points.size < 3) return points
        // For closed contours, find the two farthest points to split
        var maxDist = 0.0; var splitA = 0; var splitB = 0
        for (i in points.indices) for (j in i + 1..min(i + points.size / 2, points.lastIndex)) {
            val d = dist(points[i], points[j])
            if (d > maxDist) { maxDist = d; splitA = i; splitB = j }
        }
        val part1 = points.subList(splitA, splitB + 1)
        val part2 = points.subList(splitB, points.size) + points.subList(0, splitA + 1)
        val simplified1 = rdp(part1, epsilon)
        val simplified2 = rdp(part2, epsilon)
        // Merge, removing duplicate junction points
        val result = mutableListOf<Pair<Int, Int>>()
        result.addAll(simplified1)
        if (simplified2.size > 2) result.addAll(simplified2.subList(1, simplified2.size - 1))
        return result
    }

    private fun rdp(points: List<Pair<Int, Int>>, epsilon: Double): List<Pair<Int, Int>> {
        if (points.size <= 2) return points
        var maxDist = 0.0; var maxIdx = 0
        val first = points.first(); val last = points.last()
        for (i in 1 until points.size - 1) {
            val d = pointToLineDist(points[i], first, last)
            if (d > maxDist) { maxDist = d; maxIdx = i }
        }
        if (maxDist > epsilon) {
            val left = rdp(points.subList(0, maxIdx + 1), epsilon)
            val right = rdp(points.subList(maxIdx, points.size), epsilon)
            return left + right.subList(1, right.size)
        }
        return listOf(first, last)
    }

    private fun pointToLineDist(p: Pair<Int, Int>, a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
        val dx = b.first - a.first; val dy = b.second - a.second
        val lenSq = dx.toLong() * dx + dy.toLong() * dy
        if (lenSq == 0L) return dist(p, a)
        val num = abs(dy.toLong() * p.first - dx.toLong() * p.second + b.first.toLong() * a.second - b.second.toLong() * a.first)
        return num.toDouble() / sqrt(lenSq.toDouble())
    }

    private fun dist(a: Pair<Int, Int>, b: Pair<Int, Int>): Double =
        hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble())

    // --- Geometry utilities ---

    private fun contourArea(points: List<Pair<Int, Int>>): Double {
        var area = 0L; val n = points.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += points[i].first.toLong() * points[j].second - points[j].first.toLong() * points[i].second
        }
        return abs(area) / 2.0
    }

    private fun contourPerimeter(points: List<Pair<Int, Int>>): Double {
        var peri = 0.0
        for (i in points.indices) peri += dist(points[i], points[(i + 1) % points.size])
        return peri
    }

    private fun isConvex(points: List<Pair<Int, Int>>): Boolean {
        val n = points.size; if (n < 3) return false
        var sign = 0
        for (i in 0 until n) {
            val o = points[i]; val a = points[(i + 1) % n]; val b = points[(i + 2) % n]
            val cross = (a.first - o.first).toLong() * (b.second - a.second) -
                (a.second - o.second).toLong() * (b.first - a.first)
            if (cross != 0L) {
                val s = if (cross > 0) 1 else -1
                if (sign == 0) sign = s else if (s != sign) return false
            }
        }
        return true
    }

    private fun scoreCandidate(points: List<Pair<Int, Int>>, area: Double, w: Int, h: Int): Double {
        val imageArea = w.toDouble() * h
        val areaScore = area / imageArea
        var angleScore = 1.0
        for (i in points.indices) {
            val prev = points[(i + 3) % 4]; val curr = points[i]; val next = points[(i + 1) % 4]
            val v1x = (prev.first - curr.first).toDouble(); val v1y = (prev.second - curr.second).toDouble()
            val v2x = (next.first - curr.first).toDouble(); val v2y = (next.second - curr.second).toDouble()
            val len1 = hypot(v1x, v1y); val len2 = hypot(v2x, v2y)
            if (len1 < 1e-6 || len2 < 1e-6) continue
            val cosAngle = (v1x * v2x + v1y * v2y) / (len1 * len2)
            angleScore *= (1.0 - abs(cosAngle))
        }
        return 0.6 * areaScore + 0.4 * angleScore
    }

    // --- Corner sorting ---

    private fun sortCorners(corners: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val bySum = corners.sortedBy { it.first + it.second }
        val tl = bySum.first(); val br = bySum.last()
        val remaining = corners.toMutableList().apply { remove(tl); remove(br) }
        val byDiff = remaining.sortedBy { it.first - it.second }
        return listOf(tl, byDiff.last(), br, byDiff.first())
    }

    // --- Image processing helpers ---

    private fun grayscale(pixels: IntArray): IntArray =
        IntArray(pixels.size) { i ->
            val p = pixels[i]
            ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }

    private fun gaussianBlur(src: IntArray, w: Int, h: Int): IntArray {
        val k = intArrayOf(2, 4, 5, 4, 2, 4, 9, 12, 9, 4, 5, 12, 15, 12, 5, 4, 9, 12, 9, 4, 2, 4, 5, 4, 2)
        val kSum = 159
        val dst = IntArray(w * h)
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            var sum = 0; var ki = 0
            for (ky in -2..2) for (kx in -2..2) sum += src[(y + ky) * w + (x + kx)] * k[ki++]
            dst[y * w + x] = sum / kSum
        }
        return dst
    }
}
