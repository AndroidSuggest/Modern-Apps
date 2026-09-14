package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
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
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across [logits] and [close].
 */
class MaiaHandle private constructor(private val source: String) : AutoCloseable {
    private var session: OrtSession? = null
    private var assetPath: String = GRAPH

    /** True if the graph came up and is the file this runtime was built against. */
    val isAvailable: Boolean get() = session != null

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
        val live = session ?: return null
        if (planes.size != PLANE_COUNT * SQUARES) return null
        return try {
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
            val env = OrtEnvironment.getEnvironment()
            val tokenTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(tokens), longArrayOf(1, SQUARES.toLong(), TOKEN_WIDTH.toLong()),
            )
            val selfTensor = OnnxTensor.createTensor(
                env, floatArrayOf(selfElo.coerceIn(0, MAX_ELO).toFloat()),
            )
            val oppoTensor = OnnxTensor.createTensor(
                env, floatArrayOf(oppoElo.coerceIn(0, MAX_ELO).toFloat()),
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

    /** Free the network. Idempotent. */
    override fun close() {
        session = null
        OnnxSessions.close("asset:$assetPath")
    }

    override fun toString(): String = "Maia3-5M from $source"

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
            if (instance.session == null) Log.e(TAG, "cannot open $path")
            return instance
        }
    }
}
