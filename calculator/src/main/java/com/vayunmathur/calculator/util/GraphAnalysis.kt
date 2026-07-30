package com.vayunmathur.calculator.util

import kotlin.math.abs

/**
 * Numeric analysis of graphed functions over the visible x-window: roots (x-intercepts),
 * local minima/maxima, and intersections between two curves. All methods sample the range
 * densely, bracket features by sign/slope changes, then refine — good enough for an
 * on-device grapher without a symbolic engine.
 */
object GraphAnalysis {

    private const val SAMPLES = 1000
    private const val REFINE_ITERS = 60

    private fun evalOrNaN(e: Expression, x: Double, angle: AngleMode): Double =
        try { e.eval(x, angle) } catch (ex: ExpressionError) { Double.NaN }

    /** x-values where `f(x) = 0` in `[xMin, xMax]`, via sign-change bracketing + bisection. */
    fun roots(e: Expression, angle: AngleMode, xMin: Double, xMax: Double): List<Double> {
        val out = ArrayList<Double>()
        val step = (xMax - xMin) / SAMPLES
        var prevX = xMin
        var prevY = evalOrNaN(e, prevX, angle)
        var i = 1
        while (i <= SAMPLES) {
            val x = xMin + i * step
            val y = evalOrNaN(e, x, angle)
            if (prevY.isFinite() && y.isFinite()) {
                if (prevY == 0.0) addUnique(out, prevX)
                // A sign change that isn't a discontinuity jump brackets a root.
                else if (prevY * y < 0 && abs(y - prevY) < 1e6) {
                    addUnique(out, bisect(e, angle, prevX, x))
                }
            }
            prevX = x; prevY = y; i++
        }
        return out
    }

    /** Local minima (or maxima if [wantMax]) in `[xMin, xMax]`, via slope-sign changes. */
    fun extrema(e: Expression, angle: AngleMode, xMin: Double, xMax: Double, wantMax: Boolean): List<Double> {
        val out = ArrayList<Double>()
        val step = (xMax - xMin) / SAMPLES
        var a = xMin
        var fa = evalOrNaN(e, a, angle)
        var b = xMin + step
        var fb = evalOrNaN(e, b, angle)
        var i = 2
        while (i <= SAMPLES) {
            val c = xMin + i * step
            val fc = evalOrNaN(e, c, angle)
            if (fa.isFinite() && fb.isFinite() && fc.isFinite()) {
                val isMax = fb > fa && fb > fc
                val isMin = fb < fa && fb < fc
                if ((wantMax && isMax) || (!wantMax && isMin)) {
                    addUnique(out, ternary(e, angle, a, c, wantMax))
                }
            }
            a = b; fa = fb; b = c; fb = fc; i++
        }
        return out
    }

    /** x-values where `f(x) = g(x)` — the roots of `f - g`. */
    fun intersections(f: Expression, g: Expression, angle: AngleMode, xMin: Double, xMax: Double): List<Double> {
        val out = ArrayList<Double>()
        val step = (xMax - xMin) / SAMPLES
        fun diff(x: Double): Double {
            val a = evalOrNaN(f, x, angle); val b = evalOrNaN(g, x, angle)
            return a - b
        }
        var prevX = xMin
        var prevY = diff(prevX)
        var i = 1
        while (i <= SAMPLES) {
            val x = xMin + i * step
            val y = diff(x)
            if (prevY.isFinite() && y.isFinite() && prevY * y < 0 && abs(y - prevY) < 1e6) {
                out.add(bisectDiff(f, g, angle, prevX, x))
            }
            prevX = x; prevY = y; i++
        }
        return out
    }

    // --- refinement ---

    private fun bisect(e: Expression, angle: AngleMode, lo0: Double, hi0: Double): Double {
        var lo = lo0; var hi = hi0
        var flo = evalOrNaN(e, lo, angle)
        repeat(REFINE_ITERS) {
            val mid = (lo + hi) / 2
            val fmid = evalOrNaN(e, mid, angle)
            if (fmid == 0.0 || !fmid.isFinite()) return mid
            if (flo * fmid < 0) hi = mid else { lo = mid; flo = fmid }
        }
        return (lo + hi) / 2
    }

    private fun bisectDiff(f: Expression, g: Expression, angle: AngleMode, lo0: Double, hi0: Double): Double {
        var lo = lo0; var hi = hi0
        fun d(x: Double) = evalOrNaN(f, x, angle) - evalOrNaN(g, x, angle)
        var flo = d(lo)
        repeat(REFINE_ITERS) {
            val mid = (lo + hi) / 2
            val fmid = d(mid)
            if (fmid == 0.0 || !fmid.isFinite()) return mid
            if (flo * fmid < 0) hi = mid else { lo = mid; flo = fmid }
        }
        return (lo + hi) / 2
    }

    /** Ternary search for the extremum of a unimodal bracket `[lo, hi]`. */
    private fun ternary(e: Expression, angle: AngleMode, lo0: Double, hi0: Double, wantMax: Boolean): Double {
        var lo = lo0; var hi = hi0
        repeat(REFINE_ITERS) {
            val m1 = lo + (hi - lo) / 3
            val m2 = hi - (hi - lo) / 3
            val f1 = evalOrNaN(e, m1, angle)
            val f2 = evalOrNaN(e, m2, angle)
            val keepLeft = if (wantMax) f1 > f2 else f1 < f2
            if (keepLeft) hi = m2 else lo = m1
        }
        return (lo + hi) / 2
    }

    /** Add [x] unless a near-duplicate is already present (features found twice by sampling). */
    private fun addUnique(list: MutableList<Double>, x: Double) {
        if (list.none { abs(it - x) < 1e-4 * (1 + abs(x)) }) list.add(x)
    }
}
