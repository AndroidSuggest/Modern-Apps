package com.vayunmathur.camera.util

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

class QrAnalyzer(private val onQrDetected: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // dataWidth (arg 2) is the backing array's row stride, not the image width; HALs pad
        // Y rows to an alignment boundary, and using width there shears the frame so no code
        // ever decodes. See the same fix in [PhotoAnalyzer].
        val source = PlanarYUVLuminanceSource(
            bytes,
            plane.rowStride,
            imageProxy.height,
            0, 0,
            imageProxy.width,
            imageProxy.height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))

        try {
            val result = reader.decodeWithState(bitmap)
            onQrDetected(result.text)
        } catch (_: NotFoundException) {
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }
}
