package com.vayunmathur.games.chess.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-JVM checks for the Learn coordinate helpers and lesson board building. */
class LearnDataTest {

    @Test
    fun square_parsesAlgebraicToPosition() {
        assertEquals(Position(4, 4), square("e4")) // rank 4 -> row 4, file e -> col 4
        assertEquals(Position(0, 0), square("a8")) // top-left
        assertEquals(Position(7, 7), square("h1")) // bottom-right
    }

    @Test
    fun parseUci_decodesMoveAndPromotion() {
        val (from, to, promo) = parseUci("e2e4")
        assertEquals(square("e2"), from)
        assertEquals(square("e4"), to)
        assertNull(promo)

        val (pf, pt, pp) = parseUci("e7e8q")
        assertEquals(square("e7"), pf)
        assertEquals(square("e8"), pt)
        assertEquals(PieceType.QUEEN, pp)
    }

    @Test
    fun buildBoard_placesEnemyPawnsOnApples() {
        val level = LearnLevel(
            goal = "grab the star",
            fen = "8/8/8/8/8/8/4R3/8 w - - 0 1",
            goalType = "apples",
            apples = "e7"
        )
        val board = LearnRepository.buildBoard(level)
        // White rook stays on e2.
        val rook = board.pieces[square("e2").row][square("e2").col]
        assertEquals(PieceType.ROOK, rook?.type)
        assertEquals(PieceColor.WHITE, rook?.color)
        // Apple square e7 becomes a capturable black pawn.
        val apple = board.pieces[square("e7").row][square("e7").col]
        assertEquals(PieceType.PAWN, apple?.type)
        assertEquals(PieceColor.BLACK, apple?.color)
    }

    @Test
    fun buildBoard_emptyApplesLeavesTargetSquaresEmpty() {
        val level = LearnLevel(
            goal = "walk the king",
            fen = "8/8/8/8/8/3K4/8/8 w - - 0 1",
            goalType = "apples",
            apples = "e6",
            emptyApples = true
        )
        val board = LearnRepository.buildBoard(level)
        assertNull(board.pieces[square("e6").row][square("e6").col])
        assertEquals(PieceType.KING, board.pieces[square("d3").row][square("d3").col]?.type)
    }

    @Test
    fun playerColor_derivedFromLevelColor() {
        assertEquals(PieceColor.WHITE, LearnLevel(goal = "", fen = "8/8/8/8/8/8/8/8 w - - 0 1", goalType = "info").playerColor)
        assertTrue(
            LearnLevel(goal = "", fen = "8/8/8/8/8/8/8/8 b - - 0 1", goalType = "info", color = "black")
                .playerColor == PieceColor.BLACK
        )
    }
}
