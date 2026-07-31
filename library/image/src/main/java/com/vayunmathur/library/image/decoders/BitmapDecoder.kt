package com.vayunmathur.library.image.decoders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.graphics.ImageDecoder
import com.vayunmathur.library.image.ImageRequest
import java.nio.ByteBuffer

object BitmapDecoder {

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
        // Try ImageDecoder on P+ for better resampling + hardware support
        val reqSize = request.size
        val targetW = reqSize?.width ?: -1
        val targetH = reqSize?.height ?: -1

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    if (targetW > 0 && targetH > 0 && (w > targetW || h > targetH)) {
                        val ratio = maxOf(w.toFloat() / targetW, h.toFloat() / targetH)
                        if (ratio > 1f) {
                            decoder.setTargetSize((w / ratio).toInt().coerceAtLeast(1), (h / ratio).toInt().coerceAtLeast(1))
                        }
                    } else if (targetW > 0 && targetH > 0) {
                        // still set to avoid upscaling surprises – only downscale
                    }
                    decoder.isUnpremultipliedRequired = false
                    if (!allowHardware) {
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    } else {
                        decoder.allocator = ImageDecoder.ALLOCATOR_DEFAULT
                    }
                }
            } else {
                decodeWithBitmapFactory(bytes, targetW, targetH, allowHardware)
            }
        } catch (_: Exception) {
            try { decodeWithBitmapFactory(bytes, targetW, targetH, allowHardware) } catch (_: Exception) { null }
        }
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

        var sample = 1
        if (reqW > 0 && reqH > 0 && w > 0 && h > 0) {
            var halfW = w / 2
            var halfH = h / 2
            while (halfW / sample >= reqW && halfH / sample >= reqH) {
                sample *= 2
            }
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample.coerceAtLeast(1)
            inPreferredConfig = if (allowHardware) Bitmap.Config.ARGB_8888 else Bitmap.Config.ARGB_8888
            // HARDWARE config cannot be used with inSampleSize on older APIs safely – keep ARGB
            inMutable = false
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("BitmapFactory failed")
        // If hardware requested and we can convert, do so roughly by copying with HARDWARE if API 26+
        if (allowHardware && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try {
                bmp.copy(Bitmap.Config.HARDWARE, false) ?: bmp
            } catch (_: Exception) { bmp }
        }
        return bmp
    }
}
