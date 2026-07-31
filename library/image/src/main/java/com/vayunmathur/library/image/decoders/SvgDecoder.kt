package com.vayunmathur.library.image.decoders

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.caverock.androidsvg.SVG
import com.vayunmathur.library.image.ImageRequest
import java.io.ByteArrayInputStream

object SvgDecoder {

    fun canDecode(bytes: ByteArray, dataHint: Any?): Boolean {
        if (dataHint is String) {
            val lower = dataHint.lowercase()
            if (lower.endsWith(".svg") || lower.contains(".svg?") || lower.contains("image/svg")) return true
        }
        return BitmapDecoder.isSvg(bytes)
    }

    suspend fun decode(bytes: ByteArray, request: ImageRequest): Bitmap? {
        return try {
            val svg = SVG.getFromInputStream(ByteArrayInputStream(bytes))
            val reqSize = request.size
            val targetW = if (reqSize != null && !reqSize.isOriginal()) reqSize.width else 512
            val targetH = if (reqSize != null && !reqSize.isOriginal()) reqSize.height else 512

            // Determine document dimensions
            val docW = svg.documentWidth
            val docH = svg.documentHeight
            val aspect: Float = if (docW > 0 && docH > 0) docW / docH else 1f

            val outW: Int
            val outH: Int
            if (targetW > 0 && targetH > 0) {
                outW = targetW
                outH = targetH
            } else if (targetW > 0) {
                outW = targetW
                outH = (targetW / aspect).toInt().coerceAtLeast(1)
            } else if (docW > 0 && docH > 0) {
                outW = docW.toInt().coerceAtLeast(1)
                outH = docH.toInt().coerceAtLeast(1)
            } else {
                outW = 512
                outH = 512
            }

// If doc has no size, set a viewport so it scales
            if (docW <= 0 || docH <= 0) {
                svg.setDocumentWidth(outW.toFloat())
                svg.setDocumentHeight(outH.toFloat())
            }

            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)
            svg.renderToCanvas(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
