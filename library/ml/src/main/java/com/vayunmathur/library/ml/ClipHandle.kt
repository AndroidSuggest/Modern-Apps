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
 * When the Vulkan backend is usable and this graph is allowlisted, inference runs on the
 * Vulkan fast path and ORT is kept only as the fallback: a Vulkan failure (or short output)
 * falls back to the ORT session when it exists, preserving the null-on-failure contract.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across the embedding calls and [close].
 */
class ClipHandle private constructor(private val source: String) : AutoCloseable {
    private var session: OrtSession? = null
    private var assetPath: String = GRAPH
    private var vulkanHandle: Long = 0L

    /** True if the graph came up on Vulkan or ORT and is the file this runtime was built against. */
    val isAvailable: Boolean get() = vulkanHandle != 0L || session != null

    /**
     * The 512-d embedding of an already-preprocessed image, or null on failure.
     *
     * [pixels] is `3 * 224 * 224` floats, NCHW and RGB, resized to the shortest edge, centre-cropped
     * and normalised by CLIP's mean and standard deviation. Not L2-normalised on return.
     */
    fun imageEmbedding(pixels: FloatArray): FloatArray? {
        if (vulkanHandle != 0L) {
            try {
                vulkanRun(
                    pixels, DUMMY_IDS,
                    attentionMask(DUMMY_IDS.size),
                    OUT_IMAGE_INDEX,
                )?.let { return it }
            } catch (e: Throwable) {
                Log.w(TAG, "clip vulkan image embedding failed, falling back to ORT", e)
            }
        }
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
        if (ids.isEmpty()) return null
        if (vulkanHandle != 0L) {
            try {
                vulkanRun(
                    dummyPixels(), ids,
                    attentionMask(ids.size),
                    OUT_TEXT_INDEX,
                )?.let { return it }
            } catch (e: Throwable) {
                Log.w(TAG, "clip vulkan text embedding failed, falling back to ORT", e)
            }
        }
        val live = session ?: return null
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

    /** Free the Vulkan and/or ORT session. Idempotent. */
    override fun close() {
        val handle = vulkanHandle
        vulkanHandle = 0L
        if (handle != 0L) {
            try {
                VulkanSessions.close(handle)
            } catch (e: Throwable) {
                Log.w(TAG, "clip vulkan close failed", e)
            }
        }
        session = null
        OnnxSessions.close("asset:$assetPath")
    }

    override fun toString(): String = "TinyCLIP from $source"

    /**
     * Best-effort Vulkan fast path; leaves [vulkanHandle] at 0 on any failure so ORT stays
     * the fallback. The model bytes are read once and shared by the preflight check
     * and the open.
     */
    private fun tryVulkan(assets: AssetManager, path: String) {
        try {
            if (!VulkanSessions.isUsable()) return
            val key = "asset:$path"
            if (key !in VulkanSessions.allowlist) return
            val modelBytes = try {
                assets.open(path).use { it.readBytes() }
            } catch (e: Throwable) {
                Log.w(TAG, "cannot read $path for vulkan preflight", e)
                return
            }
            val problem = VulkanSessions.preflight(key) { modelBytes }
            if (problem != null) {
                Log.w(TAG, "vulkan preflight skipped for $path: $problem")
                return
            }
            val handle = VulkanSessions.open(key) { modelBytes }
            if (handle != 0L) {
                vulkanHandle = handle
                Log.i(TAG, "vulkan session open for $key")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "vulkan open failed for $path, using ORT", e)
            vulkanHandle = 0L
        }
    }

    /**
     * Vulkan fast path: the same inputs as [run], in the model's declared input order
     * (`input_ids`, `pixel_values`, `attention_mask`), marshalled through [VulkanWire].
     *
     * The combined graph emits both embeddings on every call, `image_embeds` first and
     * `text_embeds` second, both `[1, 512]` f32; [outputIndex] selects between them.
     * Returns null when the bridge fails or the wanted output is missing, so the caller
     * falls back to ORT.
     */
    private fun vulkanRun(
        pixels: FloatArray,
        ids: IntArray,
        mask: LongArray,
        outputIndex: Int,
    ): FloatArray? {
        val handle = vulkanHandle
        if (handle == 0L) return null
        val tokens = ids.size.toLong()
        val longIds = LongArray(ids.size) { ids[it].toLong() }
        val inputs = listOf(
            VulkanWire.longs(longArrayOf(1, tokens), longIds),
            VulkanWire.floats(longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong()), pixels),
            VulkanWire.longs(longArrayOf(1, tokens), mask),
        )
        val outputs = VulkanSessions.run(handle, inputs) ?: return null
        val embeds = outputs.filter {
            it.dtype == VulkanWire.DTYPE_F32 && it.bytes.size / 4 == DIMENSION
        }
        val out = embeds.getOrNull(outputIndex) ?: run {
            Log.w(TAG, "clip vulkan produced no $DIMENSION-wide output at index $outputIndex")
            return null
        }
        return out.asFloats()
    }

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
        pixelTensor.useOrt {
            idTensor.useOrt {
                maskTensor.useOrt {
                    val inputs = LinkedHashMap<String, OnnxTensor>(3)
                    inputs[IN_PIXELS] = pixelTensor
                    inputs[IN_IDS] = idTensor
                    inputs[IN_MASK] = maskTensor
                    session.run(inputs, setOf(wanted)).useOrt { result ->
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

        /** Output positions in the combined graph: image_embeds first, text_embeds second. */
        private const val OUT_IMAGE_INDEX = 0
        private const val OUT_TEXT_INDEX = 1

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
            instance.tryVulkan(assets, path)
            return instance
        }

        private fun attentionMask(size: Int): LongArray = LongArray(size) { 1L }

        private fun dummyPixels(): FloatArray = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)
    }
}
