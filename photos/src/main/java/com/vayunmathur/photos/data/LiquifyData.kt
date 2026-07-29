package com.vayunmathur.photos.data

import android.graphics.Bitmap
import com.vayunmathur.photos.util.PhotosNative

enum class LiquifyTool { Push, Twirl, Pucker, Bloat, Reconstruct }

data class LiquifyOp(
    val tool: LiquifyTool,
    val x: Float,            // normalized 0..1 center
    val y: Float,
    val dx: Float = 0f,      // normalized drag delta (for Push)
    val dy: Float = 0f,
    val radius: Float = 0.15f, // normalized
    val strength: Float = 0.5f,
)

data class LiquifyParams(val ops: List<LiquifyOp> = emptyList()) {
    fun isIdentity(): Boolean = ops.isEmpty()
}

fun LiquifyParams.applyToBitmap(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    val src = IntArray(w * h)
    bitmap.getPixels(src, 0, w, 0, 0, w, h)

    val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    if (isIdentity() || w == 0 || h == 0) {
        output.setPixels(src, 0, w, 0, 0, w, h)
        return output
    }

    val tools = IntArray(ops.size) { ops[it].tool.ordinal }
    val params = FloatArray(ops.size * 6)
    for (i in ops.indices) {
        val op = ops[i]
        params[i * 6] = op.x
        params[i * 6 + 1] = op.y
        params[i * 6 + 2] = op.dx
        params[i * 6 + 3] = op.dy
        params[i * 6 + 4] = op.radius
        params[i * 6 + 5] = op.strength
    }
    val out = PhotosNative.liquify(src, w, h, tools, params)
    output.setPixels(out, 0, w, 0, 0, w, h)
    return output
}
