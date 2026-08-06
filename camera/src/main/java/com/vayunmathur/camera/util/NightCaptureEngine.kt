package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Multi-frame computational night capture. Takes a burst of upright/consistent
 * frames, aligns + merges them via the native [StitchNative] Rust library to cut
 * noise (~√N read/shot-noise reduction), then brightens the result with a
 * highlight-preserving curve so it reads as a bright night shot without clipping.
 *
 * This is currently the *primary* night path — the app does not depend on
 * camera-extensions / ExtensionMode.NIGHT, so vendor Night extension (Pixel/Samsung ISP)
 * is unavailable. This custom path is used when auto low-light detection fires.
 *
 * Improvements vs old path:
 * - Burst is still collected from ImageAnalysis but at HIGHEST_AVAILABLE resolution,
 *   and fed to Rust as lossless RGBA via newNightSession/addNightRgbaFrame/mergeNight,
 *   avoiding the old Bitmap -> JPEG 95 -> decode double loss.
 * - Rust runs ORB registration at ~0.8 MP, rescales homography to full res, + deghosting
 *   against reference to avoid moving-object ghosts.
 * - Tone mapping preserves highlights with a soft knee instead of linear *1.6+18 clip.
 * - Full-res ImageCapture burst variant captures via ImageCapture repeated
 *   onImageCaptured (higher IQ than analysis stream).
 */
object NightCaptureEngine {
    /** How many frames to stack. Higher = less noise but longer capture + more work. */
    const val NIGHT_BURST_COUNT = 6

    // Brightening baked into the merged result (shadow lift + gain, highlight compressed).
    private const val NIGHT_GAIN = 1.6f
    private const val NIGHT_SHADOW_LIFT = 18f
    private const val HIGHLIGHT_COMPRESS_START = 220f
    private const val HIGHLIGHT_COMPRESS_FACTOR = 0.38f

    /**
     * Aligns and merges [burst] into a single brightened bitmap. Falls back to the
     * middle frame if native align/merge is unavailable. Runs off the main thread.
     */
    suspend fun merge(burst: List<Bitmap>): Bitmap? = withContext(Dispatchers.Default) {
        if (burst.isEmpty()) return@withContext null
        val sized = burst.filter { it.width > 0 && it.height > 0 }.let { list ->
            if (list.isEmpty()) return@withContext null
            val w = list[0].width
            val h = list[0].height
            val same = list.filter { it.width == w && it.height == h }
            if (same.size >= 2) same else list.take(1)
        }

        val merged = alignAndMergeNative(sized)
        val source = merged ?: sized[sized.size / 2]

        val w = source.width
        val h = source.height
        val n = w * h
        val px = IntArray(n)
        source.getPixels(px, 0, w, 0, 0, w, h)
        for (i in 0 until n) {
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Alpha preserved from source, RGB via highlight-preserving curve
            val rr = brightenChannel(r.toFloat())
            val gg = brightenChannel(g.toFloat())
            val bb = brightenChannel(b.toFloat())
            px[i] = (a shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        if (merged != null && merged !== source) {
            merged.recycle()
        }
        createBitmap(w, h).apply {
            setPixels(px, 0, w, 0, 0, w, h)
        }
    }

    /** Lossless RGBA path -> Rust (no double JPEG), fallback to old JPEG path. */
    private fun alignAndMergeNative(burst: List<Bitmap>): Bitmap? {
        if (!StitchNative.isAvailable) return null
        // First try lossless RGBA session (no double JPEG quality loss)
        tryLosslessRgba(burst)?.let { return it }
        // Fallback: old JPEG-compressed path (backwards compat)
        return try {
            val handle = StitchNative.newSession(false)
            try {
                for (f in burst) {
                    val baos = java.io.ByteArrayOutputStream()
                    f.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                    StitchNative.addFrame(handle, baos.toByteArray(), 0f, 0f, 0f)
                }
                val jpeg = StitchNative.merge(handle) ?: return null
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } finally {
                StitchNative.free(handle)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryLosslessRgba(burst: List<Bitmap>): Bitmap? {
        return try {
            val handle = StitchNative.newNightSession()
            if (handle == 0L) return null
            try {
                for (f in burst) {
                    val w = f.width
                    val h = f.height
                    if (w <= 0 || h <= 0 || w > 20000 || h > 20000) continue
                    val ints = IntArray(w * h)
                    f.getPixels(ints, 0, w, 0, 0, w, h)
                    val rgba = ByteArray(w * h * 4)
                    var j = 0
                    for (p in ints) {
                        rgba[j] = ((p shr 16) and 0xFF).toByte() // R
                        rgba[j + 1] = ((p shr 8) and 0xFF).toByte() // G
                        rgba[j + 2] = (p and 0xFF).toByte() // B
                        rgba[j + 3] = ((p ushr 24) and 0xFF).toByte() // A
                        j += 4
                    }
                    StitchNative.addNightRgbaFrame(handle, rgba, w, h)
                }
                val jpeg = StitchNative.mergeNight(handle) ?: return null
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } finally {
                try {
                    StitchNative.freeNight(handle)
                } catch (_: Throwable) {
                    try { StitchNative.free(handle) } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Highlight-preserving brightening:
     * - Shadow lift weighted more in dark regions
     * - Gain tapers toward highlights so brights don't clip
     * - Soft knee compression above [HIGHLIGHT_COMPRESS_START]
     */
    private fun brightenChannel(value: Float): Int {
        val y = value / 255f
        val taperedGain = NIGHT_GAIN - y * (NIGHT_GAIN - 1.15f) * 0.65f
        val lifted = value * taperedGain + NIGHT_SHADOW_LIFT * (1f - y) * (1f - y * 0.3f)
        val compressed = if (lifted > HIGHLIGHT_COMPRESS_START) {
            HIGHLIGHT_COMPRESS_START + (lifted - HIGHLIGHT_COMPRESS_START) * HIGHLIGHT_COMPRESS_FACTOR
        } else lifted
        return compressed.roundToInt().coerceIn(0, 255)
    }
}
