package com.vayunmathur.games.chess.data

import kotlin.math.abs

/**
 * Pseudo-legal move validators for [Board].
 *
 * Kept out of [Board] itself so the data class stays under detekt's
 * TooManyFunctions cap. All functions are internal extensions: same package,
 * same call sites, no API change.
 */

internal fun Board.isValidMoveIgnoringCheck(start: Position, end: Position): Boolean {
    val piece = pieces[start.row][start.col] ?: return false
    val targetPiece = pieces[end.row][end.col]
    if (targetPiece != null && targetPiece.color == piece.color) return false

    return when (piece.type) {
        PieceType.PAWN -> isValidPawnMove(start, end, piece.color)
        PieceType.ROOK -> isValidRookMove(start, end)
        PieceType.KNIGHT -> isValidKnightMove(start, end)
        PieceType.BISHOP -> isValidBishopMove(start, end)
        PieceType.QUEEN -> isValidRookMove(start, end) || isValidBishopMove(start, end)
        PieceType.KING -> isValidKingMove(start, end, piece)
    }
}

internal fun Board.isEnPassant(start: Position, end: Position): Boolean {
    val last = lastMove ?: return false
    val lastPiece = pieces[last.end.row][last.end.col] ?: return false
    if (lastPiece.type != PieceType.PAWN) return false
    if (abs(last.start.row - last.end.row) != PAWN_DOUBLE_STEP) return false
    val pawnRow = if (lastPiece.color == PieceColor.WHITE) WHITE_EP_ROW else BLACK_EP_ROW
    if (start.row != pawnRow || end.col != last.end.col) return false
    val step = if (lastPiece.color == PieceColor.WHITE) 1 else -1
    return end.row == last.end.row + step
}

internal fun Board.isPromotionSquare(piece: Piece, position: Position): Boolean =
    piece.type == PieceType.PAWN &&
        ((piece.color == PieceColor.WHITE && position.row == WHITE_PROMOTION_ROW) ||
            (piece.color == PieceColor.BLACK && position.row == BLACK_PROMOTION_ROW))

internal fun Board.enPassantCaptureRow(color: PieceColor, endRow: Int): Int =
    if (color == PieceColor.WHITE) endRow + 1 else endRow - 1

internal fun Board.applyMoveInternal(start: Position, end: Position): Board {
    val piece = pieces[start.row][start.col] ?: return this
    val grid = movedGrid(start, end, piece, null)
    return copy(pieces = grid.map { it.toList() }, lastMove = Move(start, end, piece))
}

internal fun Board.movedGrid(
    start: Position,
    end: Position,
    movingPiece: Piece,
    promoteTo: PieceType?,
): MutableList<MutableList<Piece?>> {
    val newPieces = pieces.map { it.toMutableList() }.toMutableList()
    if (movingPiece.type == PieceType.KING && abs(start.col - end.col) == CASTLE_FILE_DISTANCE) {
        moveRookForCastling(newPieces, start.row, end.col)
    }
    if (movingPiece.type == PieceType.PAWN && isEnPassant(start, end)) {
        newPieces[enPassantCaptureRow(movingPiece.color, end.row)][end.col] = null
    }
    val placed = movingPiece.copy(type = promoteTo ?: movingPiece.type, hasMoved = true)
    newPieces[end.row][end.col] = placed
    newPieces[start.row][start.col] = null
    return newPieces
}

internal fun Board.capturedFor(start: Position, end: Position, movingPiece: Piece): Piece? {
    if (movingPiece.type == PieceType.PAWN && isEnPassant(start, end)) {
        return pieces[enPassantCaptureRow(movingPiece.color, end.row)][end.col]
    }
    return pieces[end.row][end.col]
}

internal fun moveRookForCastling(
    newPieces: MutableList<MutableList<Piece?>>,
    row: Int,
    kingEndCol: Int,
) {
    val rookStartCol = if (kingEndCol > KING_HOME_FILE) ROOK_KINGSIDE_FILE else ROOK_QUEENSIDE_FILE
    val rookEndCol = if (kingEndCol > KING_HOME_FILE) kingEndCol - 1 else kingEndCol + 1
    newPieces[row][rookEndCol] = newPieces[row][rookStartCol]?.copy(hasMoved = true)
    newPieces[row][rookStartCol] = null
}

private fun Board.isValidPawnMove(start: Position, end: Position, color: PieceColor): Boolean {
    val direction = if (color == PieceColor.WHITE) -1 else 1
    val startRow = if (color == PieceColor.WHITE) PAWN_START_ROW_WHITE else PAWN_START_ROW_BLACK

    if (start.col == end.col) {
        if (pieces[end.row][end.col] != null) return false
        if (start.row + direction == end.row) return true
        if (isPawnDoubleStep(start, end, startRow, direction)) return true
    }
    if (abs(start.col - end.col) == 1 && start.row + direction == end.row) {
        return pieces[end.row][end.col] != null || isEnPassant(start, end)
    }
    return false
}

private fun Board.isPawnDoubleStep(
    start: Position,
    end: Position,
    startRow: Int,
    direction: Int,
): Boolean {
    if (start.row != startRow) return false
    if (start.row + PAWN_DOUBLE_STEP * direction != end.row) return false
    return pieces[start.row + direction][start.col] == null
}

private fun Board.isValidRookMove(start: Position, end: Position): Boolean =
    (start.row == end.row || start.col == end.col) && !isPathBlocked(start, end)

private fun Board.isValidKnightMove(start: Position, end: Position): Boolean {
    val rowDiff = abs(start.row - end.row)
    val colDiff = abs(start.col - end.col)
    return isKnightStep(rowDiff, colDiff)
}

internal fun isKnightStep(rowDiff: Int, colDiff: Int): Boolean =
    rowDiff == 2 && colDiff == 1 || rowDiff == 1 && colDiff == 2

private fun Board.isValidBishopMove(start: Position, end: Position): Boolean =
    abs(start.row - end.row) == abs(start.col - end.col) && !isPathBlocked(start, end)

private fun Board.isValidKingMove(start: Position, end: Position, piece: Piece): Boolean {
    val rowDiff = abs(start.row - end.row)
    val colDiff = abs(start.col - end.col)

    if (!piece.hasMoved && rowDiff == 0 && colDiff == CASTLE_FILE_DISTANCE) {
        if (canCastle(start, end, piece)) return true
    }
    return rowDiff <= 1 && colDiff <= 1
}

private fun Board.canCastle(start: Position, end: Position, piece: Piece): Boolean {
    if (isKingInCheck(piece.color)) return false
    val rookCol = if (end.col > start.col) ROOK_KINGSIDE_FILE else ROOK_QUEENSIDE_FILE
    val rook = pieces[start.row][rookCol]
    if (rook == null || rook.hasMoved || rook.type != PieceType.ROOK) return false
    return castlePathClear(start, rookCol, piece.color)
}

private fun Board.castlePathClear(start: Position, rookCol: Int, color: PieceColor): Boolean {
    val direction = if (rookCol > start.col) 1 else -1
    var current = start.col + direction
    while (current != rookCol) {
        if (pieces[start.row][current] != null) return false
        if (abs(current - start.col) <= CASTLE_FILE_DISTANCE) {
            val probe = Position(start.row, current)
            if (applyMoveInternal(start, probe).isKingInCheck(color)) return false
        }
        current += direction
    }
    return true
}

private fun Board.isPathBlocked(start: Position, end: Position): Boolean {
    val rowStep = (end.row - start.row).coerceIn(-1, 1)
    val colStep = (end.col - start.col).coerceIn(-1, 1)
    var currentRow = start.row + rowStep
    var currentCol = start.col + colStep
    while (currentRow != end.row || currentCol != end.col) {
        if (pieces[currentRow][currentCol] != null) return true
        currentRow += rowStep
        currentCol += colStep
    }
    return false
}

internal fun Board.walkLegalMoves(color: PieceColor, visit: (Position, Position) -> Boolean) {
    for (row in 0 until BOARD_SIZE) {
        for (col in 0 until BOARD_SIZE) {
            if (visitSquare(Position(row, col), color, visit)) return
        }
    }
}

private fun Board.visitSquare(
    start: Position,
    color: PieceColor,
    visit: (Position, Position) -> Boolean,
): Boolean {
    val piece = pieces[start.row][start.col] ?: return false
    if (piece.color != color) return false
    return walkFrom(start, visit)
}

private fun Board.walkFrom(start: Position, visit: (Position, Position) -> Boolean): Boolean {
    for (endRow in 0 until BOARD_SIZE) {
        for (endCol in 0 until BOARD_SIZE) {
            if (visitEnd(start, endRow, endCol, visit)) return true
        }
    }
    return false
}

private fun Board.visitEnd(
    start: Position,
    endRow: Int,
    endCol: Int,
    visit: (Position, Position) -> Boolean,
): Boolean {
    val end = Position(endRow, endCol)
    if (!isValidMove(start, end)) return false
    return !visit(start, end)
}

internal fun Board.findKing(kingColor: PieceColor): Position? {
    pieces.forEachIndexed { row, cols ->
        cols.forEachIndexed { col, piece ->
            if (piece?.type == PieceType.KING && piece.color == kingColor) {
                return Position(row, col)
            }
        }
    }
    return null
}

internal fun Board.calculateAmbiguity(start: Position, end: Position, movingPiece: Piece): String {
    if (movingPiece.type == PieceType.PAWN || movingPiece.type == PieceType.KING) return ""

    val alternatives = rivalPieces(start, movingPiece).filter { alt ->
        canAlsoReach(alt, end, movingPiece.color)
    }

    if (alternatives.isEmpty()) return ""
    val sameFile = alternatives.any { it.col == start.col }
    val sameRank = alternatives.any { it.row == start.row }
    return when {
        sameFile && sameRank -> "${getFileChar(start.col)}${getRankChar(start.row)}"
        sameFile -> "${getRankChar(start.row)}"
        else -> "${getFileChar(start.col)}"
    }
}

private fun Board.rivalPieces(start: Position, movingPiece: Piece): List<Position> {
    return pieces.flatMapIndexed { r, cols ->
        cols.mapIndexedNotNull { c, p ->
            if (isRivalPiece(p, r, c, start, movingPiece)) Position(r, c) else null
        }
    }
}

private fun isRivalPiece(
    p: Piece?,
    r: Int,
    c: Int,
    start: Position,
    movingPiece: Piece,
): Boolean {
    if (p == null) return false
    if (r == start.row && c == start.col) return false
    return p.type == movingPiece.type && p.color == movingPiece.color
}

private fun Board.canAlsoReach(alt: Position, end: Position, color: PieceColor): Boolean {
    return isValidMoveIgnoringCheck(alt, end) &&
        !applyMoveInternal(alt, end).isKingInCheck(color)
}
