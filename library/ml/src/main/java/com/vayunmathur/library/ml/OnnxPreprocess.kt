package com.vayunmathur.library.ml

import android.graphics.Bitmap
import kotlin.math.max

/**
 * Image preprocessing for the ONNX models, ported from
 * `library/ml/src/main/rust/src/preprocess.rs`.
 *
 * Every vision model takes planar NCHW fp32, but each wants a different fit and normalisation:
 * the segmenters stretch, SCRFD letterboxes into a square, and the constants differ per net
 * (SCRFD's divisor is 128 where MobileFaceNet's is 127.5 — see the Rust `preprocess.rs`, whose
 * comments record why each constant is what it is).
 *
 * The resize is centre-sampled bilinear (`(out + 0.5) * scale - 0.5`), matching the native
 * `sample()`. It is deliberately not `Bitmap.createScaledBitmap`: that rounds differently at
 * edges and would shift every mask by subpixel amounts relative to the MAML behaviour.
 */
internal object OnnxPreprocess {
    /** Per-model normalisation: `(value / 255 - mean) / std`, with channel order. */
    data class Normalise(
        val mean: FloatArray,
        val std: FloatArray,
        /** True when channel 0 of the input is blue (PP-OCR), false for RGB. */
        val bgr: Boolean = false,
    )

    /** MediaPipe Selfie Segmentation: scale to `0..1`, nothing else. */
    val RESCALE_ONLY = Normalise(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))

    /** U^2-Net portable: ImageNet statistics, RGB. */
    val IMAGENET = Normalise(
        floatArrayOf(0.485f, 0.456f, 0.406f),
        floatArrayOf(0.229f, 0.224f, 0.225f),
    )

    /** PP-OCRv5 detection: ImageNet statistics over BGR channels. */
    val PPOCR_DET = Normalise(
        floatArrayOf(0.485f, 0.456f, 0.406f),
        floatArrayOf(0.229f, 0.224f, 0.225f),
        bgr = true,
    )

    /** PP-OCRv5 recognition: `(value / 255 - 0.5) / 0.5` over BGR. */
    val PPOCR_REC = Normalise(
        floatArrayOf(0.5f, 0.5f, 0.5f),
        floatArrayOf(0.5f, 0.5f, 0.5f),
        bgr = true,
    )

    /** SCRFD: `(value - 127.5) / 128` on the `0..255` scale, RGB. */
    val SCRFD = Normalise(
        floatArrayOf(0.5f, 0.5f, 0.5f),
        floatArrayOf(128f / 255f, 128f / 255f, 128f / 255f),
    )

    /** MobileFaceNet: `(value - 127.5) / 127.5` on the `0..255` scale, RGB. */
    val FACE_EMBED = Normalise(
        floatArrayOf(0.5f, 0.5f, 0.5f),
        floatArrayOf(0.5f, 0.5f, 0.5f),
    )

    /** How an image was fitted into a square detector input. Mirrors `Letterbox::square`. */
    data class SquareFit(
        val scale: Float,
        val resizedW: Int,
        val resizedH: Int,
        val offsetX: Int,
        val offsetY: Int,
    ) {
        companion object {
            fun of(width: Int, height: Int, side: Int): SquareFit {
                val (scale, rw, rh) = if (width > height) {
                    val s = side.toFloat() / width
                    Triple(s, side, max(1, (height * s).toInt()).coerceAtMost(side))
                } else {
                    val s = side.toFloat() / height
                    Triple(s, max(1, (width * s).toInt()).coerceAtMost(side), side)
                }
                return SquareFit(scale, rw, rh, (side - rw) / 2, (side - rh) / 2)
            }
        }
    }

    /**
     * Readable pixels from [bitmap], copying off HARDWARE configs.
     *
     * Returns null when the bitmap is recycled. The caller recycles the copy; see
     * `NativeSegmenter.segment` for the discipline.
     */
    fun readablePixels(bitmap: Bitmap): Pair<IntArray, Bitmap>? {
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE || bitmap.config == null) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        } ?: return null
        val pixels = IntArray(readable.width * readable.height)
        readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
        return Pair(pixels, readable)
    }

    /**
     * Stretch [pixels] ([width]×[height] ARGB) to [outW]×[outH] planar NCHW floats.
     *
     * The segmenter fit: a straight scale, aspect not preserved. Matches `to_planar_f16`
     * (modulo fp16 rounding, which the fp32 ONNX graph does not need).
     */
    fun stretchPlanar(
        pixels: IntArray,
        width: Int,
        height: Int,
        outW: Int,
        outH: Int,
        norm: Normalise,
    ): FloatArray {
        val plane = outW * outH
        val out = FloatArray(3 * plane)
        val xScale = width.toFloat() / outW
        val yScale = height.toFloat() / outH
        for (y in 0 until outH) {
            val (y0, y1, wy) = sample(y, yScale, height)
            for (x in 0 until outW) {
                val (x0, x1, wx) = sample(x, xScale, width)
                val at = y * outW + x
                for (c in 0..2) {
                    val src = if (norm.bgr) 2 - c else c
                    val top = lerp(channel(pixels, width, x0, y0, src), channel(pixels, width, x1, y0, src), wx)
                    val bottom = lerp(channel(pixels, width, x0, y1, src), channel(pixels, width, x1, y1, src), wx)
                    val value = lerp(top, bottom, wy) / 255f
                    out[c * plane + at] = (value - norm.mean[c]) / norm.std[c]
                }
            }
        }
        return out
    }

    /**
     * Letterbox [pixels] into a [side]×[side] square per [fit], planar NCHW floats.
     *
     * The SCRFD fit: scaled image centred at the fit offset, border filled with raw zero
     * through the normalisation (the net sees `(0 - 127.5) / 128` at the edges, not grey).
     */
    fun letterboxPlanar(
        pixels: IntArray,
        width: Int,
        height: Int,
        side: Int,
        fit: SquareFit,
        norm: Normalise,
    ): FloatArray {
        val plane = side * side
        val out = FloatArray(3 * plane)
        val border = FloatArray(3) { (0f - norm.mean[it]) / norm.std[it] }
        val xScale = width.toFloat() / fit.resizedW
        val yScale = height.toFloat() / fit.resizedH
        for (y in 0 until side) {
            val insideRow = y >= fit.offsetY && y < fit.offsetY + fit.resizedH
            val (y0, y1, wy) = if (insideRow) sample(y - fit.offsetY, yScale, height) else Triple(0, 0, 0f)
            for (x in 0 until side) {
                val at = y * side + x
                if (!insideRow || x < fit.offsetX || x >= fit.offsetX + fit.resizedW) {
                    for (c in 0..2) out[c * plane + at] = border[c]
                    continue
                }
                val (x0, x1, wx) = sample(x - fit.offsetX, xScale, width)
                for (c in 0..2) {
                    val src = if (norm.bgr) 2 - c else c
                    val top = lerp(channel(pixels, width, x0, y0, src), channel(pixels, width, x1, y0, src), wx)
                    val bottom = lerp(channel(pixels, width, x0, y1, src), channel(pixels, width, x1, y1, src), wx)
                    val value = lerp(top, bottom, wy) / 255f
                    out[c * plane + at] = (value - norm.mean[c]) / norm.std[c]
                }
            }
        }
        return out
    }

    private fun sample(out: Int, scale: Float, extent: Int): Triple<Int, Int, Float> {
        val source = (out + 0.5f) * scale - 0.5f
        val clamped = max(source, 0f)
        val low = clamped.toInt()
        val weight = clamped - low
        val lo = low.coerceAtMost(extent - 1)
        val hi = (lo + 1).coerceAtMost(extent - 1)
        return Triple(lo, hi, weight)
    }

    private fun channel(pixels: IntArray, width: Int, x: Int, y: Int, channel: Int): Float {
        val argb = pixels[y * width + x]
        return when (channel) {
            0 -> ((argb shr 16) and 0xFF).toFloat()
            1 -> ((argb shr 8) and 0xFF).toFloat()
            else -> (argb and 0xFF).toFloat()
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
