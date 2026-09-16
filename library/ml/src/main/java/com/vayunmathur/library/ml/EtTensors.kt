package com.vayunmathur.library.ml

import android.util.Log
import org.pytorch.executorch.DType
import org.pytorch.executorch.EValue

/**
 * First-tensor extraction for ExecuTorch vision/text drop-ins, accepting fp16-lowered outputs.
 *
 * `ExecutorchSessions.runFloat` covers float32 graphs only: it returns null when the output
 * dtype is not [DType.FLOAT]. The Vulkan fp16 ladders (`*_vulkan_fp16.pte`) lower the graph
 * to half precision, so their outputs may come back [DType.HALF] — failing closed there
 * would silently pin every ET call to the LiteRT fallback forever. This reads FLOAT
 * directly and upcasts HALF element-wise, returning null (never throwing) for anything
 * else so the caller can fall back.
 *
 * [want] is the expected element count; a short tensor returns null rather than a
 * truncated vector. A longer tensor is read whole — callers slice what they need.
 */
internal fun EValue.floatsAllowingHalf(tag: String, want: Int): FloatArray? {
    if (!isTensor) {
        Log.e(TAG, "$tag produced no tensor")
        return null
    }
    val tensor = toTensor()
    val n = tensor.numel().toInt()
    if (n < want) {
        Log.e(TAG, "$tag produced $n elements, want at least $want")
        return null
    }
    return when (tensor.dtype()) {
        DType.FLOAT -> {
            val out = FloatArray(n)
            tensor.copyDataInto(java.nio.FloatBuffer.wrap(out))
            out
        }
        DType.HALF -> {
            // ShortBuffer path (getDataAsShortArray is not visible on the Kotlin
            // Tensor facade; copyDataInto(ShortBuffer) is).
            val shorts = ShortArray(n)
            tensor.copyDataInto(java.nio.ShortBuffer.wrap(shorts))
            FloatArray(n) { halfToFloat(shorts[it]) }
        }
        else -> {
            Log.e(TAG, "$tag dtype ${tensor.dtype()} is not FLOAT or HALF")
            null
        }
    }
}

private const val TAG = "EtTensors"

/** IEEE-754 binary16 → binary32, including subnormals and inf/nan. */
private fun halfToFloat(h: Short): Float {
    val bits = h.toInt() and 0xFFFF
    val sign = (bits and 0x8000) shl 16
    val exp = (bits ushr 10) and 0x1F
    val mant = bits and 0x3FF
    val fbits = when {
        exp == 0 -> {
            if (mant == 0) {
                sign
            } else {
                var m = mant
                var e = -14
                while (m and 0x400 == 0) {
                    m = m shl 1
                    e--
                }
                sign or ((e + 127) shl 23) or ((m and 0x3FF) shl 13)
            }
        }
        exp == 31 -> sign or 0x7F800000 or (mant shl 13)
        else -> sign or ((exp + (127 - 15)) shl 23) or (mant shl 13)
    }
    return Float.fromBits(fbits)
}
