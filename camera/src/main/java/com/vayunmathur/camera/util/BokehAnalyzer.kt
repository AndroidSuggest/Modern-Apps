package com.vayunmathur.camera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.vayunmathur.ncnn.PortraitSegmenter

private const val TEMPORAL_WEIGHT = 0.35f
// Throttle segmentation to avoid running more than ~15 fps even if analysis
// delivers faster. KEEP_ONLY_LATEST already drops, but this skips the JNI work early.
private const val MIN_SEGMENT_INTERVAL_MS = 66L
// Preview analysis is now capped to 1024x768, but we still downscale to <=512 max side
// before feeding the 256x256 model to cut toBitmap -> orient allocations.
private const val MAX_PREVIEW_SIDE = 512

class BokehAnalyzer(
    context: Context,
    private val isFrontFacing: Boolean = false,
    private val onMaskGenerated: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private var prevMask: FloatArray? = null
    private var blurTemp: FloatArray? = null
    private var blurDst: FloatArray? = null
    private var pixelBuffer: IntArray? = null
    private var lastSegmentMs: Long = 0L
    @Volatile private var closed = false
    private val segmenter = PortraitSegmenter(context, "erdnet.param", "erdnet.bin")

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        try {
            if (closed) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastSegmentMs < MIN_SEGMENT_INTERVAL_MS) {
                return
            }
            lastSegmentMs = now

            // Raw sensor-oriented buffer; capped to 1024x768 in portrait session,
            // so this is ~0.8MP not 12MP. Full-res final image kept in ImageCapture.
            var frame = imageProxy.toBitmap()
            if (closed) {
                frame.recycle()
                return
            }
            // Immediate downscale to <=512 max side, filter=false to avoid bilinear cost.
            var downscaled = downscaleIfNeeded(frame, MAX_PREVIEW_SIDE)
            if (downscaled !== frame) {
                frame.recycle()
            }
            frame = downscaled

            // Orient to display + mirror for front camera, filter=false (was true = bilinear).
            var oriented = orientToDisplay(frame, imageProxy.imageInfo.rotationDegrees)
            if (oriented !== frame) {
                frame.recycle()
            }
            frame = oriented
            if (closed) {
                frame.recycle()
                return
            }

            // Synchronous forward pass; runs on dedicated bokehExecutor with KEEP_ONLY_LATEST.
            val result = try {
                segmenter.segment(frame)
            } catch (e: IllegalStateException) {
                // PortraitSegmenter is closed – analyzer disposed while frame in-flight
                Log.w("BokehAnalyzer", "segmenter closed mid-frame", e)
                frame.recycle()
                return
            }
            frame.recycle()
            if (closed) return

            val w = result.width
            val h = result.height
            val current = result.mask // foreground prob [0,1], row-major

            // Temporal smoothing: in-place blend into current array when possible to avoid alloc.
            val prev = prevMask
            val smoothed = if (prev != null && prev.size == current.size) {
                for (i in current.indices) {
                    current[i] = current[i] * (1f - TEMPORAL_WEIGHT) + prev[i] * TEMPORAL_WEIGHT
                }
                current
            } else {
                current
            }
            prevMask = smoothed

            // Blur for soft edges – reuses temp/dst buffers.
            val blurred = blurMask(smoothed, w, h)

            // Reuse pixel buffer.
            var pixels = pixelBuffer
            if (pixels == null || pixels.size != w * h) {
                pixels = IntArray(w * h)
                pixelBuffer = pixels
            }
            for (i in 0 until w * h) {
                val v = blurred[i]
                val clamped = when {
                    v < 0f -> 0f
                    v > 1f -> 1f
                    else -> v
                }
                val alpha = (clamped * 255f).toInt()
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
        closed = true
        try { segmenter.close() } catch (_: Exception) {}
        prevMask = null
        blurTemp = null
        blurDst = null
        pixelBuffer = null
    }

    private fun downscaleIfNeeded(src: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(src.width, src.height)
        if (max <= maxSide) return src
        val scale = maxSide.toFloat() / max
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        // filter=false – nearest/cheap, avoids bilinear alloc cost
        return Bitmap.createScaledBitmap(src, newW, newH, false)
    }

    /**
     * Rotate the sensor-oriented frame into display orientation (and mirror it
     * horizontally for the front camera) so the produced mask aligns with the
     * preview, which CameraX renders with that same transform.
     * filter=false to avoid second full-res bilinear alloc.
     */
    private fun orientToDisplay(src: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0 && !isFrontFacing) return src
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        if (isFrontFacing) matrix.postScale(-1f, 1f) // preview mirrors the front lens
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, false)
    }

    private fun blurMask(src: FloatArray, w: Int, h: Int): FloatArray {
        // Two-pass separable Gaussian blur (radius 3, sigma ~1.5)
        // Reuse buffers to avoid per-frame allocs that caused GC pressure.
        val wh = w * h
        var temp = blurTemp
        if (temp == null || temp.size != wh) {
            temp = FloatArray(wh)
            blurTemp = temp
        }
        var dst = blurDst
        if (dst == null || dst.size != wh) {
            dst = FloatArray(wh)
            blurDst = dst
        }

        // Horizontal pass – manual clamp instead of coerceIn in inner loop
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0f
                var sx: Int
                // k = -3
                sx = x - 3
                if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
                sum += src[row + sx] * 0.06f
                // k = -2
                sx = x - 2
                if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
                sum += src[row + sx] * 0.12f
                // k = -1
                sx = x - 1
                if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
                sum += src[row + sx] * 0.18f
                // k = 0
                sum += src[row + x] * 0.28f
                // k = 1
                sx = x + 1
                if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
                sum += src[row + sx] * 0.18f
                // k = 2
                sx = x + 2
                if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
                sum += src[row + sx] * 0.12f
                // k = 3
                sx = x + 3
                if (sx < 0) sx = 0 else if (sx >= w) sx = w - 1
                sum += src[row + sx] * 0.06f

                temp[row + x] = sum
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sum = 0f
                var sy: Int
                sy = y - 3
                if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
                sum += temp[sy * w + x] * 0.06f
                sy = y - 2
                if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
                sum += temp[sy * w + x] * 0.12f
                sy = y - 1
                if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
                sum += temp[sy * w + x] * 0.18f
                sum += temp[y * w + x] * 0.28f
                sy = y + 1
                if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
                sum += temp[sy * w + x] * 0.18f
                sy = y + 2
                if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
                sum += temp[sy * w + x] * 0.12f
                sy = y + 3
                if (sy < 0) sy = 0 else if (sy >= h) sy = h - 1
                sum += temp[sy * w + x] * 0.06f

                dst[y * w + x] = sum
            }
        }
        return dst
    }
}
