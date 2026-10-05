package com.vayunmathur.games.chess.data

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One move of a puzzle solution: just the coordinates and (optional) promotion. */
data class PuzzleMove(val from: Position, val to: Position, val promotion: PieceType?)

/**
 * A single Lichess puzzle: the starting [board] (the position *before* the
 * opponent's setup move) and the full [solution] as a UCI-style move list.
 * By Lichess convention `solution[0]` is played automatically by the opponent,
 * then the player must find `solution[1]`, the opponent replies `solution[2]`, etc.
 */
data class Puzzle(val rating: Int, val board: Board, val solution: List<PuzzleMove>)

/**
 * Loads and decodes the bundled `puzzles.dat` asset produced by
 * `scripts/chess/generate_puzzles.py`. The blob is read once into memory; a
 * lightweight index (record offsets + ratings) is built so puzzles can be picked
 * by difficulty and decoded one at a time, without materializing ~50k Boards.
 *
 * Binary format is documented in the generator; it is little-endian, records are
 * sorted by rating ascending, and each record is:
 *   32B board nibbles, 1B flags, 1B enPassant, 2B rating, 1B moveCount, 2B*M moves.
 */
object PuzzleRepository {
    private const val ASSET_NAME = "puzzles.dat"
    private const val HEADER_SIZE = 8
    private const val EP_NONE = 0xFF
    private const val BOARD_BYTES = 32
    private const val FLAGS_OFF = 32
    private const val EP_OFF = 33
    private const val RATING_OFF = 34
    private const val MOVE_COUNT_OFF = 36
    private const val MOVES_OFF = 37
    private const val RECORD_BASE = 37
    private const val SQUARES_PER_BYTE = 2
    private const val NIBBLE_MASK = 0x0F
    private const val NIBBLE_BITS = 4
    private const val COLOR_BIT = 3
    private const val MOVE_BITS = 16
    private const val SQUARE_MASK = 0x3F
    private const val SQUARE_SHIFT = 6
    private const val PROMO_MASK = 0x07
    private const val PROMO_SHIFT = 12
    private const val BOARD_DIM = 8
    private const val SIDE_BIT = 0x01
    private const val WHITE_KING_BIT = 0x02
    private const val WHITE_QUEEN_BIT = 0x04
    private const val BLACK_KING_BIT = 0x08
    private const val BLACK_QUEEN_BIT = 0x10
    private const val BYTE_MASK = 0xFF
    private const val U16_MASK = 0xFFFF
    private const val BYTE_BITS = 8
    private const val COUNT_OFFSET = 4
    private const val MAGIC_0 = 'C'
    private const val MAGIC_1 = 'P'
    private const val MAGIC_2 = 'Z'
    private const val MAGIC_3 = '1'
    private const val MAGIC_INDEX_0 = 0
    private const val MAGIC_INDEX_1 = 1
    private const val MAGIC_INDEX_2 = 2
    private const val MAGIC_INDEX_3 = 3
    private const val NIBBLE_EMPTY = 0
    private const val TYPE_MASK = 0x07
    private const val NIBBLE_KING = 1
    private const val NIBBLE_QUEEN = 2
    private const val NIBBLE_ROOK = 3
    private const val NIBBLE_BISHOP = 4
    private const val NIBBLE_KNIGHT = 5
    private const val NIBBLE_PAWN = 6
    private const val PROMO_QUEEN = 1
    private const val PROMO_ROOK = 2
    private const val PROMO_BISHOP = 3
    private const val PROMO_KNIGHT = 4

    private var data: ByteArray? = null
    private var offsets: IntArray = IntArray(0)
    private var ratings: IntArray = IntArray(0)

    val count: Int get() = offsets.size

    /** Reads and indexes the asset if not already loaded. Safe to call repeatedly. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (data != null) return

        val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
        require(bytes.size >= HEADER_SIZE) { "puzzles.dat too small" }
        require(
            bytes[MAGIC_INDEX_0] == MAGIC_0.code.toByte() &&
                bytes[MAGIC_INDEX_1] == MAGIC_1.code.toByte() &&
                bytes[MAGIC_INDEX_2] == MAGIC_2.code.toByte() &&
                bytes[MAGIC_INDEX_3] == MAGIC_3.code.toByte()
        ) { "puzzles.dat: bad magic" }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.getInt(COUNT_OFFSET)
        val offs = IntArray(n)
        val rts = IntArray(n)

        var off = HEADER_SIZE
        for (i in 0 until n) {
            offs[i] = off
            rts[i] = bb.getShort(off + RATING_OFF).toInt() and U16_MASK
            val moveCount = bytes[off + MOVE_COUNT_OFF].toInt() and BYTE_MASK
            off += RECORD_BASE + MOVE_BITS / BYTE_BITS * moveCount
        }

        data = bytes
        offsets = offs
        ratings = rts
    }

    /**
     * Picks a random puzzle, optionally restricted to a rating [band] (inclusive).
     * Returns null only if the repository is empty or the band matches nothing.
     */
    fun random(band: IntRange? = null): Puzzle? {
        if (count == 0) return null
        val index = if (band == null) {
            (0 until count).random()
        } else {
            val start = lowerBound(band.first)
            val end = upperBound(band.last)
            if (end <= start) return null
            (start until end).random()
        }
        return decode(index)
    }

    /** Decodes the record at [index] into a [Puzzle]. */
    fun decode(index: Int): Puzzle {
        val bytes = data ?: error("PuzzleRepository not loaded")
        return decodeRecord(bytes, offsets[index])
    }

    /** Decodes a single record starting at [off] in [bytes]. Exposed for tests. */
    internal fun decodeRecord(bytes: ByteArray, off: Int): Puzzle {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val grid = MutableList(8) { MutableList<Piece?>(8) { null } }
        for (b in 0 until BOARD_BYTES) {
            val byte = bytes[off + b].toInt() and BYTE_MASK
            placeNibble(grid, SQUARES_PER_BYTE * b, byte and NIBBLE_MASK)
            placeNibble(grid, SQUARES_PER_BYTE * b + 1, (byte shr NIBBLE_BITS) and NIBBLE_MASK)
        }

        val flags = bytes[off + FLAGS_OFF].toInt() and BYTE_MASK
        val sideToMove = if (flags and SIDE_BIT == 0) PieceColor.WHITE else PieceColor.BLACK
        val castling = CastlingRights(
            whiteKing = flags and WHITE_KING_BIT != 0,
            whiteQueen = flags and WHITE_QUEEN_BIT != 0,
            blackKing = flags and BLACK_KING_BIT != 0,
            blackQueen = flags and BLACK_QUEEN_BIT != 0
        )

        val epByte = bytes[off + EP_OFF].toInt() and BYTE_MASK
        val epSquare = if (epByte == EP_NONE) {
            null
        } else {
            Position(epByte / BOARD_DIM, epByte % BOARD_DIM)
        }
        val rating = bb.getShort(off + RATING_OFF).toInt() and U16_MASK

        val moveCount = bytes[off + MOVE_COUNT_OFF].toInt() and BYTE_MASK
        val solution = ArrayList<PuzzleMove>(moveCount)
        for (i in 0 until moveCount) {
            val m = bb.getShort(off + MOVES_OFF + MOVE_BITS / BYTE_BITS * i).toInt() and U16_MASK
            val fromSq = m and SQUARE_MASK
            val toSq = (m shr SQUARE_SHIFT) and SQUARE_MASK
            val promo = (m shr PROMO_SHIFT) and PROMO_MASK
            solution.add(
                PuzzleMove(
                    from = Position(fromSq / BOARD_DIM, fromSq % BOARD_DIM),
                    to = Position(toSq / BOARD_DIM, toSq % BOARD_DIM),
                    promotion = promoType(promo)
                )
            )
        }

        val board = Board.fromPuzzle(grid.map { it.toList() }, sideToMove, castling, epSquare)
        return Puzzle(rating, board, solution)
    }

    private fun placeNibble(grid: MutableList<MutableList<Piece?>>, square: Int, nibble: Int) {
        if (nibble == NIBBLE_EMPTY) return
        val type = when (nibble and TYPE_MASK) {
            NIBBLE_KING -> PieceType.KING
            NIBBLE_QUEEN -> PieceType.QUEEN
            NIBBLE_ROOK -> PieceType.ROOK
            NIBBLE_BISHOP -> PieceType.BISHOP
            NIBBLE_KNIGHT -> PieceType.KNIGHT
            NIBBLE_PAWN -> PieceType.PAWN
            else -> return
        }
        val color = if ((nibble shr COLOR_BIT) and 0x01 == 0) PieceColor.WHITE else PieceColor.BLACK
        grid[square / BOARD_DIM][square % BOARD_DIM] = Piece(type, color)
    }

    private fun promoType(code: Int): PieceType? = when (code) {
        PROMO_QUEEN -> PieceType.QUEEN
        PROMO_ROOK -> PieceType.ROOK
        PROMO_BISHOP -> PieceType.BISHOP
        PROMO_KNIGHT -> PieceType.KNIGHT
        else -> null
    }

    /** First index whose rating >= [value] (ratings are sorted ascending). */
    private fun lowerBound(value: Int): Int {
        var lo = 0
        var hi = ratings.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ratings[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index whose rating > [value] (ratings are sorted ascending). */
    private fun upperBound(value: Int): Int {
        var lo = 0
        var hi = ratings.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ratings[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
