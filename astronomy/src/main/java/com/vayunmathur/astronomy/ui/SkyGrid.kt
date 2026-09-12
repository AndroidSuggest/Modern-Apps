package com.vayunmathur.astronomy.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.projection.SkyProjection
import com.vayunmathur.astronomy.domain.projection.ViewState

/**
 * Whole-sphere azimuth + altitude graticule:
 * - parallels: alt = -80..+80 step 15 deg (visible + invisible altitude circles)
 * - meridians: az = 0..360 step 30 deg, alt -90..+90 step 3 deg
 * Handles below-horizon lines and dashed wrap-around by segmenting invisible gaps.
 */
internal fun DrawScope.drawGrid(projection: SkyProjection, viewState: ViewState) {
    val gridMajor = Color(0x33FFFFFF)
    val gridMinor = Color(0x18FFFFFF)
    // Parallels: altitude circles (constant alt, sweep az 0..360)
    // Whole sphere — includes negative altitudes for below horizon when viewer tilts down / device pointing
    for (altDeg in -75..75 step 15) {
        if (altDeg == 0) continue // horizon drawn separately with distinct color
        val color = if (altDeg % 30 == 0) gridMajor else gridMinor
        var currentPath: Path? = null
        var lastVisible = false
        for (azDeg in 0..360 step 2) {
            val aa = AltAz(Math.toRadians(azDeg.toDouble()), Math.toRadians(altDeg.toDouble()))
            val off = projection.project(aa)
            if (off != null) {
                if (currentPath == null || !lastVisible) {
                    currentPath = Path().apply { moveTo(off.x, off.y) }
                } else {
                    currentPath.lineTo(off.x, off.y)
                }
                lastVisible = true
            } else {
                if (currentPath != null && lastVisible) {
                    drawPath(currentPath, color, style = Stroke(0.8f))
                    currentPath = null
                }
                lastVisible = false
            }
        }
        if (currentPath != null) drawPath(currentPath, color, style = Stroke(0.8f))
    }
    // Meridians: azimuth lines (constant az, sweep alt -90..+90)
    for (azDeg in 0..330 step 30) {
        val color = if (azDeg % 90 == 0) gridMajor else gridMinor
        var currentPath: Path? = null
        var lastVisible = false
        for (altDeg in -90..90 step 2) {
            // avoid poles singularity where az undefined at alt=90
            val aa = AltAz(Math.toRadians(azDeg.toDouble()), Math.toRadians(altDeg.toDouble()))
            val off = projection.project(aa)
            if (off != null) {
                if (currentPath == null || !lastVisible) {
                    currentPath = Path().apply { moveTo(off.x, off.y) }
                } else {
                    currentPath.lineTo(off.x, off.y)
                }
                lastVisible = true
            } else {
                if (currentPath != null && lastVisible) {
                    drawPath(currentPath, color, style = Stroke(0.8f))
                    currentPath = null
                }
                lastVisible = false
            }
        }
        if (currentPath != null) drawPath(currentPath, color, style = Stroke(0.8f))
    }
}

internal fun DrawScope.drawHorizon(projection: SkyProjection, viewState: ViewState) {
    val path = Path(); var first = true; val col = Color(0x553CB371)
    for (azDeg in 0..360 step 2) {
        val aa = AltAz(Math.toRadians(azDeg.toDouble()), 0.0)
        val off = projection.project(aa) ?: continue
        if (first) { path.moveTo(off.x, off.y); first = false } else path.lineTo(off.x, off.y)
    }
    drawPath(path, col, style = Stroke(1.4f))
}

internal fun DrawScope.drawCardinalLabels(projection: SkyProjection, viewState: ViewState, measurer: androidx.compose.ui.text.TextMeasurer) {
    listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, azDeg) ->
        val aa = AltAz(Math.toRadians(azDeg), 0.0)
        val off = projection.project(aa) ?: return@forEach
        drawText(measurer, label, topLeft = off + Offset(-6f, 6f), style = TextStyle(Color(0xFF9E9E9E), fontSize = 12.sp))
    }
}
