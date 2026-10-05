package com.vayunmathur.games.chess.util

import com.vayunmathur.games.chess.data.Board
import com.vayunmathur.games.chess.data.LearnLevel
import com.vayunmathur.games.chess.data.Piece
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.data.opposite
import com.vayunmathur.games.chess.data.square

/**
 * Goal evaluation and scoring for Learn lessons.
 *
 * Kept out of [LearnViewModel] so the ViewModel stays under detekt's
 * TooManyFunctions cap. Pure functions: no ViewModel state.
 */

// Lichess-style scoring (ui/learn/src/score.ts): apple/capture/scenario points,
// completion bonus by move count, and piece values for value levels.
internal const val SCORE_APPLE = 50
internal const val SCORE_CAPTURE = 50
internal const val SCORE_SCENARIO = 50
internal const val BONUS_THREE_STARS = 500
internal const val BONUS_TWO_STARS = 300
internal const val BONUS_ONE_STAR = 100
internal const val VALUE_QUEEN = 90
internal const val VALUE_ROOK = 50
internal const val VALUE_BISHOP = 30
internal const val VALUE_KNIGHT = 30
internal const val VALUE_PAWN = 10
internal const val MAX_STARS = 3
internal const val STAR_SLACK_DIVISOR = 4
internal const val WHITE_PROMOTION_ROW = 0
internal const val BLACK_PROMOTION_ROW = 7

internal fun isSuccess(
    level: LearnLevel,
    board: Board,
    apples: Set<Position>,
    player: PieceColor,
): Boolean {
    val opponent = player.opposite
    return when (level.goalType) {
        "info" -> true
        "apples" -> apples.isEmpty()
        "captureAll" -> countColor(board, opponent) == 0
        "protection" -> true // survived one move without a detectCapture failure
        "check" -> board.isKingInCheck(opponent)
        "checkIn" -> board.isKingInCheck(opponent)
        "escapeCheck" -> !board.isKingInCheck(player)
        "mate" -> board.isCheckmate(opponent)
        "castle" -> board.lastMove?.isCastling == true
        else -> apples.isEmpty()
    }
}

/** The opponent's punishing capture if this move hangs a piece, else null. */
internal fun detectCaptureMove(
    level: LearnLevel,
    board: Board,
    player: PieceColor,
): Pair<Position, Position>? {
    if (level.goalType == "info") return null
    val opp = player.opposite
    return when (level.detectCapture) {
        "all" -> captures(board, opp).firstOrNull()
        "unprotected" -> captures(board, opp).firstOrNull { (from, to) ->
            val after = board.movePiece(from, to)
            positionsOf(after, player).none { after.isValidMove(it, to) }
        }
        else -> null
    }
}

/** Non-capture failure conditions: path constraints and single-move goals not met. */
internal fun isFailingMove(
    level: LearnLevel,
    board: Board,
    player: PieceColor,
    moves: Int,
): Boolean {
    if (violatesPathConstraints(level, board)) return true
    return missesSingleMoveGoal(level, board, player, moves)
}

private fun violatesPathConstraints(level: LearnLevel, board: Board): Boolean {
    // Pawn-stage path constraints.
    if (level.failIfWhitePawnOn.isNotEmpty()) {
        val bad = level.failIfWhitePawnOn.map { square(it) }
        val strayed = bad.any { sq ->
            board.pieces[sq.row][sq.col]?.let {
                it.type == PieceType.PAWN && it.color == PieceColor.WHITE
            } == true
        }
        if (strayed) return true
    }
    if (level.failIfPieceOffPath.isNotEmpty()) {
        val allowed = level.failIfPieceOffPath.map { square(it) }.toSet()
        val strayed = board.pieces.flatMapIndexed { r, row ->
            row.mapIndexedNotNull { c, p -> if (p != null) Position(r, c) else null }
        }.any { it !in allowed }
        if (strayed) return true
    }
    return false
}

private fun missesSingleMoveGoal(
    level: LearnLevel,
    board: Board,
    player: PieceColor,
    moves: Int,
): Boolean {
    val opponent = player.opposite
    // Single-move goals that weren't achieved this move.
    return when (level.goalType) {
        "check" -> !board.isKingInCheck(opponent)
        "checkIn" -> !board.isKingInCheck(opponent) && moves >= (level.n ?: level.nbMoves)
        "escapeCheck" -> board.isKingInCheck(player)
        "mate" -> !board.isCheckmate(opponent)
        else -> false
    }
}

internal fun isLastRank(to: Position): Boolean =
    to.row == WHITE_PROMOTION_ROW || to.row == BLACK_PROMOTION_ROW

internal fun countColor(board: Board, color: PieceColor): Int =
    board.pieces.sumOf { row -> row.count { it?.color == color } }

internal fun positionsOf(board: Board, color: PieceColor): List<Position> =
    board.pieces.flatMapIndexed { r, row ->
        row.mapIndexedNotNull { c, p -> if (p?.color == color) Position(r, c) else null }
    }

/** Legal captures available to [attacker] against the other side. */
internal fun captures(board: Board, attacker: PieceColor): List<Pair<Position, Position>> {
    val targets = positionsOf(board, attacker.opposite)
    return positionsOf(board, attacker).flatMap { from ->
        targets.filter { to -> board.isValidMove(from, to) }.map { from to it }
    }
}

internal fun promotionFor(piece: Piece, to: Position): PieceType? {
    if (piece.type != PieceType.PAWN) return null
    if (to.row != WHITE_PROMOTION_ROW && to.row != BLACK_PROMOTION_ROW) return null
    return PieceType.QUEEN
}

internal fun fenSide(fen: String): PieceColor =
    if (fen.split(" ").getOrNull(1) == "b") PieceColor.BLACK else PieceColor.WHITE

internal fun starsFor(level: LearnLevel, moves: Int): Int {
    val par = level.nbMoves
    return when {
        moves <= par -> MAX_STARS
        moves <= par + maxOf(1, par / STAR_SLACK_DIVISOR) -> 2
        else -> 1
    }
}

internal fun levelBonus(stars: Int): Int = when (stars) {
    MAX_STARS -> BONUS_THREE_STARS
    2 -> BONUS_TWO_STARS
    else -> BONUS_ONE_STAR
}

internal fun pieceValue(type: PieceType): Int = when (type) {
    PieceType.QUEEN -> VALUE_QUEEN
    PieceType.ROOK -> VALUE_ROOK
    PieceType.BISHOP -> VALUE_BISHOP
    PieceType.KNIGHT -> VALUE_KNIGHT
    PieceType.PAWN -> VALUE_PAWN
    PieceType.KING -> 0
}
