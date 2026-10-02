package com.vayunmathur.openassistant.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.vayunmathur.library.downloadservice.ModelUrls
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device **SigLIP2-base** image/text embedder on ONNX Runtime, the semantic
 * search provider OpenAssistant serves to the photos app over [InferenceService].
 *
 * SigLIP puts images and text in one shared space: the image encoder turns a
 * photo into a vector, the text encoder turns a query into a vector, and their
 * cosine similarity says how well the phrase describes the photo. photos stores
 * the returned vectors and does cosine locally.
 *
 * Mirrors the old on-device `photos/.../ClipEmbedder.kt` (lazy single-threaded
 * [OrtSession]s, [OnnxTensor] + `run`, L2-normalize, [floatsToBytes]) but with
 * SigLIP2 preprocessing and the Gemma [SentencePieceTokenizer]:
 *  - **Vision** ([VISION_FILE]): resize **directly** to [IMAGE_SIZE]² (square,
 *    no shortest-side + center-crop), rescale 1/255, normalize mean/std 0.5 →
 *    `(px-127.5)/127.5`, packed NCHW `[1,3,224,224]`.
 *  - **Text** ([TEXT_FILE]): tokenize with [SentencePieceTokenizer] to a fixed
 *    [SentencePieceTokenizer.SEQ_LEN] and run the text tower.
 *
 * The embedding [dim] is read from the model at run time (SigLIP2-base = 768)
 * and flows back to photos via the IPC `dim` key, so nothing hardcodes it.
 *
 * The three model files are downloaded on demand to `getExternalFilesDir(null)`
 * (see [InferenceService]); if any is missing [filesPresent] is false and the
 * caller triggers the download instead.
 */
object SiglipEmbedder {
    /** HuggingFace source, also the model id photos uses for change-detection. */
    const val MODEL_ID = "onnx-community/siglip2-base-patch16-224-ONNX"

    /**
     * Bump when the downloaded SigLIP2 files or preprocessing change, so
     * [com.vayunmathur.openassistant.util.AssistantViewModel] deletes the stale
     * on-disk files and they re-download on the next embedding request.
     */
    const val MODEL_VERSION = 1

    // On-disk names shared with the mirror-first downloader (single source of
    // truth in ModelUrls) so file-presence gating and the fetch always agree.
    const val VISION_FILE = ModelUrls.SIGLIP_VISION_FILE
    const val TEXT_FILE = ModelUrls.SIGLIP_TEXT_FILE
    const val TOKENIZER_FILE = ModelUrls.SIGLIP_TOKENIZER_FILE

    /** Square RGB input side the SigLIP2 image encoder expects. */
    const val IMAGE_SIZE = 224

    private const val TAG = "SiglipEmbedder"

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Any()

    @Volatile private var visionSession: OrtSession? = null
    @Volatile private var textSession: OrtSession? = null
    @Volatile private var tokenizer: SentencePieceTokenizer? = null
    @Volatile private var visionOutputName: String? = null
    @Volatile private var textOutputName: String? = null
    @Volatile private var initTried = false
    @Volatile private var initOk = false

    /** True only if all three model files are present on disk. */
    fun filesPresent(context: Context): Boolean =
        modelFile(context, VISION_FILE).exists() &&
            modelFile(context, TEXT_FILE).exists() &&
            modelFile(context, TOKENIZER_FILE).exists()

    /** True if both encoders + tokenizer are loaded and ready. */
    fun isAvailable(context: Context): Boolean = ensureInit(context)

    /** Embedding dimension read from the vision model, or 0 if not initialised. */
    fun dim(context: Context): Int {
        if (!ensureInit(context)) return 0
        return cachedDim
    }

    @Volatile private var cachedDim = 0

    private fun modelFile(context: Context, name: String): File =
        File(context.applicationContext.getExternalFilesDir(null), name)

    private fun ensureInit(context: Context): Boolean {
        if (initOk) return true
        synchronized(lock) {
            if (initOk) return true
            if (initTried) return false
            initTried = true
            return try {
                val app = context.applicationContext
                if (!filesPresent(app)) {
                    Log.w(TAG, "SigLIP2 model files missing; embedder unavailable")
                    initTried = false // allow a retry once files are downloaded
                    return false
                }
                val tok = SentencePieceTokenizer.load(modelFile(app, TOKENIZER_FILE)) ?: run {
                    Log.w(TAG, "Failed to load SentencePiece tokenizer")
                    return false
                }
                val opts = OrtSession.SessionOptions().apply {
                    // Single-threaded keeps sustained CPU/battery use low.
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                }
                visionSession = env.createSession(modelFile(app, VISION_FILE).absolutePath, opts)
                textSession = env.createSession(modelFile(app, TEXT_FILE).absolutePath, opts)
                tokenizer = tok
                // These sub-model exports emit BOTH `last_hidden_state` and the
                // pooled embedding (`pooler_output`); we must read the pooled one,
                // not output index 0, or the vectors are meaningless.
                visionOutputName = bestOutputName(visionSession!!)
                textOutputName = bestOutputName(textSession!!)
                cachedDim = visionSession?.let { readOutputDim(it, visionOutputName!!) } ?: 0
                initOk = true
                Log.i(TAG, "SigLIP2 embedder ready (dim=$cachedDim, visionOut=$visionOutputName, textOut=$textOutputName)")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialise SigLIP2 embedder", e)
                closeLocked()
                false
            }
        }
    }

    /**
     * Pick the output that holds the pooled embedding. SigLIP2's vision/text
     * sub-model exports emit `last_hidden_state` (rank-3) plus the pooled
     * `pooler_output` (rank-2 `[1, dim]`) — the latter is the retrieval vector.
     * Prefer a name hinting "pool"/"embed", else the first rank-2 output, else
     * output 0.
     */
    private fun bestOutputName(session: OrtSession): String {
        val info = session.outputInfo
        info.keys.firstOrNull { it.contains("pool", ignoreCase = true) }?.let { return it }
        info.keys.firstOrNull { it.contains("embed", ignoreCase = true) }?.let { return it }
        info.entries.firstOrNull { (it.value.info as? ai.onnxruntime.TensorInfo)?.shape?.size == 2 }
            ?.let { return it.key }
        return info.keys.first()
    }

    private fun readOutputDim(session: OrtSession, outputName: String): Int {
        return try {
            val info = session.outputInfo[outputName]!!.info as ai.onnxruntime.TensorInfo
            info.shape.last().toInt()
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Embed the image at [file] into an L2-normalised vector, or null on
     * failure. The file is a decoded copy handed over by [InferenceService].
     */
    fun imageEmbedding(context: Context, file: File): FloatArray? {
        if (!ensureInit(context)) return null
        val session = visionSession ?: return null
        val bitmap = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image ${file.absolutePath}", e)
            null
        } ?: return null
        val input = try {
            preprocess(bitmap)
        } finally {
            bitmap.recycle()
        }
        return try {
            synchronized(lock) {
                val inputName = session.inputNames.iterator().next()
                val outName = visionOutputName ?: bestOutputName(session)
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(input),
                    longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong()),
                ).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        val out = result.get(outName).get() as OnnxTensor
                        val vec = FloatArray(out.info.shape.last().toInt())
                        out.floatBuffer.get(vec)
                        l2Normalize(vec)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image embedding failed", e)
            null
        }
    }

    /**
     * Embed [text] into an L2-normalised vector in the SAME space as
     * [imageEmbedding], or null on failure.
     */
    fun textEmbedding(context: Context, text: String): FloatArray? {
        if (!ensureInit(context)) return null
        val session = textSession ?: return null
        val tok = tokenizer ?: return null
        val ids = tok.encode(text)
        val longIds = LongArray(ids.size) { ids[it].toLong() }
        return try {
            synchronized(lock) {
                val tensors = HashMap<String, OnnxTensor>()
                try {
                    for (name in session.inputNames) {
                        val lname = name.lowercase()
                        when {
                            lname.contains("mask") -> {
                                // SigLIP attends over the full padded sequence; all-ones.
                                val mask = LongArray(ids.size) { 1L }
                                tensors[name] = OnnxTensor.createTensor(
                                    env, LongBuffer.wrap(mask), longArrayOf(1, ids.size.toLong())
                                )
                            }
                            lname.contains("position") -> {
                                // Sequential positions 0..seqLen-1.
                                val pos = LongArray(ids.size) { it.toLong() }
                                tensors[name] = OnnxTensor.createTensor(
                                    env, LongBuffer.wrap(pos), longArrayOf(1, ids.size.toLong())
                                )
                            }
                            else -> {
                                // input_ids
                                tensors[name] = OnnxTensor.createTensor(
                                    env, LongBuffer.wrap(longIds), longArrayOf(1, ids.size.toLong())
                                )
                            }
                        }
                    }
                    session.run(tensors).use { result ->
                        val outName = textOutputName ?: bestOutputName(session)
                        val out = result.get(outName).get() as OnnxTensor
                        val vec = FloatArray(out.info.shape.last().toInt())
                        out.floatBuffer.get(vec)
                        l2Normalize(vec)
                    }
                } finally {
                    tensors.values.forEach { it.close() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Text embedding failed", e)
            null
        }
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        try { visionSession?.close() } catch (_: Exception) {}
        try { textSession?.close() } catch (_: Exception) {}
        visionSession = null
        textSession = null
        tokenizer = null
        visionOutputName = null
        textOutputName = null
        cachedDim = 0
        initOk = false
        initTried = false
    }

    /**
     * SigLIP preprocessing: resize [src] **directly** to [IMAGE_SIZE]² (no
     * center-crop), rescale to [0,1], normalize mean/std 0.5 → (px-127.5)/127.5,
     * packed NCHW (RGB).
     */
    private fun preprocess(src: Bitmap): FloatArray {
        val safe = if (src.config == Bitmap.Config.HARDWARE || src.config == null) {
            src.copy(Bitmap.Config.ARGB_8888, false)
        } else src

        val scaled = Bitmap.createScaledBitmap(safe, IMAGE_SIZE, IMAGE_SIZE, true)
        val px = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        scaled.getPixels(px, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
        if (scaled != safe) scaled.recycle()
        if (safe != src) safe.recycle()

        val area = IMAGE_SIZE * IMAGE_SIZE
        val out = FloatArray(3 * area)
        for (i in 0 until area) {
            val p = px[i]
            out[i] = (((p shr 16) and 0xFF) - 127.5f) / 127.5f          // R plane
            out[area + i] = (((p shr 8) and 0xFF) - 127.5f) / 127.5f    // G plane
            out[2 * area + i] = ((p and 0xFF) - 127.5f) / 127.5f        // B plane
        }
        return out
    }

    // ---- Shared math + serialisation helpers ----

    fun l2Normalize(values: FloatArray): FloatArray {
        var norm = 0f
        for (v in values) norm += v * v
        norm = sqrt(norm)
        if (norm == 0f) return values
        return FloatArray(values.size) { values[it] / norm }
    }

    fun floatsToBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buffer.putFloat(v)
        return buffer.array()
    }
}
