package com.vayunmathur.library.image.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import com.vayunmathur.library.image.ImageRequest
import java.nio.ByteBuffer

object BitmapDecoder {

    /**
     * Longest edge an unconstrained ("original size") decode may produce.
     *
     * Requests without `.size(...)` used to decode at full resolution, so one
     * panorama or high-MP photo became a ~500 MB bitmap that crashed in
     * `RecordingCanvas.throwIfCannotDraw` as soon as it was drawn (files #768).
     * 4096 keeps full-screen viewers sharp while a square 4096x4096 ARGB_8888
     * bitmap is 64 MB, comfortably under Canvas's draw limit.
     */
    const val MAX_ORIGINAL_DIMENSION = 4096

    fun isSvg(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        val head = String(bytes.take(1024).toByteArray()).trimStart()
        return head.startsWith("<svg", ignoreCase = true) ||
            head.startsWith("<?xml") && head.contains("<svg", ignoreCase = true)
    }

    suspend fun decode(
        bytes: ByteArray,
        request: ImageRequest,
        allowHardware: Boolean,
    ): Bitmap? {
        val reqSize = request.size
        val targetW = reqSize?.width ?: -1
        val targetH = reqSize?.height ?: -1

        val viaImageDecoder = try {
            decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), request, allowHardware)
        } catch (_: Exception) {
            null
        }
        if (viaImageDecoder != null) return viaImageDecoder
        return try { decodeWithBitmapFactory(bytes, targetW, targetH, allowHardware) } catch (_: Exception) { null }
    }

    /**
     * Decode straight from an [ImageDecoder.Source], so local media never has to be
     * read into a `ByteArray` first. Downsampling happens inside the decoder, which
     * is why the target size matters more than where the bytes came from.
     */
    fun decode(
        source: ImageDecoder.Source,
        request: ImageRequest,
        allowHardware: Boolean,
    ): Bitmap? {
        val reqSize = request.size
        val targetW = reqSize?.width ?: -1
        val targetH = reqSize?.height ?: -1

        return try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (targetW > 0 && targetH > 0 && (w > targetW || h > targetH)) {
                    val ratio = maxOf(w.toFloat() / targetW, h.toFloat() / targetH)
                    if (ratio > 1f) {
                        decoder.setTargetSize((w / ratio).toInt().coerceAtLeast(1), (h / ratio).toInt().coerceAtLeast(1))
                    }
                } else if (targetW <= 0 || targetH <= 0) {
                    // Unconstrained request: never hand back a bitmap larger than
                    // MAX_ORIGINAL_DIMENSION on its longest edge (see files #768).
                    val longest = maxOf(w, h)
                    if (longest > MAX_ORIGINAL_DIMENSION) {
                        val ratio = longest.toFloat() / MAX_ORIGINAL_DIMENSION
                        decoder.setTargetSize(
                            (w / ratio).toInt().coerceAtLeast(1),
                            (h / ratio).toInt().coerceAtLeast(1),
                        )
                    }
                }
                decoder.isUnpremultipliedRequired = false
                decoder.allocator = if (allowHardware) {
                    ImageDecoder.ALLOCATOR_DEFAULT
                } else {
                    ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Power-of-two downsampling for the `BitmapFactory` fallback, pure arithmetic
     * so it is unit-testable without Android bitmaps.
     *
     * A sized request samples to roughly the target; an unconstrained request
     * ("original size") still caps the longest edge at [MAX_ORIGINAL_DIMENSION]
     * so no caller can produce a Canvas-crashing bitmap (files #768).
     */
    internal fun sampleSizeFor(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (reqW > 0 && reqH > 0 && srcW > 0 && srcH > 0) {
            val halfW = srcW / 2
            val halfH = srcH / 2
            while (halfW / sample >= reqW && halfH / sample >= reqH) {
                sample *= 2
            }
        } else if (srcW > 0 && srcH > 0 && (srcW > MAX_ORIGINAL_DIMENSION || srcH > MAX_ORIGINAL_DIMENSION)) {
            while (srcW / sample > MAX_ORIGINAL_DIMENSION || srcH / sample > MAX_ORIGINAL_DIMENSION) {
                sample *= 2
            }
        }
        return sample.coerceAtLeast(1)
    }

    private fun decodeWithBitmapFactory(
        bytes: ByteArray,
        reqW: Int,
        reqH: Int,
        allowHardware: Boolean,
    ): Bitmap {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val (w, h) = boundsOpts.outWidth to boundsOpts.outHeight

        val sample = sampleSizeFor(w, h, reqW, reqH)

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = if (allowHardware) Bitmap.Config.ARGB_8888 else Bitmap.Config.ARGB_8888
            // HARDWARE config cannot be used with inSampleSize on older APIs safely – keep ARGB
            inMutable = false
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("BitmapFactory failed")
        // If hardware requested, convert roughly by copying with HARDWARE.
        if (allowHardware) {
            return try {
                bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp
            } catch (_: Exception) { bmp }
        }
        return bmp
    }
}
