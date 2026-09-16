package com.vayunmathur.library.ml

import android.content.Context
import android.util.Log
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * On-device human-move prediction for chess: Maia3-5M, ExecuTorch Vulkan-only, fail-closed.
 *
 * An encoder-only transformer over 64 square tokens — width 256, 8 blocks, 8 heads — at 5.23
 * million parameters. It predicts what a player *of a given rating* would play, so a weak
 * setting blunders the way a beginner does. It replaces Stockfish at `Skill Level 0`, which
 * searched eight ply and then threw a move away in ways no human ever does.
 *
 * # One runtime, fail-closed
 *
 * The only path is the Vulkan int8-weight-only `.pte` (`maia3_vulkan_int8wo.pte`,
 * ~6.3 MB, torch-level cos ≥ 0.9990 vs the ship w4 rung) on ExecuTorch: one `forward` call
 * with three inputs — `tokens [1,64,97]` float32 in the caller's square-major layout,
 * `self_elo [1]` and `oppo_elo [1]` float32 — returning the 4352 move logits (from-to pairs
 * plus file promotions) directly. There is no LiteRT fallback: when the `.pte` is absent,
 * the Vulkan delegate is not linked, or the run fails, [logits] returns null and
 * [isAvailable] is false.
 *
 * # It predicts, it does not search
 *
 * One forward pass per move and no tree at all, which is the point rather than a compromise.
 *
 * # Strength is an input
 *
 * [logits] takes `selfElo` and `oppoElo` in 0..5000 — clamped, not rejected — and they enter
 * the model as a blend of two learned embeddings. One weights file therefore covers every
 * difficulty; there is nothing to reload when the player changes it.
 *
 * # One bundled asset
 *
 * The graph ships inside the APK, so this has an asset factory and no download. The asset
 * must be stored **uncompressed** (`noCompress += "pte"` in
 * `games/chess/build.gradle.kts`).
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
 * Construction never throws. [isAvailable] is false when the backend did not come up — the
 * asset is absent or has an operator the runtime cannot execute — and then [logits] returns
 * null. The chess app gates its "play against the AI" option on it rather than offering a
 * mode that cannot move.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across [logits] and [close].
 */
class MaiaHandle private constructor(private val source: String) : AutoCloseable {
    private var etModule: Module? = null
    private var etAssetPath: String = ET_GRAPH
    private val lock = Any()

    /** True if the ExecuTorch backend came up. */
    val isAvailable: Boolean get() = etModule != null

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
        // Square-major [64, 97] (8 plies + zero clock column). The ExecuTorch export takes
        // this layout directly as [1,64,97].
        val sqMajor = FloatArray(SQUARES * TOKEN_WIDTH)
        for (square in 0 until SQUARES) {
            for (ply in 0 until HISTORY) {
                for (plane in 0 until PLANE_COUNT) {
                    sqMajor[square * TOKEN_WIDTH + ply * PLANE_COUNT + plane] =
                        planes[plane * SQUARES + square]
                }
            }
        }
        val self = selfElo.coerceIn(0, MAX_ELO).toFloat()
        val oppo = oppoElo.coerceIn(0, MAX_ELO).toFloat()
        val mod = etModule ?: return null
        return etLogits(mod, sqMajor, self, oppo)
    }

    /**
     * One ExecuTorch `forward` invocation: three inputs, first float output.
     *
     * `ExecutorchSessions.runFloat` only covers the single-input case, so Maia — tokens plus
     * two elo scalars — wraps one [Tensor] per input in an [EValue] and goes through [run]
     * directly. Reads through [floatsAllowingHalf] (not a FLOAT-only check): the Vulkan fp16
     * fallback rung returns HALF. Returns null (never throws) on any failure — fail-closed,
     * no fallback backend.
     */
    private fun etLogits(mod: Module, sqMajor: FloatArray, self: Float, oppo: Float): FloatArray? {
        return try {
            val inputs = listOf(
                EValue.from(Tensor.fromBlob(sqMajor, longArrayOf(1, SQUARES.toLong(), TOKEN_WIDTH.toLong()))),
                EValue.from(Tensor.fromBlob(floatArrayOf(self), longArrayOf(1))),
                EValue.from(Tensor.fromBlob(floatArrayOf(oppo), longArrayOf(1))),
            )
            val outputs = synchronized(lock) {
                ExecutorchSessions.run(mod, inputs)
            } ?: return null
            outputs.firstOrNull()?.floatsAllowingHalf(TAG, MOVES)
        } catch (e: Throwable) {
            Log.e(TAG, "maia ET inference failed", e)
            null
        }
    }

    /** Free the backend. Idempotent. */
    override fun close() {
        synchronized(lock) {
            etModule = null
            ExecutorchSessions.close("asset:$etAssetPath")
        }
    }

    override fun toString(): String = "Maia3-5M from $source"

    companion object {
        private const val TAG = "MaiaHandle"

        /** Board squares. One token per square. */
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

        /** The ExecuTorch graph (Vulkan int8-weight-only ship candidate). A wrong file fails at load. */
        const val ET_GRAPH = "maia3_vulkan_int8wo.pte"

        /**
         * The model from the APK's assets: ExecuTorch Vulkan-only, fail-closed.
         *
         * The ExecuTorch attempt is skipped unless the Vulkan delegate is linked into the
         * runtime (`library/ml/libs/executorch-vulkan-1.4.0.aar`, XNNPACK=OFF), so on
         * devices without the Vulkan AAR this reports unavailable rather than loading a
         * fallback. Needs a [Context] (not just an `AssetManager`) because
         * [ExecutorchSessions] stages the `.pte` under `cacheDir/executorch` before
         * loading it.
         */
        fun inContext(
            context: Context,
            ptePath: String = ET_GRAPH,
        ): MaiaHandle {
            val instance = MaiaHandle("the APK's $ptePath")
            instance.etAssetPath = ptePath
            instance.etModule = openEt(context, ptePath)
            if (!instance.isAvailable) Log.e(TAG, "cannot open $ptePath")
            return instance
        }

        private fun openEt(context: Context, ptePath: String): Module? {
            val backends = ExecutorchSessions.registeredBackends()
            if (backends?.any { it.contains("Vulkan", ignoreCase = true) } != true) {
                Log.i(TAG, "Vulkan backend absent, skipping $ptePath")
                return null
            }
            return ExecutorchSessions.openAsset(context, ptePath)
        }
    }
}
