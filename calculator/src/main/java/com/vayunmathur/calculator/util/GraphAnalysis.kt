package com.vayunmathur.calculator.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point in graph (not screen) coordinates. */
data class GraphPoint(val x: Double, val y: Double)

/** The kinds of notable point the grapher can find. */
enum class FeatureKind {
    ROOT,
    Y_INTERCEPT,
    MINIMUM,
    MAXIMUM,
    INTERSECTION,
}

/**
 * A notable point on — or between — curves. [curveIds] holds the one curve the feature
 * belongs to, or the two curves that cross at an [FeatureKind.INTERSECTION].
 */
data class GraphFeature(
    val point: GraphPoint,
    val kind: FeatureKind,
    val curveIds: List<Long>,
)

/**
 * A curve reduced to connected polyline runs in graph space. A new run begins wherever the
 * curve is undefined or jumps (an asymptote), so runs never bridge a discontinuity — which
 * is what stops `tan(x)` from appearing to cross every horizontal line at its asymptotes.
 */
class SampledCurve(
    val id: Long,
    val expr: Expression,
    val polar: Boolean,
    val runs: List<List<GraphPoint>>,
)

/**
 * Numeric analysis of the graphed curves: roots, axis intercepts, local extrema, and
 * intersections. Everything is derived from the same polyline sampling used to draw the
 * curves, so a marker always sits on the line the user can see, and so Cartesian and polar
 * curves — including intersections *between* the two kinds — are handled uniformly.
 *
 * Exact values are recovered afterwards by bisection / ternary search wherever the curve is
 * Cartesian; polar features keep their (sub-pixel) polyline estimate.
 */
object GraphAnalysis {

    /** θ samples for a polar curve over [0, 2π]. */
    private const val POLAR_SAMPLES = 2000
    private const val REFINE_ITERS = 80

    private fun evalOrNaN(e: Expression, v: Double, angle: AngleMode): Double =
        try { e.eval(v, angle) } catch (ex: ExpressionError) { Double.NaN }

    // ---- Sampling ----

    /**
     * Samples one curve across the visible window. [columns] is normally the canvas width in
     * pixels, giving one Cartesian sample per pixel column; polar curves ignore it and sweep
     * θ over a full turn (polar angles are always radians, matching how they are drawn).
     */
    fun sample(
        id: Long,
        expr: Expression,
        polar: Boolean,
        angle: AngleMode,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        columns: Int,
    ): SampledCurve {
        val runs = ArrayList<List<GraphPoint>>()
        var current = ArrayList<GraphPoint>()
        // Treat a vertical leap of more than a few screens as a discontinuity, not a segment.
        val jumpLimit = (yMax - yMin) * 4

        fun breakRun() {
            if (current.size > 1) runs.add(current)
            current = ArrayList()
        }

        if (polar) {
            var k = 0
            while (k <= POLAR_SAMPLES) {
                val theta = 2 * PI * k / POLAR_SAMPLES
                val r = evalOrNaN(expr, theta, AngleMode.RADIANS)
                if (r.isFinite()) {
                    current.add(GraphPoint(r * cos(theta), r * sin(theta)))
                } else {
                    breakRun()
                }
                k++
            }
        } else {
            val steps = columns.coerceAtLeast(2)
            var i = 0
            var prevY = Double.NaN
            while (i <= steps) {
                val x = xMin + (xMax - xMin) * i / steps
                val y = evalOrNaN(expr, x, angle)
                if (y.isFinite()) {
                    if (prevY.isFinite() && abs(y - prevY) > jumpLimit) breakRun()
                    current.add(GraphPoint(x, y))
                } else {
                    breakRun()
                }
                prevY = y
                i++
            }
        }
        breakRun()
        return SampledCurve(id, expr, polar, runs)
    }

    // ---- Feature discovery ----

    /**
     * Every notable point within [radius] graph units of [at], nearest first.
     *
     * Only geometry near the tap is examined, so this stays cheap enough to run on each
     * touch no matter how many curves are on screen.
     */
    fun featuresNear(
        curves: List<SampledCurve>,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
    ): List<GraphFeature> {
        val found = ArrayList<GraphFeature>()
        for (curve in curves) singleCurveFeatures(curve, at, radius, angle, found)
        for (i in curves.indices) {
            for (j in i + 1 until curves.size) {
                intersectionFeatures(curves[i], curves[j], at, radius, angle, found)
            }
        }
        return found
            .filter { distance(it.point, at) <= radius }
            .sortedBy { distance(it.point, at) }
            .let(::dedupe)
    }

    /** Roots, y-intercepts and local extrema of a single curve. */
    private fun singleCurveFeatures(
        curve: SampledCurve,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        for (run in curve.runs) {
            for (i in 0 until run.size - 1) {
                val a = run[i]
                val b = run[i + 1]
                if (segmentDistance(at, a, b) > radius) continue

                // Axis crossings. `straddles` accepts an endpoint sitting exactly on the axis,
                // which a strict sign-change test misses whenever a sample lands on it.
                if (straddles(a.y, b.y)) {
                    val x = if (curve.polar) interpolateZero(a.y, b.y, a.x, b.x)
                    else refineZero({ evalOrNaN(curve.expr, it, angle) }, a.x, b.x)
                    out.add(GraphFeature(GraphPoint(x, 0.0), FeatureKind.ROOT, listOf(curve.id)))
                }
                if (straddles(a.x, b.x)) {
                    val y = if (curve.polar) interpolateZero(a.x, b.x, a.y, b.y)
                    else evalOrNaN(curve.expr, 0.0, angle)
                    if (y.isFinite()) {
                        out.add(GraphFeature(GraphPoint(0.0, y), FeatureKind.Y_INTERCEPT, listOf(curve.id)))
                    }
                }
            }
            // Local extrema in y — geometrically the peaks and troughs of the drawn curve,
            // which is meaningful for polar curves too.
            for (i in 1 until run.size - 1) {
                val p = run[i]
                if (distance(p, at) > radius) continue
                val isMax = p.y > run[i - 1].y && p.y > run[i + 1].y
                val isMin = p.y < run[i - 1].y && p.y < run[i + 1].y
                if (!isMax && !isMin) continue
                val kind = if (isMax) FeatureKind.MAXIMUM else FeatureKind.MINIMUM
                val point = if (curve.polar) p else {
                    val x = refineExtremum({ evalOrNaN(curve.expr, it, angle) }, run[i - 1].x, run[i + 1].x, isMax)
                    val y = evalOrNaN(curve.expr, x, angle)
                    if (y.isFinite()) GraphPoint(x, y) else p
                }
                out.add(GraphFeature(point, kind, listOf(curve.id)))
            }
        }
    }

    /**
     * Crossings between two curves. Works for any combination of Cartesian and polar because
     * it intersects the sampled polylines geometrically; Cartesian pairs are then refined on
     * `f - g`, and are additionally checked for tangential touches, which a purely geometric
     * crossing test cannot see.
     */
    private fun intersectionFeatures(
        a: SampledCurve,
        b: SampledCurve,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        val ids = listOf(a.id, b.id)
        for (runA in a.runs) {
            for (i in 0 until runA.size - 1) {
                val p1 = runA[i]
                val p2 = runA[i + 1]
                if (segmentDistance(at, p1, p2) > radius) continue
                for (runB in b.runs) {
                    for (j in 0 until runB.size - 1) {
                        val q1 = runB[j]
                        val q2 = runB[j + 1]
                        if (segmentDistance(at, q1, q2) > radius) continue
                        val hit = segmentIntersection(p1, p2, q1, q2) ?: continue
                        val point = if (!a.polar && !b.polar) {
                            val lo = minOf(p1.x, p2.x, q1.x, q2.x)
                            val hi = maxOf(p1.x, p2.x, q1.x, q2.x)
                            val diff = { x: Double -> evalOrNaN(a.expr, x, angle) - evalOrNaN(b.expr, x, angle) }
                            val x = refineZero(diff, lo, hi)
                            val y = evalOrNaN(a.expr, x, angle)
                            if (y.isFinite()) GraphPoint(x, y) else hit
                        } else hit
                        out.add(GraphFeature(point, FeatureKind.INTERSECTION, ids))
                    }
                }
            }
        }
        if (!a.polar && !b.polar) tangentialTouches(a, b, at, radius, angle, out)
    }

    /**
     * Points where two Cartesian curves touch without crossing (`x²` and `2x-1` at x = 1).
     * `f - g` has a local minimum in absolute value there but never changes sign, so this
     * looks for those minima and accepts the ones that reach zero to within a fraction of a
     * pixel — [radius] stands in for the touch tolerance's scale.
     */
    private fun tangentialTouches(
        a: SampledCurve,
        b: SampledCurve,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        val diff = { x: Double -> evalOrNaN(a.expr, x, angle) - evalOrNaN(b.expr, x, angle) }
        val steps = 240
        val lo = at.x - radius
        val hi = at.x + radius
        val tolerance = radius * 1e-3
        var prev = diff(lo)
        var cur = diff(lo + (hi - lo) / steps)
        for (i in 2..steps) {
            val x = lo + (hi - lo) * i / steps
            val next = diff(x)
            if (prev.isFinite() && cur.isFinite() && next.isFinite()) {
                val xPrev = lo + (hi - lo) * (i - 2) / steps
                val isTrough = abs(cur) < abs(prev) && abs(cur) < abs(next)
                if (isTrough) {
                    val xt = refineExtremum({ -abs(diff(it)) }, xPrev, x, wantMax = true)
                    val dv = diff(xt)
                    if (dv.isFinite() && abs(dv) <= tolerance) {
                        val y = evalOrNaN(a.expr, xt, angle)
                        if (y.isFinite()) {
                            out.add(GraphFeature(GraphPoint(xt, y), FeatureKind.INTERSECTION, listOf(a.id, b.id)))
                        }
                    }
                }
            }
            prev = cur
            cur = next
        }
    }

    // ---- Numeric helpers ----

    /**
     * Whether a sign change happens between [a] and [b], *including* the case where one of
     * them is exactly zero. Requiring a strict `a * b < 0` was the long-standing bug here: a
     * viewport centred on the origin makes samples land exactly on the nice crossing points,
     * so the product is `0`, not negative, and the crossing was skipped every time.
     */
    private fun straddles(a: Double, b: Double): Boolean {
        if (!a.isFinite() || !b.isFinite()) return false
        return (a <= 0.0 && b >= 0.0) || (a >= 0.0 && b <= 0.0)
    }

    /** Linear interpolation of the parameter where `va → vb` crosses zero. */
    private fun interpolateZero(va: Double, vb: Double, pa: Double, pb: Double): Double {
        val d = vb - va
        if (d == 0.0) return pa
        return pa + (pb - pa) * (-va / d)
    }

    /** Bisection for `f(x) = 0` on a bracketing interval, tolerant of exact zeros. */
    private fun refineZero(f: (Double) -> Double, lo0: Double, hi0: Double): Double {
        var lo = lo0
        var hi = hi0
        var flo = f(lo)
        val fhi = f(hi)
        if (flo == 0.0) return lo
        if (fhi == 0.0) return hi
        if (!flo.isFinite() || !fhi.isFinite() || !straddles(flo, fhi)) return (lo + hi) / 2
        repeat(REFINE_ITERS) {
            val mid = (lo + hi) / 2
            val fmid = f(mid)
            if (fmid == 0.0 || !fmid.isFinite()) return mid
            if (straddles(flo, fmid)) hi = mid else { lo = mid; flo = fmid }
        }
        return (lo + hi) / 2
    }

    /** Ternary search for the extremum of a unimodal bracket. */
    private fun refineExtremum(f: (Double) -> Double, lo0: Double, hi0: Double, wantMax: Boolean): Double {
        var lo = lo0
        var hi = hi0
        repeat(REFINE_ITERS) {
            val m1 = lo + (hi - lo) / 3
            val m2 = hi - (hi - lo) / 3
            val f1 = f(m1)
            val f2 = f(m2)
            if (!f1.isFinite() || !f2.isFinite()) return (lo + hi) / 2
            val keepLeft = if (wantMax) f1 > f2 else f1 < f2
            if (keepLeft) hi = m2 else lo = m1
        }
        return (lo + hi) / 2
    }

    // ---- Geometry ----

    private fun distance(a: GraphPoint, b: GraphPoint) = hypot(a.x - b.x, a.y - b.y)

    /** Shortest distance from [p] to the segment `a→b`. */
    private fun segmentDistance(p: GraphPoint, a: GraphPoint, b: GraphPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return distance(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Where segments `p1→p2` and `q1→q2` cross, or null if they don't. */
    private fun segmentIntersection(p1: GraphPoint, p2: GraphPoint, q1: GraphPoint, q2: GraphPoint): GraphPoint? {
        val rx = p2.x - p1.x
        val ry = p2.y - p1.y
        val sx = q2.x - q1.x
        val sy = q2.y - q1.y
        val denom = rx * sy - ry * sx
        if (denom == 0.0) return null // parallel or collinear
        val qpx = q1.x - p1.x
        val qpy = q1.y - p1.y
        val t = (qpx * sy - qpy * sx) / denom
        val u = (qpx * ry - qpy * rx) / denom
        if (t < 0.0 || t > 1.0 || u < 0.0 || u > 1.0) return null
        return GraphPoint(p1.x + t * rx, p1.y + t * ry)
    }

    /** Collapses features that describe the same point, keeping the most specific kind. */
    private fun dedupe(sorted: List<GraphFeature>): List<GraphFeature> {
        val out = ArrayList<GraphFeature>()
        for (f in sorted) {
            val scale = 1e-6 * (1 + abs(f.point.x) + abs(f.point.y))
            if (out.none { distance(it.point, f.point) <= scale }) out.add(f)
        }
        return out
    }
}
