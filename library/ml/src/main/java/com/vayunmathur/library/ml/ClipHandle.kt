package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device image and text embedding in a shared 512-d space: TinyCLIP on the reduced ONNX
 * Runtime build.
 *
 * Two transformers of width 256 with 4 heads of 64 — a 10-layer vision tower over 16x16 patches of a
 * 224x224 image, and a 3-layer causal text tower over up to 77 tokens — at 23.4 million parameters.
 *
 * # One bundled asset
 *
 * `clip/model_int8.onnx` ships inside the APK, so this has an [inAssets] and no download. The
 * export is a **single combined graph**: `input_ids` + `pixel_values` + `attention_mask` are
 * all required on every call, and it returns both `image_embeds` and `text_embeds`. So each
 * call passes a cheap dummy for the side it does not need and reads only the output it wants.
 *
 * An asset must be stored **uncompressed** for the byte-read below to avoid an inflate into
 * a heap buffer: `noCompress += "onnx"` in `photos/build.gradle.kts`. Int8 weights barely
 * deflate, so it costs nothing on download size.
 *
 * # Neither vector is normalised
 *
 * [imageEmbedding] and [textEmbedding] return the raw projection. The caller normalises — in
 * `:photos` that is `ClipEmbedder.l2Normalize`, so the stored BLOB format is decided in one place.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when the asset is absent or has an
 * operator outside the reduced build — and then both embedding calls return null.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across the embedding calls and [close].
 */
class ClipHandle private constructor(private val source: String) : AutoCloseable {
    private var session: OrtSession? = null
    private var assetPath: String = GRAPH

    /** True if the graph came up and is the file this runtime was built against. */
    val isAvailable: Boolean get() = session != null

    /**
     * The 512-d embedding of an already-preprocessed image, or null on failure.
     *
     * [pixels] is `3 * 224 * 224` floats, NCHW and RGB, resized to the shortest edge, centre-cropped
     * and normalised by CLIP's mean and standard deviation. Not L2-normalised on return.
     */
    fun imageEmbedding(pixels: FloatArray): FloatArray? {
        val live = session ?: return null
        return try {
            run(
                live,
                pixels, DUMMY_IDS,
                attentionMask(DUMMY_IDS.size),
                OUT_IMAGE,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "clip image embedding failed", e)
            null
        }
    }

    /**
     * The 512-d embedding of a tokenised query, in the same space as [imageEmbedding], or null.
     *
     * [ids] must end at `<|endoftext|>` with the tokenizer's padding trimmed off, because that is
     * the position CLIP pools. Not L2-normalised on return.
     */
    fun textEmbedding(ids: IntArray): FloatArray? {
        val live = session ?: return null
        if (ids.isEmpty()) return null
        return try {
            run(
                live,
                dummyPixels(), ids,
                attentionMask(ids.size),
                OUT_TEXT,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "clip text embedding failed", e)
            null
        }
    }

    /** Free the session. Idempotent. */
    override fun close() {
        session = null
        OnnxSessions.close("asset:$assetPath")
    }

    override fun toString(): String = "TinyCLIP from $source"

    private fun run(
        session: OrtSession,
        pixels: FloatArray,
        ids: IntArray,
        mask: LongArray,
        wanted: String,
    ): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val pixelTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong()),
        )
        val longIds = LongArray(ids.size) { ids[it].toLong() }
        val idShape = longArrayOf(1, ids.size.toLong())
        val idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longIds), idShape)
        val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), idShape)
        pixelTensor.use {
            idTensor.use {
                maskTensor.use {
                    val inputs = LinkedHashMap<String, OnnxTensor>(3)
                    inputs[IN_PIXELS] = pixelTensor
                    inputs[IN_IDS] = idTensor
                    inputs[IN_MASK] = maskTensor
                    session.run(inputs, setOf(wanted)).use { result ->
                        val out = result.get(wanted).get() as OnnxTensor
                        val vec = FloatArray(out.info.shape.last().toInt())
                        out.floatBuffer.get(vec)
                        return vec
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ClipHandle"

        /** The embedding dimension both towers project into. */
        const val DIMENSION = 512

        /** The square RGB input side the vision tower expects. */
        const val IMAGE_SIZE = 224

        /** The longest tokenised query, including both special tokens. */
        const val CONTEXT_LENGTH = 77

        /** The bundled export. Native checks nothing about it; a wrong file fails at load. */
        const val GRAPH = "clip/model_int8.onnx"

        private const val IN_PIXELS = "pixel_values"
        private const val IN_IDS = "input_ids"
        private const val IN_MASK = "attention_mask"
        private const val OUT_IMAGE = "image_embeds"
        private const val OUT_TEXT = "text_embeds"

        private val DUMMY_IDS = IntArray(CONTEXT_LENGTH)

        /**
         * The model from the APK's assets, which is the only place it lives.
         *
         * No `inDirectory` counterpart: at ~24 MB this is bundled, so
         * there is no download directory to look in.
         */
        fun inAssets(assets: AssetManager, path: String = GRAPH): ClipHandle {
            val instance = ClipHandle("the APK's $path")
            instance.assetPath = path
            instance.session = OnnxSessions.openAssetManager(assets, path)
            if (instance.session == null) Log.e(TAG, "cannot open $path")
            return instance
        }

        private fun attentionMask(size: Int): LongArray = LongArray(size) { 1L }

        private fun dummyPixels(): FloatArray = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)
    }
}
