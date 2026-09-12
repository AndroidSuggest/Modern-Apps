package com.vayunmathur.astronomy.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.vayunmathur.astronomy.domain.engine.AltAz
import com.vayunmathur.astronomy.domain.projection.StereographicProjection
import com.vayunmathur.astronomy.platform.VisibleArt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val ART_MESH = 12

/**
 * Draw a constellation figure so it lies on the celestial sphere. The 3 anchors
 * define an affine map from image pixels to 3D sky direction; a subdivided mesh
 * of that map is then run through the real sphere projection, so the artwork
 * curves and tracks with the stars instead of being a flat plane.
 */
internal fun DrawScope.drawConstellationArt(
    projection: StereographicProjection,
    bmp: android.graphics.Bitmap,
    art: VisibleArt,
    paint: android.graphics.Paint
) {
    val a0 = art.anchors[0]; val a1 = art.anchors[1]; val a2 = art.anchors[2]
    val v0 = altAzToVec(a0.altAz); val v1 = altAzToVec(a1.altAz); val v2 = altAzToVec(a2.altAz)
    val inv = invert3x3(
        a0.srcX.toDouble(), a0.srcY.toDouble(), 1.0,
        a1.srcX.toDouble(), a1.srcY.toDouble(), 1.0,
        a2.srcX.toDouble(), a2.srcY.toDouble(), 1.0
    ) ?: return
    // Per-component coefficients (a,b,c) with comp(px,py) = a*px + b*py + c.
    fun coef(c0: Double, c1: Double, c2: Double) = doubleArrayOf(
        inv[0]*c0 + inv[1]*c1 + inv[2]*c2,
        inv[3]*c0 + inv[4]*c1 + inv[5]*c2,
        inv[6]*c0 + inv[7]*c1 + inv[8]*c2
    )
    val cx = coef(v0[0], v1[0], v2[0])
    val cy = coef(v0[1], v1[1], v2[1])
    val cz = coef(v0[2], v1[2], v2[2])

    val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
    val n = ART_MESH
    val verts = FloatArray((n + 1) * (n + 1) * 2)
    var k = 0
    for (j in 0..n) {
        val py = bh * j / n
        for (i in 0..n) {
            val px = bw * i / n
            var vx = cx[0]*px + cx[1]*py + cx[2]
            var vy = cy[0]*px + cy[1]*py + cy[2]
            var vz = cz[0]*px + cz[1]*py + cz[2]
            val len = sqrt(vx*vx + vy*vy + vz*vz)
            if (len < 1e-9) return
            vx /= len; vy /= len; vz /= len
            val alt = asin(vz.coerceIn(-1.0, 1.0))
            val az = atan2(vx, vy).let { if (it < 0) it + 2*PI else it }
            val off = projection.projectMesh(AltAz(az, alt)) ?: return
            verts[k++] = off.x; verts[k++] = off.y
        }
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmapMesh(bmp, n, n, verts, 0, null, 0, paint)
    }
}

internal fun altAzToVec(a: AltAz): DoubleArray {
    val ca = cos(a.altRad)
    return doubleArrayOf(ca * sin(a.azRad), ca * cos(a.azRad), sin(a.altRad))
}

internal fun invert3x3(
    m0: Double, m1: Double, m2: Double,
    m3: Double, m4: Double, m5: Double,
    m6: Double, m7: Double, m8: Double
): DoubleArray? {
    val det = m0*(m4*m8 - m5*m7) - m1*(m3*m8 - m5*m6) + m2*(m3*m7 - m4*m6)
    if (abs(det) < 1e-12) return null
    val id = 1.0 / det
    return doubleArrayOf(
        (m4*m8 - m5*m7)*id, (m2*m7 - m1*m8)*id, (m1*m5 - m2*m4)*id,
        (m5*m6 - m3*m8)*id, (m0*m8 - m2*m6)*id, (m2*m3 - m0*m5)*id,
        (m3*m7 - m4*m6)*id, (m1*m6 - m0*m7)*id, (m0*m4 - m1*m3)*id
    )
}

internal fun starRadius(mag: Double): Float = (4.2 - mag * 0.55).toFloat().coerceIn(0.9f, 5.5f)

internal fun bvToColor(bv: Double): Color = when {
    bv < -0.2 -> Color(0xFF9BB0FF)
    bv < 0.0 -> Color(0xFFC6D2FF)
    bv < 0.3 -> Color.White
    bv < 0.6 -> Color(0xFFFFFFE0)
    bv < 1.0 -> Color(0xFFFFE4B5)
    bv < 1.4 -> Color(0xFFFFA500)
    else -> Color(0xFFFF6347)
}

internal fun planetColor(id: String): Color = when (id) {
    "MERCURY" -> Color(0xFFA9A9A9)
    "VENUS" -> Color(0xFFFFE0B0)
    "MARS" -> Color(0xFFFF4500)
    "JUPITER" -> Color(0xFFDEB887)
    "SATURN" -> Color(0xFFF0E68C)
    "URANUS" -> Color(0xFFADD8E6)
    "NEPTUNE" -> Color(0xFF4169E1)
    else -> Color.White
}
