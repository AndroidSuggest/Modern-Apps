package com.vayunmathur.camera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.vayunmathur.ncnn.PortraitSegmenter

private const val TEMPORAL_WEIGHT = 0.35f

class BokehAnalyzer(
    context: Context,
    private val isFrontFacing: Boolean = false,
    private val onMaskGenerated: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private var prevMask: FloatArray? = null
    private val segmenter = PortraitSegmenter(context)

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        try {
            // toBitmap() returns the raw sensor-oriented buffer; the preview is
            // shown in display orientation (and mirrored for the front camera).
            // Rotate/mirror the frame to match so the mask lines up instead of
            // coming out rotated 90°.
            val raw = imageProxy.toBitmap()
            val bitmap = orientToDisplay(raw, imageProxy.imageInfo.rotationDegrees)

            // Synchronous forward pass; the analyzer runs on a background executor
            // with KEEP_ONLY_LATEST backpressure so blocking here is fine.
            val result = segmenter.segment(bitmap)
            if (bitmap !== raw) bitmap.recycle()
            raw.recycle()
            val w = result.width
            val h = result.height
            val current = result.mask // foreground probability in [0,1], row-major

            // Temporal smoothing: blend with previous frame.
            val prev = prevMask
            val smoothed = if (prev != null && prev.size == current.size) {
                FloatArray(current.size) { i ->
                    current[i] * (1f - TEMPORAL_WEIGHT) + prev[i] * TEMPORAL_WEIGHT
                }
            } else current
            prevMask = smoothed

            // Gaussian blur the mask for soft edges.
            val blurred = blurMask(smoothed, w, h)

            val pixels = IntArray(w * h)
            for (i in pixels.indices) {
                val alpha = (blurred[i].coerceIn(0f, 1f) * 255).toInt()
                pixels[i] = Color.argb(alpha, 255, 255, 255)
            }
            val maskBitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            onMaskGenerated(maskBitmap)
        } catch (e: Throwable) {
            Log.e("BokehAnalyzer", "segmentation failed", e)
        } finally {
            imageProxy.close()
        }
    }

    fun close() {
        segmenter.close()
    }

    /**
     * Rotate the sensor-oriented frame into display orientation (and mirror it
     * horizontally for the front camera) so the produced mask aligns with the
     * preview, which CameraX renders with that same transform.
     */
    private fun orientToDisplay(src: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0 && !isFrontFacing) return src
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        if (isFrontFacing) matrix.postScale(-1f, 1f) // preview mirrors the front lens
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun blurMask(src: FloatArray, w: Int, h: Int): FloatArray {
        // Two-pass separable Gaussian blur (radius 3, sigma ~1.5)
        val kernel = floatArrayOf(0.06f, 0.12f, 0.18f, 0.28f, 0.18f, 0.12f, 0.06f)
        val r = 3
        val temp = FloatArray(w * h)
        // Horizontal pass
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) {
                val sx = (x + k).coerceIn(0, w - 1)
                sum += src[y * w + sx] * kernel[k + r]
            }
            temp[y * w + x] = sum
        }
        // Vertical pass
        val dst = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) {
                val sy = (y + k).coerceIn(0, h - 1)
                sum += temp[sy * w + x] * kernel[k + r]
            }
            dst[y * w + x] = sum
        }
        return dst
    }
}
