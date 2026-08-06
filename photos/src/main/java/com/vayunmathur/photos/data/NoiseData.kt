package com.vayunmathur.photos.data

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap

data class NoiseParams(
    val amount: Float = 0f,
    val monochrome: Boolean = true,
) {
    fun isIdentity(): Boolean = amount == 0f
}

fun NoiseParams.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    val intensity = (amount / 100f * 80f).toInt()
    val random = kotlin.random.Random(42)
    val output = IntArray(w * h)

    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val origR = (p ushr 16) and 0xFF
        val origG = (p ushr 8) and 0xFF
        val origB = p and 0xFF

        val r: Int
        val g: Int
        val b: Int
        if (monochrome) {
            val n = random.nextInt(-intensity, intensity + 1)
            r = (origR + n).coerceIn(0, 255)
            g = (origG + n).coerceIn(0, 255)
            b = (origB + n).coerceIn(0, 255)
        } else {
            r = (origR + random.nextInt(-intensity, intensity + 1)).coerceIn(0, 255)
            g = (origG + random.nextInt(-intensity, intensity + 1)).coerceIn(0, 255)
            b = (origB + random.nextInt(-intensity, intensity + 1)).coerceIn(0, 255)
        }

        output[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    val result = createBitmap(w, h)
    result.setPixels(output, 0, w, 0, 0, w, h)
    return result
}
