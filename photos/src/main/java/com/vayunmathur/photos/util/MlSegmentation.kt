package com.vayunmathur.photos.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vayunmathur.ncnn.Segmenter
import com.vayunmathur.photos.data.Selection

/**
 * Auto-select the foreground subject with an on-device neural model —
 * **U²-Net portable** salient-object detection on **ncnn** (CPU-only, BSD-3, no
 * ONNX Runtime, no MediaPipe, no Google Play Services, F-Droid clean).
 *
 * U²-Net predicts a general per-pixel saliency map, so it selects arbitrary
 * subjects. Pixels above [FG_THRESHOLD] form the selection; the hard edge is
 * feathered slightly so cutouts aren't jagged. Runs off the main thread;
 * [onResult] is posted to the main thread (null on failure).
 *
 * The model + preprocessing live inside the `ncnn-android` AAR ([Segmenter]);
 * input 320×320 RGB, ImageNet-normalised; output a 320×320 saliency map through
 * a sigmoid.
 */
fun segmentSubject(context: Context, bitmap: Bitmap, onResult: (Selection?) -> Unit) {
    Thread {
        val sel = runCatching { runSegmenter(context, bitmap) }
            .getOrElse { Log.e("MlSegmentation", "segmentation failed", it); null }
        Handler(Looper.getMainLooper()).post { onResult(sel) }
    }.start()
}

private const val FG_THRESHOLD = 0.5f

private val segLock = Any()
@Volatile private var segmenter: Segmenter? = null
@Volatile private var segInitTried = false

private fun ensureSegmenter(context: Context): Segmenter? {
    segmenter?.let { return it }
    synchronized(segLock) {
        segmenter?.let { return it }
        if (segInitTried) return null
        segInitTried = true
        return try {
            Segmenter(context.applicationContext, "u2netp.ncnn.param", "u2netp.ncnn.bin").also { segmenter = it }
        } catch (e: Throwable) {
            Log.e("MlSegmentation", "U\u00b2-Net segmenter unavailable", e)
            null
        }
    }
}

private fun runSegmenter(context: Context, bitmap: Bitmap): Selection? {
    val seg = ensureSegmenter(context) ?: return null

    // Cap the returned mask resolution for speed/memory; the model runs at its
    // fixed input size and we upsample the saliency map to this.
    val maxDim = 512
    val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)

    val safe = if (bitmap.config == Bitmap.Config.HARDWARE || bitmap.config == null) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    } else bitmap

    val result = synchronized(segLock) { seg.segment(safe) }
    if (safe != bitmap) safe.recycle()

    val sw = result.width
    val sh = result.height
    val saliency = result.mask

    // Normalise to 0..1 (U²-Net output isn't guaranteed to span the full range).
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (v in saliency) { if (v < lo) lo = v; if (v > hi) hi = v }
    val range = (hi - lo).takeIf { it > 1e-6f } ?: 1f

    // Upsample the model mask to the (w,h) selection grid and threshold.
    val mask = FloatArray(w * h)
    for (y in 0 until h) {
        val sy = (y * sh / h).coerceIn(0, sh - 1)
        for (x in 0 until w) {
            val sx = (x * sw / w).coerceIn(0, sw - 1)
            val norm = (saliency[sy * sw + sx] - lo) / range
            mask[y * w + x] = if (norm >= FG_THRESHOLD) 1f else 0f
        }
    }

    return Selection(mask, w, h).applyFeather(1.5f)
}
