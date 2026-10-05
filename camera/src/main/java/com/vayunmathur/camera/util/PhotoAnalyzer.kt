package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Combined PHOTO-stream analyzer. The ImageAnalysis use case can only host one analyzer, so this
 * reads the Y (luminance) plane once and both (1) samples average brightness for night-mode
 * detection and (2) runs the ZXing QR decode.
 *
 * Runs on the ViewModel's dedicated analysis thread, not the main one: the decode plus the
 * Motion-Photo frame copy cost tens of ms per frame, and under STRATEGY_KEEP_ONLY_LATEST that
 * starved the analyzer of frames when it shared the main thread with preview rendering.
 *
 * When [onMotionFrame] is supplied it also emits a copy of each frame (bitmap + timestamp +
 * rotation) so the ViewModel can maintain a Motion-Photo ring buffer off this same stream.
 */
/** Y-plane subsample stride (px) and unsigned-byte mask for luminance sampling. */
private const val LUMA_STRIDE_PX = 16
private const val CHANNEL_MASK = 0xFF

class PhotoAnalyzer(
    private val onLuminance: (Float) -> Unit,
    private val onQrDetected: (String) -> Unit,
    private val onMotionFrame: ((Bitmap, Long, Int) -> Unit)? = null
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        // Restrict to 2D codes only. Without this, ZXing's default MultiFormatReader
        // also decodes 1D linear barcodes (EAN/UPC/Code-128/ITF) which yield purely
        // numeric strings. In landscape, horizontal scene edges mimic linear barcode
        // bars and produce spurious numeric "QR" detections.
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
            )
        )
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val startMs = System.currentTimeMillis()
        try {
            val frame = readYPlane(imageProxy) ?: return
            sampleLuminance(frame, imageProxy, startMs)
            emitMotionFrame(imageProxy)
            decodeQr(frame, imageProxy, startMs)
        } catch (e: IllegalStateException) {
            Log.e(
                "NightPreview",
                "PhotoAnalyzer analyze() OUTER threw – was not logged, " +
                    "causes black preview and only 1x zoom because analyzer crashes",
                e
            )
            closeQuietly(imageProxy, "outer close() also failed (double hidden)")
        } catch (e: IllegalArgumentException) {
            Log.e(
                "NightPreview",
                "PhotoAnalyzer analyze() OUTER threw – was not logged, " +
                    "causes black preview and only 1x zoom because analyzer crashes",
                e
            )
            closeQuietly(imageProxy, "outer close() also failed (double hidden)")
        }
    }

    /** Y-plane bytes plus row stride; null when the plane can't be read. */
    private data class YFrame(val bytes: ByteArray, val rowStride: Int)

    /** Copies the Y plane; rethrows buffer errors after logging (handled by the outer catch). */
    @OptIn(ExperimentalGetImage::class)
    private fun readYPlane(imageProxy: ImageProxy): YFrame? {
        val plane = imageProxy.planes[0]
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        try {
            buffer.get(bytes)
        } catch (e: java.nio.BufferUnderflowException) {
            Log.e(
                "NightPreview",
                "PhotoAnalyzer buffer.get() threw (swallowed before) " +
                    "plane0Remaining? ${buffer.remaining()}",
                e
            )
            throw e
        }
        return YFrame(bytes, rowStride)
    }

    /** Samples average luminance over a strided subsample and forwards it. */
    @OptIn(ExperimentalGetImage::class)
    private fun sampleLuminance(frame: YFrame, imageProxy: ImageProxy, startMs: Long) {
        // Average luminance over a strided subsample of the Y plane (kept cheap).
        var sum = 0L
        var count = 0
        var i = 0
        try {
            while (i < frame.bytes.size) {
                sum += (frame.bytes[i].toInt() and CHANNEL_MASK)
                count++
                i += LUMA_STRIDE_PX
            }
        } catch (e: ArithmeticException) {
            Log.e("NightPreview", "PhotoAnalyzer luma loop threw (hidden)", e)
        }
        if (count > 0) {
            val avg = sum.toFloat() / count
            Log.d(
                "NightPreview",
                "PhotoAnalyzer luma avg=$avg sum=$sum count=$count bytesSize=${frame.bytes.size} " +
                    "width=${imageProxy.width} height=${imageProxy.height} rowStride=${frame.rowStride} " +
                    "timestamp=${imageProxy.imageInfo.timestamp} " +
                    "rot=${imageProxy.imageInfo.rotationDegrees} " +
                    "took=${System.currentTimeMillis() - startMs}ms"
            )
            try {
                onLuminance(avg)
            } catch (e: IllegalStateException) {
                Log.e("NightPreview", "PhotoAnalyzer onLuminance callback threw (was hidden)", e)
            } catch (e: IllegalArgumentException) {
                Log.e("NightPreview", "PhotoAnalyzer onLuminance callback threw (was hidden)", e)
            }
        } else {
            Log.w(
                "NightPreview",
                "PhotoAnalyzer count=0, no luma callback – preview may be black? " +
                    "bytesSize=${frame.bytes.size}"
            )
        }
    }

    /** Feeds the Motion-Photo ring buffer with an RGB copy of this frame. */
    @OptIn(ExperimentalGetImage::class)
    private fun emitMotionFrame(imageProxy: ImageProxy) {
        val emit = onMotionFrame ?: return
        try {
            val bmp = try {
                imageProxy.toBitmap()
            } catch (e: IllegalStateException) {
                Log.e("NightPreview", "PhotoAnalyzer toBitmap() for Motion-Photo threw (hidden before)", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.e("NightPreview", "PhotoAnalyzer toBitmap() for Motion-Photo threw (hidden before)", e)
                null
            }
            if (bmp != null) {
                try {
                    emit(bmp, imageProxy.imageInfo.timestamp, imageProxy.imageInfo.rotationDegrees)
                } catch (e: IllegalStateException) {
                    Log.e("NightPreview", "PhotoAnalyzer onMotionFrame emit threw (hidden)", e)
                } catch (e: IllegalArgumentException) {
                    Log.e("NightPreview", "PhotoAnalyzer onMotionFrame emit threw (hidden)", e)
                }
            }
        } catch (e: IllegalStateException) {
            Log.e("NightPreview", "PhotoAnalyzer Motion-Photo outer threw (hidden)", e)
        } catch (e: IllegalArgumentException) {
            Log.e("NightPreview", "PhotoAnalyzer Motion-Photo outer threw (hidden)", e)
        }
    }

    /**
     * Runs the ZXing QR decode over the Y plane.
     *
     * dataWidth (arg 2) is the stride of the backing array, NOT the image width — the
     * two only coincide when the HAL packs Y rows tightly. Most camera HALs pad each
     * row out to a 32/64-byte boundary, so for a 1440-wide analysis frame rowStride
     * comes back as 1472 or 1536. Passing width there makes ZXing start each row
     * (rowStride - width) bytes early, shearing the frame progressively down the
     * image and destroying the finder patterns, so nothing ever decodes. It fails
     * silently and only on devices whose analysis width isn't already aligned, which
     * is why QR scanning worked on some phones and not others.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun decodeQr(frame: YFrame, imageProxy: ImageProxy, startMs: Long) {
        val source = PlanarYUVLuminanceSource(
            frame.bytes,
            frame.rowStride,
            imageProxy.height,
            0, 0,
            imageProxy.width,
            imageProxy.height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        try {
            val result = reader.decodeWithState(bitmap)
            Log.d(
                "NightPreview",
                "PhotoAnalyzer QR decoded text=${result.text} " +
                    "width=${imageProxy.width} height=${imageProxy.height}"
            )
            try {
                onQrDetected(result.text)
            } catch (e: IllegalStateException) {
                Log.e("NightPreview", "PhotoAnalyzer onQrDetected threw (hidden)", e)
            } catch (e: IllegalArgumentException) {
                Log.e("NightPreview", "PhotoAnalyzer onQrDetected threw (hidden)", e)
            }
        } catch (_: NotFoundException) {
            // expected – no QR in frame, NOT an error
        } catch (e: com.google.zxing.ChecksumException) {
            Log.e(
                "NightPreview",
                "PhotoAnalyzer ZXing decodeWithState threw OTHER than NotFound (was swallowed)",
                e
            )
        } catch (e: com.google.zxing.FormatException) {
            Log.e(
                "NightPreview",
                "PhotoAnalyzer ZXing decodeWithState threw OTHER than NotFound (was swallowed)",
                e
            )
        } finally {
            resetAndClose(imageProxy, startMs)
        }
    }

    /** Resets the ZXing reader and closes the frame (a stall here freezes the preview). */
    private fun resetAndClose(imageProxy: ImageProxy, startMs: Long) {
        try {
            reader.reset()
        } catch (e: IllegalStateException) {
            Log.e("NightPreview", "PhotoAnalyzer reader.reset() threw (hidden)", e)
        }
        try {
            imageProxy.close()
            Log.d(
                "NightPreview",
                "PhotoAnalyzer imageProxy.close() took=${System.currentTimeMillis() - startMs}ms " +
                    "total – if not closed, pipeline stalls -> black preview!"
            )
        } catch (e: IllegalStateException) {
            Log.e(
                "NightPreview",
                "PhotoAnalyzer imageProxy.close() threw – pipeline stall -> black preview root!",
                e
            )
        }
    }

    /** Best-effort frame close from catch blocks, logging [context] on failure. */
    private fun closeQuietly(imageProxy: ImageProxy, context: String) {
        try {
            imageProxy.close()
        } catch (e2: IllegalStateException) {
            Log.e("NightPreview", "PhotoAnalyzer $context", e2)
        }
    }
}
