package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * Face detection and face embedding, for `:photos`'s people clustering.
 *
 * **SCRFD 500M** finds faces and their five keypoints; **MobileFaceNet** (`w600k_mbf`,
 * ArcFace-trained) turns an aligned crop into a 512-d embedding. Both run on ExecuTorch
 * over the pure-Vulkan runtime — see `photos/src/main/assets/README.md` for provenance
 * and the InsightFace licence.
 *
 * These replace `com.vayunmathur.ncnn.FaceDetector` and `FaceEmbedder`. The embeddings are
 * **not** bit-identical to ncnn's, so `FaceRecognizer.EMBEDDER_VERSION`
 * has to be bumped alongside this — the stored index would otherwise mix two incompatible
 * embedding spaces and cluster nonsense.
 *
 * # SCRFD decode
 *
 * The export declares nine outputs: three score maps, three box maps, three keypoint maps
 * at strides 8, 16 and 32. Each is flattened from a `[H, W, anchors]` transpose
 * (`perm [2,3,0,1]`), so the layout is **cell-major**: element `(row, col, anchor)` sits
 * at `flat[(row * w + col) * 2 + anchor]`. The box math (centre at `col * stride` with no
 * half-cell offset, `+1` on extents, five keypoints as stride-scaled offsets) and the
 * greedy stable NMS (score ≥ 0.5 keep, IoU strictly above 0.45 suppresses) are unchanged.
 *
 * The input is letterboxed into a 640×640 square (`OnnxPreprocess.SquareFit`, scale
 * `640 / long side`, truncated, centred, border raw-zero through the SCRFD
 * normalisation), and boxes are mapped back onto the source bitmap as `0..1` fractions.
 */

/** One detected face. Every coordinate is a fraction of the source bitmap, `0..1`. */
data class DetectedFaceBox(
    /** Left edge. */
    val left: Float,
    /** Top edge. */
    val top: Float,
    /** Right edge. */
    val right: Float,
    /** Bottom edge. */
    val bottom: Float,
    /** Left eye, in the detector's keypoint order rather than the viewer's left. */
    val leftEyeX: Float,
    /** Left eye. */
    val leftEyeY: Float,
    /** Right eye. */
    val rightEyeX: Float,
    /** Right eye. */
    val rightEyeY: Float,
    /** Detector confidence, post-sigmoid. */
    val score: Float,
)

/**
 * SCRFD 500M face detection, ExecuTorch Vulkan-only, fail-closed.
 *
 * Not thread-safe: [detect] and [close] must not overlap.
 *
 * The only path is the Vulkan fp16 `.pte` (`scrfd500_vulkan_fp16.pte`,
 * ~1.3 MB) on ExecuTorch: `forward` takes the `letterboxPlanar` buffer directly as
 * NCHW `[1,3,640,640]` f32 and returns the nine score/box/keypoint maps positionally.
 * When the `.pte` is absent, the Vulkan delegate is not linked, or the run fails,
 * [detect] returns an empty list — there is no LiteRT fallback.
 *
 * @param context used to read assets and to stage the `.pte`; the application context is
 * retained for the lazy ExecuTorch open.
 * @param etAssetName the `.pte` in the app's assets.
 */
class FaceDetector(
    context: Context,
    etAssetName: String = ET_ASSET,
) : AutoCloseable {
    private val app = context.applicationContext
    private val etAsset = etAssetName
    private val lock = Any()

    @Volatile private var etModule: Module? = null
    @Volatile private var etTried = false

    /** Whether the detector came up. False means people clustering is off. */
    val isAvailable: Boolean get() = ensureEt() != null

    /**
     * Every face in [bitmap], or an empty list if there are none or the detector is
     * unavailable.
     *
     * [bitmap] may be any size: it is letterboxed and normalised here.
     */
    fun detect(bitmap: Bitmap): List<DetectedFaceBox> {
        val mod = ensureEt() ?: return emptyList()
        return detectEt(bitmap, mod) ?: emptyList()
    }

    /**
     * One ExecuTorch `forward` invocation over [bitmap], or null on failure.
     *
     * The export returns the nine maps positionally (score_8/16/32, bbox_8/16/32,
     * kps_8/16/32 — see `analysis/et-face/ET_FACE_DROPIN.md` §2), so this uses
     * `ExecutorchSessions.run` + `toTensor()` per output rather than `runFloat`
     * (which only returns the first).
     */
    private fun detectEt(bitmap: Bitmap, mod: Module): List<DetectedFaceBox>? {
        val (pixels, readable) = OnnxPreprocess.readablePixels(bitmap) ?: return null
        try {
            val fit = OnnxPreprocess.SquareFit.of(readable.width, readable.height, LONG_SIDE)
            val planar = OnnxPreprocess.letterboxPlanar(
                pixels, readable.width, readable.height, LONG_SIDE, fit, OnnxPreprocess.SCRFD,
            )
            // No swizzle: ET takes NCHW, which planar already is.
            val outputs = synchronized(lock) {
                ExecutorchSessions.run(
                    mod,
                    listOf(EValue.from(Tensor.fromBlob(planar, longArrayOf(1, 3, LONG_SIDE.toLong(), LONG_SIDE.toLong())))),
                )
            } ?: return null
            if (outputs.size < NUM_OUTPUTS) {
                Log.e(TAG, "scrfd ET produced ${outputs.size} outputs, want $NUM_OUTPUTS")
                return null
            }
            fun floats(index: Int, want: Int): FloatArray? {
                // HALF-tolerant: fp16 Vulkan lowers may come back binary16.
                return outputs[index].floatsAllowingHalf("scrfd ET output $index", want)
            }
            val cells = STRIDES.map { LONG_SIDE / it }
            val wants = cells.mapIndexed { level, c -> c * c * ANCHORS }
            val faces = ArrayList<Face>()
            for (level in STRIDES.indices) {
                val scores = floats(level, wants[level]) ?: return null
                val boxes = floats(level + 3, wants[level] * 4) ?: return null
                val kps = floats(level + 6, wants[level] * KEYPOINT_COUNT * 2) ?: return null
                decode(scores, boxes, kps, STRIDES[level], cells[level], faces)
            }
            suppress(faces)
            return faces.map { toSource(it, fit, readable.width, readable.height) }
        } catch (e: Throwable) {
            Log.e(TAG, "scrfd ET inference failed", e)
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
     * delegate is not linked, or the load failed — fail closed, never a throw.
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

    private data class Face(
        val score: Float,
        val bounds: FloatArray,
        val keypoints: Array<FloatArray>,
    )

    /** Decode one stride's maps into proposals. Cell-major: `(row, col, anchor)`. */
    private fun decode(
        score: FloatArray,
        bbox: FloatArray,
        keypoints: FloatArray,
        stride: Int,
        cells: Int,
        out: MutableList<Face>,
    ) {
        for (anchor in 0 until ANCHORS) {
            for (row in 0 until cells) {
                for (col in 0 until cells) {
                    val cell = row * cells + col
                    val s = score[cell * ANCHORS + anchor]
                    if (s < SCORE_THRESHOLD) continue
                    val cx = (col * stride).toFloat()
                    val cy = (row * stride).toFloat()
                    val b = cell * ANCHORS * 4 + anchor * 4
                    val x0 = cx - bbox[b] * stride
                    val y0 = cy - bbox[b + 1] * stride
                    val x1 = cx + bbox[b + 2] * stride + 1f
                    val y1 = cy + bbox[b + 3] * stride + 1f
                    val kBase = cell * ANCHORS * KEYPOINT_COUNT * 2 + anchor * KEYPOINT_COUNT * 2
                    val points = Array(KEYPOINT_COUNT) { k ->
                        floatArrayOf(
                            cx + keypoints[kBase + k * 2] * stride,
                            cy + keypoints[kBase + k * 2 + 1] * stride,
                        )
                    }
                    out += Face(s, floatArrayOf(x0, y0, x1, y1), points)
                }
            }
        }
    }

    /** Greedy stable NMS: by score, drop overlaps with IoU strictly above threshold. */
    private fun suppress(faces: MutableList<Face>) {
        faces.sortByDescending { it.score }
        val kept = ArrayList<Face>(faces.size)
        for (face in faces) {
            val overlaps = kept.any { other ->
                val iw = min(face.bounds[2], other.bounds[2]) - max(face.bounds[0], other.bounds[0])
                val ih = min(face.bounds[3], other.bounds[3]) - max(face.bounds[1], other.bounds[1])
                if (iw <= 0f || ih <= 0f) return@any false
                val intersection = iw * ih
                val union = area(face) + area(other) - intersection
                union > 0f && intersection / union > IOU_THRESHOLD
            }
            if (!overlaps) kept += face
        }
        faces.clear()
        faces.addAll(kept)
    }

    private fun area(face: Face): Float {
        val w = max(face.bounds[2] - face.bounds[0], 0f)
        val h = max(face.bounds[3] - face.bounds[1], 0f)
        return w * h
    }

    /** Map out of letterboxed space onto the source bitmap as `0..1` fractions. */
    private fun toSource(face: Face, fit: OnnxPreprocess.SquareFit, width: Int, height: Int): DetectedFaceBox {
        val limitX = (max(width, 1) - 1).toFloat()
        val limitY = (max(height, 1) - 1).toFloat()
        fun undo(value: Float, pad: Int, limit: Float): Float =
            ((value - pad) / fit.scale).coerceIn(0f, limit)
        fun nx(value: Float, pad: Int): Float = undo(value, pad, limitX) / width
        fun ny(value: Float, pad: Int): Float = undo(value, pad, limitY) / height
        val leftEye = face.keypoints[0]
        val rightEye = face.keypoints[1]
        return DetectedFaceBox(
            left = nx(face.bounds[0], fit.offsetX),
            top = ny(face.bounds[1], fit.offsetY),
            right = nx(face.bounds[2], fit.offsetX),
            bottom = ny(face.bounds[3], fit.offsetY),
            leftEyeX = nx(leftEye[0], fit.offsetX),
            leftEyeY = ny(leftEye[1], fit.offsetY),
            rightEyeX = nx(rightEye[0], fit.offsetX),
            rightEyeY = ny(rightEye[1], fit.offsetY),
            score = face.score,
        )
    }

    companion object {
        /** The ExecuTorch graph (Vulkan fp16, same 500M weights). */
        const val ET_ASSET: String = "scrfd500_vulkan_fp16.pte"
        private const val TAG = "FaceDetector"
        private const val LONG_SIDE = 640
        private const val ANCHORS = 2
        private const val KEYPOINT_COUNT = 5
        private const val SCORE_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.45f
        /** Three score maps + three box maps + three keypoint maps. */
        private const val NUM_OUTPUTS = 9
        private val STRIDES = intArrayOf(8, 16, 32)
    }
}

/**
 * MobileFaceNet face embedding, ExecuTorch Vulkan-only, fail-closed.
 *
 * Takes an aligned 112×112 crop and returns 512 floats. The result is **not**
 * L2-normalised — `FaceRecognizer` does that, as it did before.
 *
 * The only path is the Vulkan fp16 `.pte` (`mbf512_vulkan_fp16.pte`,
 * ~6.9 MB, same buffalo_s `w600k_mbf` weights) on ExecuTorch: the 112² NCHW crop
 * goes straight into `forward` and returns the single `[1,512]` embedding.
 * When the `.pte` is absent, the Vulkan delegate is not linked, or the run fails,
 * [embed] returns null — there is no LiteRT fallback.
 *
 * Not thread-safe: [embed] and [close] must not overlap.
 *
 * @param context used to read assets and to stage the `.pte`; the application context is
 * retained for the lazy ExecuTorch open.
 * @param etAssetName the `.pte` in the app's assets.
 */
class FaceEmbedder(
    context: Context,
    etAssetName: String = ET_ASSET,
) : AutoCloseable {
    private val app = context.applicationContext
    private val etAsset = etAssetName
    private val lock = Any()

    @Volatile private var etModule: Module? = null
    @Volatile private var etTried = false

    /** Whether the embedder came up. */
    val isAvailable: Boolean get() = ensureEt() != null

    /**
     * The 512-d embedding of an aligned [bitmap], or null when unavailable or on failure.
     *
     * [bitmap] should already be the canonical 112×112 crop; anything else is stretched
     * here, which for a face crop is not what the caller wanted.
     */
    fun embed(bitmap: Bitmap): FloatArray? {
        val mod = ensureEt() ?: return null
        return embedEt(bitmap, mod)
    }

    /**
     * One ExecuTorch `forward` invocation over [bitmap], or null on failure.
     *
     * Single `[1,512]` output read HALF-tolerantly: the Vulkan fp16 export may
     * come back binary16, and `runFloat` fails closed on non-FLOAT — so this uses
     * `run` + `floatsAllowingHalf` like the detector path. Returns null (never
     * throws).
     */
    private fun embedEt(bitmap: Bitmap, mod: Module): FloatArray? {
        val (pixels, readable) = OnnxPreprocess.readablePixels(bitmap) ?: return null
        try {
            val planar = OnnxPreprocess.stretchPlanar(
                pixels, readable.width, readable.height,
                SIZE, SIZE, OnnxPreprocess.FACE_EMBED,
            )
            // No swizzle: ET takes NCHW, which planar already is.
            val outputs = synchronized(lock) {
                ExecutorchSessions.run(
                    mod,
                    listOf(EValue.from(Tensor.fromBlob(planar, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())))),
                )
            } ?: return null
            val vec = outputs.firstOrNull()?.floatsAllowingHalf("mobilefacenet ET", EMBEDDING_LENGTH)
                ?: return null
            if (vec.size != EMBEDDING_LENGTH) {
                Log.e(TAG, "mobilefacenet ET produced ${vec.size} floats, want $EMBEDDING_LENGTH")
                return null
            }
            return vec
        } catch (e: Throwable) {
            Log.e(TAG, "mobilefacenet ET inference failed", e)
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
     * delegate is not linked, or the load failed — fail closed, never a throw.
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
        /** The ExecuTorch graph (Vulkan fp16, same 512-d weights). */
        const val ET_ASSET: String = "mbf512_vulkan_fp16.pte"
        /** Length of the embedding MobileFaceNet produces. */
        const val EMBEDDING_LENGTH = 512
        private const val TAG = "FaceEmbedder"
        private const val SIZE = 112
    }
}
