package com.vayunmathur.photos.data

import androidx.annotation.StringRes
import com.vayunmathur.photos.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Photoshop-style layer blend modes. Blending is implemented per-pixel using the
 * W3C compositing & blending formulas so that all modes (including the non-separable
 * Hue/Saturation/Color/Luminosity) behave consistently across API levels. The
 * compositor renders at 512px during editing and full-res only on export, so the
 * per-pixel cost is acceptable.
 */
enum class LayerBlendMode(@StringRes val labelRes: Int) {
    Normal(R.string.blend_normal),
    Multiply(R.string.blend_multiply),
    Screen(R.string.blend_screen),
    Overlay(R.string.blend_overlay),
    Darken(R.string.blend_darken),
    Lighten(R.string.blend_lighten),
    ColorDodge(R.string.blend_color_dodge),
    ColorBurn(R.string.blend_color_burn),
    HardLight(R.string.blend_hard_light),
    SoftLight(R.string.blend_soft_light),
    Difference(R.string.blend_difference),
    Exclusion(R.string.blend_exclusion),
    Hue(R.string.blend_hue),
    Saturation(R.string.blend_saturation),
    Color(R.string.color),
    Luminosity(R.string.blend_luminosity);

    private val separable: Boolean
        get() = this != Hue && this != Saturation && this != Color && this != Luminosity

    /**
     * Composites a source ARGB pixel over a backdrop ARGB pixel using this blend mode.
     *
     * @param dst backdrop pixel (ARGB), the composite-so-far.
     * @param src source pixel (ARGB), the layer being drawn.
     * @param extraAlpha additional source alpha multiplier in 0..1 (layer opacity * mask).
     * @return the composited ARGB pixel.
     */
    fun blendPixel(dst: Int, src: Int, extraAlpha: Float): Int {
        val sa = (((src ushr 24) and 0xFF) / 255f) * extraAlpha.coerceIn(0f, 1f)
        if (sa <= 0f) return dst
        val ba = ((dst ushr 24) and 0xFF) / 255f

        val sr = ((src ushr 16) and 0xFF) / 255f
        val sg = ((src ushr 8) and 0xFF) / 255f
        val sb = (src and 0xFF) / 255f
        val br = ((dst ushr 16) and 0xFF) / 255f
        val bg = ((dst ushr 8) and 0xFF) / 255f
        val bb = (dst and 0xFF) / 255f

        // Blended color B(Cb, Cs).
        val mr: Float
        val mg: Float
        val mb: Float
        if (separable) {
            mr = blendChannel(br, sr)
            mg = blendChannel(bg, sg)
            mb = blendChannel(bb, sb)
        } else {
            val blended = blendNonSeparable(br, bg, bb, sr, sg, sb)
            mr = blended[0]; mg = blended[1]; mb = blended[2]
        }

        // Cs' = (1 - ab) * Cs + ab * B(Cb, Cs)
        val csr = (1f - ba) * sr + ba * mr
        val csg = (1f - ba) * sg + ba * mg
        val csb = (1f - ba) * sb + ba * mb

        // ao = as + ab * (1 - as)
        val ao = sa + ba * (1f - sa)
        if (ao <= 0f) return 0

        // Co = as * Cs' + ab * (1 - as) * Cb   (premultiplied), then un-premultiply.
        val outR = (sa * csr + ba * (1f - sa) * br) / ao
        val outG = (sa * csg + ba * (1f - sa) * bg) / ao
        val outB = (sa * csb + ba * (1f - sa) * bb) / ao

        return (to255(ao) shl 24) or (to255(outR) shl 16) or (to255(outG) shl 8) or to255(outB)
    }

    private fun blendChannel(cb: Float, cs: Float): Float = when (this) {
        Normal -> cs
        Multiply -> cb * cs
        Screen -> cb + cs - cb * cs
        Overlay -> hardLight(cs, cb)
        Darken -> min(cb, cs)
        Lighten -> max(cb, cs)
        ColorDodge -> when {
            cb <= 0f -> 0f
            cs >= 1f -> 1f
            else -> min(1f, cb / (1f - cs))
        }
        ColorBurn -> when {
            cb >= 1f -> 1f
            cs <= 0f -> 0f
            else -> 1f - min(1f, (1f - cb) / cs)
        }
        HardLight -> hardLight(cb, cs)
        SoftLight -> softLight(cb, cs)
        Difference -> abs(cb - cs)
        Exclusion -> cb + cs - 2f * cb * cs
        else -> cs
    }

    private fun blendNonSeparable(
        br: Float, bg: Float, bb: Float,
        sr: Float, sg: Float, sb: Float,
    ): FloatArray = when (this) {
        Hue -> setLum(setSat(floatArrayOf(sr, sg, sb), sat(br, bg, bb)), lum(br, bg, bb))
        Saturation -> setLum(setSat(floatArrayOf(br, bg, bb), sat(sr, sg, sb)), lum(br, bg, bb))
        Color -> setLum(floatArrayOf(sr, sg, sb), lum(br, bg, bb))
        Luminosity -> setLum(floatArrayOf(br, bg, bb), lum(sr, sg, sb))
        else -> floatArrayOf(sr, sg, sb)
    }
}

private fun to255(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

private fun hardLight(cb: Float, cs: Float): Float =
    if (cs <= 0.5f) cb * (2f * cs)
    else cb + (2f * cs - 1f) - cb * (2f * cs - 1f) // Screen(cb, 2*cs-1)

private fun softLight(cb: Float, cs: Float): Float =
    if (cs <= 0.5f) {
        cb - (1f - 2f * cs) * cb * (1f - cb)
    } else {
        val d = if (cb <= 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else sqrt(cb)
        cb + (2f * cs - 1f) * (d - cb)
    }

private fun lum(r: Float, g: Float, b: Float): Float = 0.3f * r + 0.59f * g + 0.11f * b

private fun clipColor(c: FloatArray): FloatArray {
    val l = lum(c[0], c[1], c[2])
    val n = min(c[0], min(c[1], c[2]))
    val x = max(c[0], max(c[1], c[2]))
    if (n < 0f && l - n != 0f) {
        for (i in 0..2) c[i] = l + (c[i] - l) * l / (l - n)
    }
    if (x > 1f && x - l != 0f) {
        for (i in 0..2) c[i] = l + (c[i] - l) * (1f - l) / (x - l)
    }
    return c
}

private fun setLum(c: FloatArray, l: Float): FloatArray {
    val d = l - lum(c[0], c[1], c[2])
    c[0] += d; c[1] += d; c[2] += d
    return clipColor(c)
}

private fun sat(r: Float, g: Float, b: Float): Float =
    max(r, max(g, b)) - min(r, min(g, b))

private fun setSat(c: FloatArray, s: Float): FloatArray {
    var iMin = 0; var iMid = 1; var iMax = 2
    if (c[iMin] > c[iMid]) { val t = iMin; iMin = iMid; iMid = t }
    if (c[iMid] > c[iMax]) { val t = iMid; iMid = iMax; iMax = t }
    if (c[iMin] > c[iMid]) { val t = iMin; iMin = iMid; iMid = t }
    if (c[iMax] > c[iMin]) {
        c[iMid] = (c[iMid] - c[iMin]) * s / (c[iMax] - c[iMin])
        c[iMax] = s
    } else {
        c[iMid] = 0f
        c[iMax] = 0f
    }
    c[iMin] = 0f
    return c
}
