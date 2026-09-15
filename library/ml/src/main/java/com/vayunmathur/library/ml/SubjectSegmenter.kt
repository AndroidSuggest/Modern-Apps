package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * General salient-object detection, for `:photos`'s auto-select-subject.
 *
 * **U²-Net portable** (Apache-2.0) at 320×320, run on the reduced ONNX Runtime build. Unlike
 * [SelfieSegmenter] it is not person-specific: it predicts a per-pixel saliency map and so
 * picks out arbitrary subjects. See `photos/src/main/assets/README.md` for provenance.
 *
 * Replaces the Vulkan `NativeSegmenter` path (`MlNative::createU2netp`): the graph is the
 * upstream export (`input.1 [1,3,320,320]` in, seven `[1,1,320,320]` maps out), of which
 * only output 0 — the fused `d0`, the same output the MAML `record(&[d0])` produced — is
 * read. The stretch resize and ImageNet normalisation now happen here in Kotlin.
 *
 * Replaces `com.vayunmathur.ncnn.Segmenter`. Same network, re-sourced from a licensed ONNX
 * export — the ncnn model's op inventory matched it exactly — so masks should look the
 * same as before, unlike `:camera`'s.
 *
 * CPU-backed: if [isAvailable] is false the model is missing or un-runnable and [segment]
 * returns null, which the caller treats as "no subject found".
 *
 * When the Vulkan backend is usable and this graph is allowlisted, inference runs on the
 * Vulkan fast path with ORT kept as the fallback: a Vulkan failure falls back to the ORT
 * session when it exists, preserving the null-on-failure contract.
 *
 * Not thread-safe: [segment] and [close] must not overlap.
 *
 * @param context used only to read the asset; not retained.
 * @param assetName the `.onnx` in the app's assets.
 */
class SubjectSegmenter(context: Context, assetName: String = DEFAULT_ASSET) : AutoCloseable {
    private val app = context.applicationContext
    private val asset = assetName
    private val lock = Any()

    @Volatile private var session: OrtSession? = null
    @Volatile private var vulkanHandle: Long = 0L
    @Volatile private var loadTried = false

    /** Whether the network came up. False means auto-select-subject is off. */
    val isAvailable: Boolean get() = ensure()

    /**
     * Run the network over [bitmap] and return a 320×320 saliency map, or null on failure.
     *
     * [bitmap] may be any size and any config: it is resized and normalised here.
     */
    fun segment(bitmap: Bitmap): SegmentationMask? {
        if (!ensure()) return null
        val (pixels, readable) = OnnxPreprocess.readablePixels(bitmap) ?: return null
        try {
            val input = OnnxPreprocess.stretchPlanar(
                pixels, readable.width, readable.height,
                SIZE, SIZE, OnnxPreprocess.IMAGENET,
            )
            if (vulkanHandle != 0L) {
                try {
                    vulkanSegment(vulkanHandle, input)?.let { return it }
                } catch (e: Throwable) {
                    Log.w(TAG, "u2netp vulkan inference failed, falling back to ORT", e)
                }
            }
            val live = session ?: return null
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).useOrt { tensor ->
                live.run(mapOf(INPUT to tensor), setOf(OUTPUT)).useOrt { result ->
                    val out = result[0] as OnnxTensor
                    val flat = FloatArray(SIZE * SIZE)
                    out.floatBuffer.get(flat)
                    return SegmentationMask(SIZE, SIZE, flat)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "u2netp inference failed", e)
            return null
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    override fun close() {
        synchronized(lock) {
            val handle = vulkanHandle
            vulkanHandle = 0L
            if (handle != 0L) {
                try {
                    VulkanSessions.close(handle)
                } catch (e: Throwable) {
                    Log.w(TAG, "u2netp vulkan close failed", e)
                }
            }
            session = null
            OnnxSessions.close(sessionKey())
        }
    }

    private fun sessionKey() = "asset:$asset"

    private fun ensure(): Boolean {
        if (session != null || vulkanHandle != 0L) return true
        synchronized(lock) {
            if (session != null || vulkanHandle != 0L) return true
            if (loadTried) return false
            loadTried = true
            session = OnnxSessions.openAsset(app, asset)
            tryVulkanLocked()
            return session != null || vulkanHandle != 0L
        }
    }

    /**
     * Best-effort Vulkan fast path; leaves [vulkanHandle] at 0 on any failure so ORT stays
     * the fallback. Caller must hold [lock]. The model bytes are read once and shared by
     * the preflight check and the open.
     */
    private fun tryVulkanLocked() {
        try {
            if (!VulkanSessions.isUsable()) return
            val key = sessionKey()
            if (key !in VulkanSessions.allowlist) return
            val modelBytes = try {
                app.assets.open(asset).use { it.readBytes() }
            } catch (e: Throwable) {
                Log.w(TAG, "cannot read $asset for vulkan preflight", e)
                return
            }
            val problem = VulkanSessions.preflight(key) { modelBytes }
            if (problem != null) {
                Log.w(TAG, "vulkan preflight skipped for $asset: $problem")
                return
            }
            val handle = VulkanSessions.open(key, { modelBytes }, null)
            if (handle != 0L) {
                vulkanHandle = handle
                Log.i(TAG, "vulkan session open for $key")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "vulkan open failed for $asset, using ORT", e)
            vulkanHandle = 0L
        }
    }

    /**
     * Vulkan fast path over the already-preprocessed NCHW input.
     *
     * Returns null when the bridge returns no bytes or the fused `d0` output cannot be
     * located, so the caller falls back to ORT. The bridge concatenates every graph output
     * in name order, so `d0` is found by name via the output shapes; without output metadata
     * output 0 is assumed to be `d0`, matching the MAML `record(&[d0])` behaviour.
     */
    private fun vulkanSegment(handle: Long, input: FloatArray): SegmentationMask? {
        val names = arrayOf(INPUT)
        val dtypes = intArrayOf(DTYPE_F32)
        val shapes = longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
        val shapeOffsets = intArrayOf(0)
        val outBytes = VulkanBridge.run(handle, names, dtypes, shapes, shapeOffsets, floatsToLe(input))
            ?: return null
        val floats = leToFloats(outBytes)
        val outNames = try {
            VulkanBridge.lastOutputNames(handle)
        } catch (e: Throwable) {
            Log.w(TAG, "u2netp vulkan output names unavailable", e)
            null
        }
        val outShapes = try {
            VulkanBridge.lastOutputShapes(handle)
        } catch (e: Throwable) {
            Log.w(TAG, "u2netp vulkan output shapes unavailable", e)
            null
        }
        if (outNames != null && outNames.isNotEmpty() && outShapes != null &&
            outShapes.size % outNames.size == 0
        ) {
            val rank = outShapes.size / outNames.size
            var offset = 0
            for (i in outNames.indices) {
                var numel = 1L
                for (r in 0 until rank) numel *= outShapes[i * rank + r]
                val count = numel.toInt()
                if (outNames[i] == OUTPUT) {
                    if (count < SIZE * SIZE || offset + count > floats.size) {
                        Log.w(TAG, "vulkan u2netp output $OUTPUT out of range")
                        return null
                    }
                    return SegmentationMask(SIZE, SIZE, floats.copyOfRange(offset, offset + SIZE * SIZE))
                }
                offset += count
            }
            Log.w(TAG, "vulkan u2netp output $OUTPUT not in ${outNames.toList()}")
            return null
        }
        if (floats.size < SIZE * SIZE) {
            Log.w(TAG, "vulkan u2netp returned ${floats.size} floats, want ${SIZE * SIZE}")
            return null
        }
        return SegmentationMask(SIZE, SIZE, floats.copyOfRange(0, SIZE * SIZE))
    }

    companion object {
        /** What `:photos` ships. */
        const val DEFAULT_ASSET: String = "u2netp.onnx"
        private const val TAG = "SubjectSegmenter"
        private const val SIZE = 320
        private const val INPUT = "input.1"
        /** The fused `d0` output: graph output 0, the only one the MAML path produced. */
        private const val OUTPUT = "1959"

        /** ONNX TensorProto FLOAT, as carried in the Vulkan `dtypes` array. */
        private const val DTYPE_F32 = 1

        private fun floatsToLe(values: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in values) buf.putFloat(v)
            return buf.array()
        }

        private fun leToFloats(bytes: ByteArray): FloatArray {
            val out = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }
    }
}
