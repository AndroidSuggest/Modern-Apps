package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * Person-versus-background segmentation, for `:camera`'s portrait bokeh.
 *
 * **MediaPipe Selfie Segmentation** (Apache-2.0) at 256×256, ExecuTorch-only on the
 * Vulkan fp16 `.pte` (`selfie_segmentation_vulkan_fp16.pte`, ~275 KB). See
 * `camera/src/main/assets/README.md` for provenance.
 *
 * `forward` takes the planar CHW buffer straight from `OnnxPreprocess.stretchPlanar`
 * as `[1,3,256,256]` NCHW float and returns the single-channel `[1,1,256,256]`
 * probability mask. No CHW→NHWC interleave and no LiteRT fallback: when the `.pte`
 * is absent, the Vulkan delegate is not linked, or the run fails, [segment] returns
 * null (fail closed — "no mask", never an error) and portrait bokeh stays off.
 *
 * Resize (`OnnxPreprocess.stretchPlanar`, straight scale) and `RESCALE_ONLY`
 * normalisation happen here in Kotlin.
 *
 * Not thread-safe: [segment] and [close] must not overlap.
 *
 * @param context used to read assets and to stage the `.pte`; the application context is
 * retained for the lazy ExecuTorch open.
 * @param etAssetName the `.pte` in the app's assets.
 */
class SelfieSegmenter(
    context: Context,
    etAssetName: String = ET_ASSET,
) : AutoCloseable {
    private val app = context.applicationContext
    private val etAsset = etAssetName
    private val lock = Any()

    @Volatile private var etModule: Module? = null
    @Volatile private var etTried = false

    /** Whether the network came up. False means portrait bokeh is off on this device. */
    val isAvailable: Boolean get() = ensureEt() != null

    /**
     * Run the network over [bitmap] and return a 256×256 mask, or null on failure.
     *
     * [bitmap] may be any size and any config: it is resized and normalised here.
     * Null (missing `.pte`, no Vulkan delegate, failed run) means "no mask" — no
     * fallback is attempted.
     */
    fun segment(bitmap: Bitmap): SegmentationMask? {
        val mod = ensureEt() ?: return null
        val (pixels, readable) = OnnxPreprocess.readablePixels(bitmap) ?: return null
        try {
            val planar = OnnxPreprocess.stretchPlanar(
                pixels, readable.width, readable.height,
                SIZE, SIZE, OnnxPreprocess.RESCALE_ONLY,
            )
            // No interleave: ET wants NCHW, which planar already is.
            val input = EValue.from(
                Tensor.fromBlob(planar, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())),
            )
            val outs = synchronized(lock) {
                ExecutorchSessions.run(mod, listOf(input))
            } ?: return null
            // HALF-tolerant: the Vulkan fp16 graph may return binary16 (see EtTensors).
            val out = outs.firstOrNull()?.floatsAllowingHalf(TAG, SIZE * SIZE) ?: return null
            return SegmentationMask(SIZE, SIZE, out.copyOfRange(0, SIZE * SIZE))
        } catch (e: Throwable) {
            Log.e(TAG, "selfie ET inference failed", e)
            return null
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    override fun close() {
        synchronized(lock) {
            etModule = null
            ExecutorchSessions.close(etSessionKey())
        }
    }

    private fun etSessionKey() = "asset:$etAsset"

    /**
     * The ExecuTorch module, loaded once. Null when the `.pte` is absent, the Vulkan
     * delegate is not linked (`library/ml/libs/executorch-vulkan-1.4.0.aar`,
     * XNNPACK=OFF), or the load failed — all of which mean "unavailable", never a throw.
     */
    private fun ensureEt(): Module? {
        if (etModule != null) return etModule
        synchronized(lock) {
            if (etModule != null) return etModule
            if (etTried) return null
            etTried = true
            val backends = ExecutorchSessions.registeredBackends()
            if (backends?.any { it.contains("Vulkan", ignoreCase = true) } != true) {
                Log.i(TAG, "Vulkan backend absent, skipping $etAsset")
                return null
            }
            etModule = ExecutorchSessions.openAsset(app, etAsset)
            return etModule
        }
    }

    companion object {
        /** The ExecuTorch graph (Vulkan fp16). */
        const val ET_ASSET: String = "selfie_segmentation_vulkan_fp16.pte"
        private const val TAG = "SelfieSegmenter"
        private const val SIZE = 256
    }
}
