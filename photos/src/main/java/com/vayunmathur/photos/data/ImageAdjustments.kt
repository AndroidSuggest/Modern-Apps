package com.vayunmathur.photos.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.createBitmap
import kotlin.math.pow
import kotlin.random.Random

data class ImageAdjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpness: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val fade: Float = 0f,
    val tint: Float = 0f,
)

data class PhotoFilter(
    val name: String,
    val adjustments: ImageAdjustments,
)

object PhotoFilters {
    val all: List<PhotoFilter> = listOf(
        PhotoFilter("None", ImageAdjustments()),
        PhotoFilter("Vivid", ImageAdjustments(contrast = 25f, saturation = 40f)),
        PhotoFilter("Vivid Warm", ImageAdjustments(contrast = 25f, saturation = 40f, warmth = 30f)),
        PhotoFilter("Vivid Cool", ImageAdjustments(contrast = 25f, saturation = 40f, warmth = -30f)),
        PhotoFilter("Dramatic", ImageAdjustments(contrast = 50f, brightness = -15f, saturation = -10f)),
        PhotoFilter("Dramatic Warm", ImageAdjustments(contrast = 50f, brightness = -15f, saturation = -10f, warmth = 25f)),
        PhotoFilter("Dramatic Cool", ImageAdjustments(contrast = 50f, brightness = -15f, saturation = -10f, warmth = -25f)),
        PhotoFilter("Mono", ImageAdjustments(saturation = -100f)),
        PhotoFilter("Silvertone", ImageAdjustments(saturation = -100f, warmth = 15f, contrast = 10f)),
        PhotoFilter("Noir", ImageAdjustments(saturation = -100f, contrast = 40f, brightness = -20f)),
        PhotoFilter("Vintage", ImageAdjustments(warmth = 25f, saturation = -20f, fade = 20f, contrast = -10f)),
        PhotoFilter("Sepia", ImageAdjustments(warmth = 40f, saturation = -50f, brightness = 5f)),
        PhotoFilter("Chrome", ImageAdjustments(contrast = 30f, tint = -10f, saturation = 10f)),
        PhotoFilter("Fade", ImageAdjustments(fade = 35f, contrast = -15f, brightness = 10f)),
        PhotoFilter("Warm Sunset", ImageAdjustments(warmth = 50f, saturation = 15f, brightness = 5f)),
        PhotoFilter("Cool Ocean", ImageAdjustments(warmth = -40f, tint = -15f, saturation = 10f)),
        PhotoFilter("Film Grain", ImageAdjustments(warmth = 15f, grain = 30f, fade = 15f, contrast = 10f)),
        PhotoFilter("Cinematic", ImageAdjustments(contrast = 35f, warmth = 10f, tint = -20f, saturation = -15f, shadows = 15f)),
    )
}

fun ImageAdjustments.toColorMatrix(): ColorMatrix {
    val result = ColorMatrix()

    if (brightness != 0f) {
        val v = brightness / 100f * 128f
        val m = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, v,
            0f, 1f, 0f, 0f, v,
            0f, 0f, 1f, 0f, v,
            0f, 0f, 0f, 1f, 0f,
        ))
        result.postConcat(m)
    }

    if (contrast != 0f) {
        val s = 1f + contrast / 100f
        val t = (-0.5f * s + 0.5f) * 255f
        val m = ColorMatrix(floatArrayOf(
            s, 0f, 0f, 0f, t,
            0f, s, 0f, 0f, t,
            0f, 0f, s, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        ))
        result.postConcat(m)
    }

    if (saturation != 0f) {
        val m = ColorMatrix()
        m.setSaturation((1f + saturation / 100f).coerceAtLeast(0f))
        result.postConcat(m)
    }

    if (warmth != 0f) {
        val w = warmth / 100f * 30f
        val m = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, w,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, -w,
            0f, 0f, 0f, 1f, 0f,
        ))
        result.postConcat(m)
    }

    if (exposure != 0f) {
        val e = 2f.pow(exposure / 100f)
        val m = ColorMatrix(floatArrayOf(
            e, 0f, 0f, 0f, 0f,
            0f, e, 0f, 0f, 0f,
            0f, 0f, e, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        result.postConcat(m)
    }

    if (highlights != 0f) {
        val h = highlights / 100f * 0.3f
        val m = ColorMatrix(floatArrayOf(
            1f + h, 0f, 0f, 0f, h * 40f,
            0f, 1f + h, 0f, 0f, h * 40f,
            0f, 0f, 1f + h, 0f, h * 40f,
            0f, 0f, 0f, 1f, 0f,
        ))
        result.postConcat(m)
    }

    if (shadows != 0f) {
        val s = shadows / 100f * 40f
        val m = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, s,
            0f, 1f, 0f, 0f, s,
            0f, 0f, 1f, 0f, s,
            0f, 0f, 0f, 1f, 0f,
        ))
        result.postConcat(m)
    }

    if (fade != 0f) {
        val f = fade / 100f
        val scale = 1f - f * 0.4f
        val offset = f * 0.4f * 128f
        val m = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        ))
        result.postConcat(m)
    }

    if (tint != 0f) {
        val t = tint / 100f * 25f
        val m = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, -t * 0.5f,
            0f, 1f, 0f, 0f, t,
            0f, 0f, 1f, 0f, -t * 0.5f,
            0f, 0f, 0f, 1f, 0f,
        ))
        result.postConcat(m)
    }

    return result
}

fun ImageAdjustments.applyToBitmap(bitmap: Bitmap): Bitmap {
    var result = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    val cm = toColorMatrix()
    if (!cm.array.contentEquals(ColorMatrix().array)) {
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    }

    if (hasPixelEffects()) {
        result = applyPixelEffects(result)
    }

    return result
}

fun ImageAdjustments.hasPixelEffects(): Boolean =
    sharpness != 0f || vignette != 0f || grain != 0f

fun ImageAdjustments.applyPixelEffects(bitmap: Bitmap): Bitmap {
    if (!hasPixelEffects()) return bitmap
    var result = bitmap
    if (sharpness > 0f) {
        result = applySharpen(if (result === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, true) else result, sharpness / 100f)
    }
    if (vignette > 0f) {
        if (result === bitmap) result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        applyVignette(result, vignette / 100f)
    }
    if (grain > 0f) {
        if (result === bitmap) result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        applyGrain(result, grain / 100f)
    }
    return result
}

private fun applySharpen(src: Bitmap, amount: Float): Bitmap {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)

    val out = IntArray(w * h)
    val strength = amount * 1.5f
    val center = 1f + 4f * strength
    val side = -strength

    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val idx = y * w + x
            val c = pixels[idx]
            val t = pixels[(y - 1) * w + x]
            val b = pixels[(y + 1) * w + x]
            val l = pixels[y * w + (x - 1)]
            val r = pixels[y * w + (x + 1)]

            fun ch(color: Int, shift: Int): Int {
                val cv = ((color shr shift) and 0xFF).toFloat()
                val tv = ((t shr shift) and 0xFF).toFloat()
                val bv = ((b shr shift) and 0xFF).toFloat()
                val lv = ((l shr shift) and 0xFF).toFloat()
                val rv = ((r shr shift) and 0xFF).toFloat()
                return (cv * center + tv * side + bv * side + lv * side + rv * side)
                    .toInt().coerceIn(0, 255)
            }

            val a = (c shr 24) and 0xFF
            out[idx] = (a shl 24) or (ch(c, 16) shl 16) or (ch(c, 8) shl 8) or ch(c, 0)
        }
    }

    for (x in 0 until w) {
        out[x] = pixels[x]
        out[(h - 1) * w + x] = pixels[(h - 1) * w + x]
    }
    for (y in 0 until h) {
        out[y * w] = pixels[y * w]
        out[y * w + w - 1] = pixels[y * w + w - 1]
    }

    val result = createBitmap(w, h)
    result.setPixels(out, 0, w, 0, 0, w, h)
    src.recycle()
    return result
}

private fun applyVignette(bitmap: Bitmap, amount: Float) {
    val w = bitmap.width
    val h = bitmap.height
    val canvas = Canvas(bitmap)
    val cx = w / 2f
    val cy = h / 2f
    val radius = kotlin.math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()

    val gradient = RadialGradient(
        cx, cy, radius,
        intArrayOf(0x00000000.toInt(), 0x00000000.toInt(), (((amount * 200).toInt().coerceAtMost(255)) shl 24)),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )
    val paint = Paint().apply {
        shader = gradient
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
}

private fun applyGrain(bitmap: Bitmap, amount: Float) {
    val intensity = (amount * 50f).toInt()
    val rng = Random(42)
    bitmap.mapPixels { pixel ->
        val noise = rng.nextInt(-intensity, intensity + 1)
        val a = (pixel shr 24) and 0xFF
        val r = (((pixel shr 16) and 0xFF) + noise).coerceIn(0, 255)
        val g = (((pixel shr 8) and 0xFF) + noise).coerceIn(0, 255)
        val b = ((pixel and 0xFF) + noise).coerceIn(0, 255)
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
