package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/**
 * Person-versus-background segmentation, for `:camera`'s portrait bokeh.
 *
 * **MediaPipe Selfie Segmentation** (Apache-2.0) at 256×256, run on the reduced ONNX Runtime
 * build. See `camera/src/main/assets/README.md` for provenance.
 *
 * Replaces the Vulkan `NativeSegmenter` path (`MlNative::createSelfie`): the graph is the
 * upstream export (`pixel_values [1,3,256,256]` in, `alphas [1,1,256,256]` out), so the
 * resize (`OnnxPreprocess.stretchPlanar`, straight scale) and `RESCALE_ONLY` normalisation
 * now happen here in Kotlin rather than natively.
 *
 * Replaces `com.vayunmathur.ncnn.PortraitSegmenter` and its `erdnet` model, which shipped
 * with no upstream URL, license or conversion recipe. This is a different model rather
 * than a port of that one, so its masks differ.
 *
 * CPU-backed: if [isAvailable] is false the model is missing or has an operator outside
 * the reduced build, and [segment] returns null, which callers treat as "no mask" rather
 * than as an error.
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
class SelfieSegmenter(context: Context, assetName: String = DEFAULT_ASSET) : AutoCloseable {
    private val app = context.applicationContext
    private val asset = assetName
    private val lock = Any()

    @Volatile private var session: OrtSession? = null
    @Volatile private var vulkanHandle: Long = 0L
    @Volatile private var loadTried = false

    /** Whether the network came up. False means portrait bokeh is off on this device. */
    val isAvailable: Boolean get() = ensure()

    /**
     * Run the network over [bitmap] and return a 256×256 mask, or null on failure.
     *
     * [bitmap] may be any size and any config: it is resized and normalised here.
     */
    fun segment(bitmap: Bitmap): SegmentationMask? {
        if (!ensure()) return null
        val (pixels, readable) = OnnxPreprocess.readablePixels(bitmap) ?: return null
        try {
            val input = OnnxPreprocess.stretchPlanar(
                pixels, readable.width, readable.height,
                SIZE, SIZE, OnnxPreprocess.RESCALE_ONLY,
            )
            if (vulkanHandle != 0L) {
                try {
                    vulkanSegment(vulkanHandle, input)?.let { return it }
                } catch (e: Throwable) {
                    Log.w(TAG, "selfie vulkan inference failed, falling back to ORT", e)
                }
            }
            val live = session ?: return null
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).useOrt { tensor ->
                live.run(mapOf(INPUT to tensor)).useOrt { result ->
                    val out = result[0] as OnnxTensor
                    val flat = FloatArray(SIZE * SIZE)
                    out.floatBuffer.get(flat)
                    return SegmentationMask(SIZE, SIZE, flat)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "selfie inference failed", e)
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
                    Log.w(TAG, "selfie vulkan close failed", e)
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
            val handle = VulkanSessions.open(key) { modelBytes }
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
     * Returns null when the bridge fails or produces too few floats, so the caller falls
     * back to ORT. The graph has a single `alphas [1,1,256,256]` output, so the mask is the
     * first f32 output at least [SIZE]×[SIZE] wide.
     */
    private fun vulkanSegment(handle: Long, input: FloatArray): SegmentationMask? {
        val inputs = listOf(
            VulkanWire.floats(longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()), input),
        )
        val outputs = VulkanSessions.run(handle, inputs) ?: return null
        val out = outputs.firstOrNull {
            it.dtype == VulkanWire.DTYPE_F32 && it.bytes.size / 4 >= SIZE * SIZE
        } ?: run {
            Log.w(TAG, "vulkan selfie produced no ${SIZE * SIZE}-wide output")
            return null
        }
        return SegmentationMask(SIZE, SIZE, out.asFloats().copyOfRange(0, SIZE * SIZE))
    }

    companion object {
        /** What `:camera` ships. */
        const val DEFAULT_ASSET: String = "selfie_segmentation.onnx"
        private const val TAG = "SelfieSegmenter"
        private const val SIZE = 256
        private const val INPUT = "pixel_values"
    }
}
