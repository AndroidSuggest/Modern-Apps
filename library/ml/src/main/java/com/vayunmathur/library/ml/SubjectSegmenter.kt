package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
        val live = session ?: return null
        val (pixels, readable) = OnnxPreprocess.readablePixels(bitmap) ?: return null
        try {
            val input = OnnxPreprocess.stretchPlanar(
                pixels, readable.width, readable.height,
                SIZE, SIZE, OnnxPreprocess.IMAGENET,
            )
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
                live.run(mapOf(INPUT to tensor), setOf(OUTPUT)).use { result ->
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
            session = null
            OnnxSessions.close(sessionKey())
        }
    }

    private fun sessionKey() = "asset:$asset"

    private fun ensure(): Boolean {
        session?.let { return true }
        synchronized(lock) {
            session?.let { return true }
            if (loadTried) return false
            loadTried = true
            session = OnnxSessions.openAsset(app, asset)
            return session != null
        }
    }

    companion object {
        /** What `:photos` ships. */
        const val DEFAULT_ASSET: String = "u2netp.onnx"
        private const val TAG = "SubjectSegmenter"
        private const val SIZE = 320
        private const val INPUT = "input.1"
        /** The fused `d0` output: graph output 0, the only one the MAML path produced. */
        private const val OUTPUT = "1959"
    }
}
