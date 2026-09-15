package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * On-device human-move prediction for chess: Maia3-5M on the reduced ONNX Runtime build.
 *
 * An encoder-only transformer over 64 square tokens — width 256, 8 blocks, 8 heads — at 5.23
 * million parameters. The export (`scripts/ml/maia_to_onnx.py` from `UofTCSSLab/Maia3-5M`)
 * takes `tokens [1, 64, 97]` (square-major planes × 8 plies + clock column) plus the two elo
 * scalars and returns the 4352 move logits directly (from-to pairs plus file promotions).
 * Validated against the torch reference at correlation 1.0, argmax-exact.
 *
 * # It predicts, it does not search
 *
 * One forward pass per move and no tree at all, which is the point rather than a compromise.
 * Maia3 is trained on human games to predict what a player *of a given rating* would play, so
 * a weak setting blunders the way a beginner does. It replaces Stockfish at `Skill Level 0`,
 * which searched eight ply and then threw a move away in ways no human ever does.
 *
 * # Strength is an input
 *
 * [logits] takes `selfElo` and `oppoElo` in 0..5000 — clamped, not rejected — and they enter
 * the model as a blend of two learned embeddings. One weights file therefore covers every
 * difficulty; there is nothing to reload when the player changes it.
 *
 * # One bundled asset, ~21 MiB
 *
 * `maia3-5m.onnx` ships inside the APK, so this has an [inAssets] and no download. An asset
 * must be stored **uncompressed** (`noCompress += "onnx"` in `games/chess/build.gradle.kts`).
 *
 * # The caller owns the chess
 *
 * [logits] returns the raw 4352-entry move vector. Legal masking, temperature and sampling all
 * need a legal move list, which this library has no idea how to produce — `MaiaEngine` in
 * `:games:chess` does all three, and also does the mirror-and-swap that [logits] requires for
 * black.
 *
 * # Availability
 *
 * Construction never throws. [isAvailable] is false when the asset is absent or has an
 * operator outside the reduced build — and then [logits] returns null. The chess app gates
 * its "play against the AI" option on it rather than offering a mode that cannot move.
 *
 * When the Vulkan backend is usable and this graph is allowlisted, inference runs on the
 * Vulkan fast path and ORT is kept only as the fallback: a Vulkan failure (or short output)
 * falls back to the ORT session when it exists, preserving the null-on-failure contract.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across [logits] and [close].
 */
class MaiaHandle private constructor(private val source: String) : AutoCloseable {
    private var session: OrtSession? = null
    private var assetPath: String = GRAPH
    private var vulkanHandle: Long = 0L

    /** True if the graph came up on Vulkan or ORT and is the file this runtime was built against. */
    val isAvailable: Boolean get() = vulkanHandle != 0L || session != null

    /**
     * The [MOVES] move logits for a board, or null on failure.
     *
     * [planes] is `PLANE_COUNT * SQUARES` floats, plane-major, with square `rank * 8 + file`
     * so a1 is 0 and h8 is 63. The planes are white P, N, B, R, Q, K then black P, N, B, R, Q,
     * K. They are repeated across the 8 history plies here (the model was trained with
     * `use_uci_history=False`, so all plies hold the current board).
     *
     * **The board must already be from the mover's side.** When black is to move the caller
     * mirrors it vertically and swaps the colours, and un-mirrors the move it picks. Passing
     * an unmirrored black position produces plausible nonsense rather than an error.
     *
     * Indices 0..4095 are `from * 64 + to`; 4096..4351 are
     * `4096 + fromFile * 32 + toFile * 4 + piece` for queen, rook, bishop, knight. Promotions
     * are always rank 7 to rank 8, because the board is mirrored for black.
     */
    fun logits(planes: FloatArray, selfElo: Int, oppoElo: Int): FloatArray? {
        if (planes.size != PLANE_COUNT * SQUARES) return null
        // Square-major [64, 12] -> [64, 96] across 8 plies + zero clock column.
        val tokens = FloatArray(SQUARES * TOKEN_WIDTH)
        for (square in 0 until SQUARES) {
            for (ply in 0 until HISTORY) {
                for (plane in 0 until PLANE_COUNT) {
                    tokens[square * TOKEN_WIDTH + ply * PLANE_COUNT + plane] =
                        planes[plane * SQUARES + square]
                }
            }
        }
        val self = selfElo.coerceIn(0, MAX_ELO).toFloat()
        val oppo = oppoElo.coerceIn(0, MAX_ELO).toFloat()
        if (vulkanHandle != 0L) {
            try {
                vulkanLogits(tokens, self, oppo)?.let { return it }
            } catch (e: Throwable) {
                Log.w(TAG, "maia vulkan inference failed, falling back to ORT", e)
            }
        }
        val live = session ?: return null
        return try {
            val env = OrtEnvironment.getEnvironment()
            val tokenTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(tokens), longArrayOf(1, SQUARES.toLong(), TOKEN_WIDTH.toLong()),
            )
            val selfTensor = OnnxTensor.createTensor(
                env, floatArrayOf(self),
            )
            val oppoTensor = OnnxTensor.createTensor(
                env, floatArrayOf(oppo),
            )
            tokenTensor.useOrt {
                selfTensor.useOrt {
                    oppoTensor.useOrt {
                        live.run(
                            mapOf(
                                "tokens" to tokenTensor,
                                "self_elo" to selfTensor,
                                "oppo_elo" to oppoTensor,
                            ),
                        ).useOrt { result ->
                            val out = result.get("moves").get() as OnnxTensor
                            val vec = FloatArray(MOVES)
                            out.floatBuffer.get(vec)
                            vec
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "maia inference failed", e)
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
                Log.w(TAG, "maia vulkan close failed", e)
            }
        }
        session = null
        OnnxSessions.close("asset:$assetPath")
    }

    override fun toString(): String = "Maia3-5M from $source"

    /**
     * Best-effort Vulkan fast path; leaves [vulkanHandle] at 0 on any failure so ORT stays
     * the fallback. The model bytes are read once and shared by the preflight check
     * and the open.
     */
    private fun tryVulkan(assets: AssetManager, path: String) {
        try {
            if (!VulkanSessions.isUsable()) return
            val key = "asset:$path"
            val modelBytes = try {
                assets.open(path).use { it.readBytes() }
            } catch (e: Throwable) {
                Log.w(TAG, "cannot read $path for vulkan preflight", e)
                return
            }
            val problem = VulkanSessions.preflight(key, readModel = { modelBytes })
            if (problem != null) {
                Log.w(TAG, "vulkan preflight skipped for $path: $problem")
                return
            }
            val handle = VulkanSessions.open(key, { modelBytes }, null)
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
     * Vulkan fast path: the same inputs as the ORT call, packed flat little-endian.
     *
     * Returns null when the bridge returns no bytes or the `moves` output cannot be located,
     * so the caller falls back to ORT. Throws on packing errors, which the caller also
     * treats as fallback.
     */
    private fun vulkanLogits(tokens: FloatArray, selfElo: Float, oppoElo: Float): FloatArray? {
        val handle = vulkanHandle
        if (handle == 0L) return null
        val names = arrayOf(IN_TOKENS, IN_SELF_ELO, IN_OPPO_ELO)
        val dtypes = intArrayOf(DTYPE_F32, DTYPE_F32, DTYPE_F32)
        val shapes = longArrayOf(
            1, SQUARES.toLong(), TOKEN_WIDTH.toLong(),
            1,
            1,
        )
        val shapeOffsets = longArrayOf(0, 3, 4)
        val payload = packMaiaInputs(tokens, selfElo, oppoElo)
        val outBytes = VulkanBridge.run(handle, names, dtypes, shapes, shapeOffsets, payload)
            ?: return null
        val outNames = try {
            VulkanBridge.lastOutputNames(handle)
        } catch (e: Throwable) {
            Log.w(TAG, "maia vulkan output names unavailable", e)
            null
        }
        val outShapes = try {
            VulkanBridge.lastOutputShapes(handle)
        } catch (e: Throwable) {
            Log.w(TAG, "maia vulkan output shapes unavailable", e)
            null
        }
        return parseMovesOutput(outBytes, outNames, outShapes)
    }

    /**
     * Locate `moves` in the flat little-endian output payload.
     *
     * The bridge concatenates every graph output in [names] order; the flat shape dims split
     * evenly across names (this graph has the single `[1, 4352]` output), so each output's
     * float window is its shape chunk. Without metadata the exact single-output size is
     * required.
     */
    private fun parseMovesOutput(
        outputs: ByteArray,
        names: Array<String>?,
        shapesFlat: LongArray?,
    ): FloatArray? {
        val floats = leToFloats(outputs)
        if (names != null && names.isNotEmpty() && shapesFlat != null) {
            if (shapesFlat.size % names.size == 0) {
                val rank = shapesFlat.size / names.size
                var offset = 0
                for (i in names.indices) {
                    var numel = 1L
                    for (r in 0 until rank) numel *= shapesFlat[i * rank + r]
                    val count = numel.toInt()
                    if (names[i] == OUT_MOVES) {
                        if (count != MOVES || offset + count > floats.size) {
                            Log.w(TAG, "maia vulkan output $OUT_MOVES has $count floats, want $MOVES")
                            return null
                        }
                        return floats.copyOfRange(offset, offset + count)
                    }
                    offset += count
                }
                Log.w(TAG, "maia vulkan output $OUT_MOVES not in ${names.toList()}")
                return null
            }
            val perOutput = floats.size / names.size
            val index = names.indexOf(OUT_MOVES)
            if (index < 0 || perOutput != MOVES || (index + 1) * perOutput > floats.size) return null
            return floats.copyOfRange(index * perOutput, (index + 1) * perOutput)
        }
        if (floats.size != MOVES) {
            Log.w(TAG, "maia vulkan returned ${floats.size} floats, want $MOVES")
            return null
        }
        return floats
    }

    companion object {
        private const val TAG = "MaiaHandle"

        /** Board squares, and so the model's sequence length. One token per square. */
        const val SQUARES = 64

        /** Board planes: six piece types for each colour. */
        const val PLANE_COUNT = 12

        /** History plies (all hold the current board) plus the clock column. */
        const val HISTORY = 8
        const val TOKEN_WIDTH = 97

        /** Entries in the move vocabulary: 64x64 from-to pairs plus 8x8x4 promotions. */
        const val MOVES = 4352

        /** The highest rating the model interpolates to. Higher inputs clamp to it. */
        const val MAX_ELO = 5000

        /** The one graph. A wrong file fails at load. */
        const val GRAPH = "maia3-5m.onnx"

        private const val IN_TOKENS = "tokens"
        private const val IN_SELF_ELO = "self_elo"
        private const val IN_OPPO_ELO = "oppo_elo"
        private const val OUT_MOVES = "moves"

        /** ONNX TensorProto elem type for float32, as carried in the Vulkan `dtypes` array. */
        private const val DTYPE_F32 = 1

        /**
         * The model from the APK's assets, which is the only place it lives.
         *
         * No `inDirectory` counterpart: at ~21 MiB this is bundled, so there is no download
         * directory to look in.
         */
        fun inAssets(assets: AssetManager, path: String = GRAPH): MaiaHandle {
            val instance = MaiaHandle("the APK's $path")
            instance.assetPath = path
            instance.session = OnnxSessions.openAssetManager(assets, path)
            instance.tryVulkan(assets, path)
            if (!instance.isAvailable) Log.e(TAG, "cannot open $path")
            return instance
        }

        /** `tokens` (f32) + `self_elo` (f32 scalar) + `oppo_elo` (f32 scalar), packed in order. */
        private fun packMaiaInputs(tokens: FloatArray, selfElo: Float, oppoElo: Float): ByteArray {
            val buf = ByteBuffer.allocate(tokens.size * 4 + 4 + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (v in tokens) buf.putFloat(v)
            buf.putFloat(selfElo)
            buf.putFloat(oppoElo)
            return buf.array()
        }

        private fun leToFloats(bytes: ByteArray): FloatArray {
            val out = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }
    }
}
