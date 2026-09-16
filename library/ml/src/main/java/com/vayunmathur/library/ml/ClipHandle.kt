package com.vayunmathur.library.ml

import android.content.Context
import android.util.Log
import java.io.File
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * On-device image and text embedding in a shared 512-d space: TinyCLIP,
 * ExecuTorch-only (Vulkan fp16 split towers), fail-closed.
 *
 * Two transformers of width 256 with 4 heads of 64 — a 10-layer vision tower over 16x16 patches of a
 * 224x224 image, and a 3-layer causal text tower over up to 77 tokens — at 23.4 million parameters.
 *
 * # ET Vulkan-only, no fallback
 *
 * The split-tower Vulkan fp16 `.pte` pair on ExecuTorch is the only backend (each call
 * runs only the tower it needs — no dummy feed for the unused side). The pair totals
 * ~167 MB, so it is a runtime download into [etDir] (see `PhotosEtModels` in `:photos`),
 * never a bundled asset. When it is absent, the Vulkan delegate is not linked, or a run
 * fails, [imageEmbedding] and [textEmbedding] return null — fail closed with a log, no
 * LiteRT fallback. There is no `.tflite` in this handle.
 *
 * # Neither vector is normalised
 *
 * [imageEmbedding] and [textEmbedding] return the raw projection. The caller normalises — in
 * `:photos` that is `ClipEmbedder.l2Normalize`, so the stored BLOB format is decided in one place.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when the ET pair did not come up — and
 * then both embedding calls return null.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across the embedding calls and [close].
 */
class ClipHandle private constructor(private val source: String) : AutoCloseable {
    private val lock = Any()
    private var etImage: Module? = null
    private var etText: Module? = null
    private var etImageKey: String = ""
    private var etTextKey: String = ""

    /** True if the ET pair came up and is the file this runtime was built against. */
    val isAvailable: Boolean get() = etImage != null && etText != null

    /**
     * True when the ExecuTorch pair backs BOTH towers.
     *
     * The pair is all-or-nothing (see [inDirectory]): a half-open pair would embed images
     * and queries in two different spaces, so a missing tower takes the other down with it.
     * [ClipEmbedder] keys its re-index check off this — the ET pair is a different model
     * (39M) from the old bundled rung (8M), not just a different runtime.
     */
    val isEtActive: Boolean get() = etImage != null && etText != null

    /**
     * The 512-d embedding of an already-preprocessed image, or null on failure.
     *
     * [pixels] is `3 * 224 * 224` floats, NCHW and RGB, resized to the shortest edge, centre-cropped
     * and normalised by CLIP's mean and standard deviation. Not L2-normalised on return.
     * Null means the ET image tower is unavailable or the run failed — fail closed, no fallback.
     */
    fun imageEmbedding(pixels: FloatArray): FloatArray? {
        val mod = etImage ?: run {
            Log.e(TAG, "clip ET image tower unavailable, failing closed")
            return null
        }
        return etImageEmbedding(pixels, mod)
    }

    /**
     * The 512-d embedding of a tokenised query, in the same space as [imageEmbedding], or null.
     *
     * [ids] must end at `<|endoftext|>` with the tokenizer's padding trimmed off, because that is
     * the position CLIP pools. Not L2-normalised on return.
     * Null means the ET text tower is unavailable or the run failed — fail closed, no fallback.
     */
    fun textEmbedding(ids: IntArray): FloatArray? {
        if (ids.isEmpty()) return null
        val mod = etText ?: run {
            Log.e(TAG, "clip ET text tower unavailable, failing closed")
            return null
        }
        return etTextEmbedding(ids, mod)
    }

    /**
     * One ExecuTorch image-tower `forward`: NCHW `[1,3,224,224]` in, `[1,512]` out.
     * Null (never a throw) means fail closed — the caller returns null, no fallback.
     */
    private fun etImageEmbedding(pixels: FloatArray, mod: Module): FloatArray? {
        if (pixels.size != 3 * IMAGE_SIZE * IMAGE_SIZE) {
            Log.w(TAG, "clip ET image wants ${3 * IMAGE_SIZE * IMAGE_SIZE} floats, got ${pixels.size}")
            return null
        }
        return try {
            val outputs = synchronized(lock) {
                ExecutorchSessions.run(
                    mod,
                    listOf(EValue.from(Tensor.fromBlob(pixels, longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())))),
                )
            } ?: return null
            etFirstFloats(outputs, "image")
        } catch (e: Throwable) {
            Log.e(TAG, "clip ET image embedding failed, failing closed", e)
            null
        }
    }

    /**
     * One ExecuTorch text-tower `forward`: int64 `input_ids` + `attention_mask`
     * `[1,77]` in, `[1,512]` out. Null (never a throw) means fail closed.
     *
     * The export is fixed-shape `[1,77]`, so a trimmed query is padded back to
     * [CONTEXT_LENGTH] with zeros and a matching mask (padding positions masked
     * out). The tower is causal and pools at `<|endoftext|>`, so padding after
     * the end token does not move the pooled vector.
     */
    private fun etTextEmbedding(ids: IntArray, mod: Module): FloatArray? {
        if (ids.size > CONTEXT_LENGTH) {
            Log.w(TAG, "clip ET text wants at most $CONTEXT_LENGTH tokens, got ${ids.size}")
            return null
        }
        return try {
            val padded = IntArray(CONTEXT_LENGTH)
            ids.copyInto(padded)
            val longIds = LongArray(CONTEXT_LENGTH) { padded[it].toLong() }
            val mask = LongArray(CONTEXT_LENGTH) { if (it < ids.size) 1L else 0L }
            val outputs = synchronized(lock) {
                ExecutorchSessions.run(
                    mod,
                    listOf(
                        EValue.from(Tensor.fromBlob(longIds, longArrayOf(1, CONTEXT_LENGTH.toLong()))),
                        EValue.from(Tensor.fromBlob(mask, longArrayOf(1, CONTEXT_LENGTH.toLong()))),
                    ),
                )
            } ?: return null
            etFirstFloats(outputs, "text")
        } catch (e: Throwable) {
            Log.e(TAG, "clip ET text embedding failed, failing closed", e)
            null
        }
    }

    /**
     * First output as a 512-d vector, accepting fp16-lowered graphs.
     *
     * The Vulkan fp16 pair may come back [DType.HALF]; failing closed there would
     * silently null every ET call, so HALF is upcast loudly
     * (see `floatsAllowingHalf`). Null means fail closed.
     */
    private fun etFirstFloats(outputs: Array<EValue>, tower: String): FloatArray? {
        val vec = outputs.firstOrNull()?.floatsAllowingHalf("clip ET $tower tower", DIMENSION)
            ?: return null
        if (vec.size != DIMENSION) {
            Log.w(TAG, "clip ET $tower tower produced ${vec.size} floats, want $DIMENSION")
            return null
        }
        return vec
    }

    /** Free the ET pair. Idempotent. */
    override fun close() {
        synchronized(lock) {
            etImage = null
            etText = null
            if (etImageKey.isNotEmpty()) ExecutorchSessions.close(etImageKey)
            if (etTextKey.isNotEmpty()) ExecutorchSessions.close(etTextKey)
            etImageKey = ""
            etTextKey = ""
        }
    }

    override fun toString(): String = "TinyCLIP from $source"

    companion object {
        private const val TAG = "ClipHandle"

        /** The embedding dimension both towers project into. */
        const val DIMENSION = 512

        /** The square RGB input side the vision tower expects. */
        const val IMAGE_SIZE = 224

        /** The longest tokenised query, including both special tokens. */
        const val CONTEXT_LENGTH = 77

        /** Split-tower ET image graph (Vulkan fp16), downloaded — never bundled. */
        const val IMAGE_ET_FILE = "tinyclip_image_vulkan_fp16.pte"

        /** Split-tower ET text graph (Vulkan fp16), downloaded — never bundled. */
        const val TEXT_ET_FILE = "tinyclip_text_vulkan_fp16.pte"

        /**
         * ET-only handle: the split-tower `.pte` pair from [etDir], fail-closed.
         *
         * The pair totals ~167 MB, so it is a runtime download (see `PhotosEtModels`
         * in `:photos`), never a bundled asset: [etDir] is the app's external files
         * directory and the files sit at its root under [IMAGE_ET_FILE]/[TEXT_ET_FILE].
         *
         * The pair is all-or-nothing: a half-open pair (one tower loaded, the other
         * missing) would embed images and queries in two different spaces, so the
         * loaded half is closed again and the handle reports unavailable.
         * Construction never throws; a handle without the pair reports
         * [ClipHandle.isAvailable] false and every embedding call returns null.
         * No LiteRT fallback — failures log and return null.
         */
        fun inDirectory(
            context: Context,
            etDir: File,
        ): ClipHandle {
            val instance = ClipHandle(etDir.name)
            context.applicationContext // keep signature for callers; ET path needs no Context
            val backends = ExecutorchSessions.registeredBackends()
            if (backends?.any { it.contains("Vulkan", ignoreCase = true) } == true) {
                instance.etImageKey = "file:${File(etDir, IMAGE_ET_FILE).absolutePath}"
                instance.etTextKey = "file:${File(etDir, TEXT_ET_FILE).absolutePath}"
                val image = ExecutorchSessions.openPath(File(etDir, IMAGE_ET_FILE).absolutePath)
                val text = ExecutorchSessions.openPath(File(etDir, TEXT_ET_FILE).absolutePath)
                if (image != null && text != null) {
                    instance.etImage = image
                    instance.etText = text
                    Log.i(TAG, "TinyCLIP ET pair open from $etDir")
                } else {
                    // Half-open pair: close the loaded half so both towers stay in one space.
                    ExecutorchSessions.close(instance.etImageKey)
                    ExecutorchSessions.close(instance.etTextKey)
                    instance.etImageKey = ""
                    instance.etTextKey = ""
                    Log.e(TAG, "TinyCLIP ET pair incomplete in $etDir, failing closed")
                }
            } else {
                Log.e(TAG, "Vulkan backend absent, TinyCLIP ET pair unavailable, failing closed")
            }
            if (!instance.isAvailable) Log.e(TAG, "cannot open TinyCLIP ET pair in $etDir")
            return instance
        }
    }
}
