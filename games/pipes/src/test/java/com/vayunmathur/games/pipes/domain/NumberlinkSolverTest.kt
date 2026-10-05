package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberlinkSolverTest {

    private fun rectangle(rows: Int, cols: Int) =
        buildSet { for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c)) }

    private fun pairs(vararg ends: Pair<CellPos, CellPos>) =
        ends.mapIndexed { i, (a, b) -> EndpointPair(i, listOf(a, b)) }

    private fun verdict(rows: Int, cols: Int, vararg ends: Pair<CellPos, CellPos>) =
        NumberlinkSolver.classify(rectangle(rows, cols), pairs(*ends))

    @Test
    fun oneFlowDownEachColumnOfATwoByTwoIsUnique() {
        // A-B over A-B: each colour can only go straight down its own column.
        assertEquals(
            NumberlinkSolver.Verdict.UNIQUE,
            verdict(
                2, 2,
                CellPos(0, 0) to CellPos(1, 0),
                CellPos(0, 1) to CellPos(1, 1),
            ),
        )
    }

    @Test
    fun aSingleFlowAcrossAnOpenBoardHasManySolutions() {
        // Opposite corners of a 4x4 with nothing else on the board: every staircase works.
        assertEquals(
            NumberlinkSolver.Verdict.MULTIPLE,
            verdict(4, 4, CellPos(0, 0) to CellPos(3, 3)),
        )
    }

    @Test
    fun aFlowThatCannotReachItsPartnerIsUnsolvable() {
        // The row-1 pair walls off row 2 from row 0, so the vertical pair cannot get through.
        assertEquals(
            NumberlinkSolver.Verdict.NONE,
            verdict(
                3, 2,
                CellPos(1, 0) to CellPos(1, 1),
                CellPos(0, 0) to CellPos(2, 0),
            ),
        )
    }

    @Test
    fun anAdjacentPairLeavingTheBoardEmptyCountsAsAnExtraSolution() {
        // Bug #552 in miniature: the second pair's endpoints touch, so the board can be
        // "connected" with a two-cell line that strands (2,0) and (2,1). The intended full-board
        // solution exists too, which is what makes such a level look unwinnable.
        assertEquals(
            NumberlinkSolver.Verdict.MULTIPLE,
            verdict(
                3, 2,
                CellPos(0, 0) to CellPos(0, 1),
                CellPos(1, 0) to CellPos(1, 1),
            ),
        )
    }

    @Test
    fun budgetTooSmallToProveAnythingIsUndecided() {
        assertEquals(
            NumberlinkSolver.Verdict.UNDECIDED,
            NumberlinkSolver.classify(
                rectangle(6, 6),
                pairs(CellPos(0, 0) to CellPos(5, 5)),
                stateBudget = 0,
            ),
        )
    }

    /**
     * The solver walks cells diagonally and memoises on the frontier, which is easy to get subtly
     * wrong in a way that still looks plausible on square boards. These count the solutions by
     * enumerating every edge subset instead, which is only affordable on a handful of cells.
     */
    @Test
    fun agreesWithExhaustiveEnumerationOnSmallBoards() {
        val boards = listOf(
            // The worked example in the header of scripts/pipes/numberlink_solver.cpp.
            Triple(
                3 to 4,
                pairs(
                    CellPos(0, 0) to CellPos(1, 2),
                    CellPos(0, 3) to CellPos(2, 1),
                    CellPos(1, 1) to CellPos(2, 0),
                ),
                NumberlinkSolver.Verdict.UNIQUE,
            ),
            Triple(2 to 2, pairs(CellPos(0, 0) to CellPos(1, 1)), NumberlinkSolver.Verdict.MULTIPLE),
            Triple(2 to 3, pairs(CellPos(0, 0) to CellPos(1, 2)), NumberlinkSolver.Verdict.MULTIPLE),
            Triple(
                3 to 3,
                pairs(
                    CellPos(0, 0) to CellPos(2, 0),
                    CellPos(0, 2) to CellPos(2, 2),
                ),
                NumberlinkSolver.Verdict.MULTIPLE,
            ),
            Triple(1 to 4, pairs(CellPos(0, 0) to CellPos(0, 3)), NumberlinkSolver.Verdict.UNIQUE),
        )
        for ((dims, endpoints, expected) in boards) {
            val (rows, cols) = dims
            val label = "${rows}x$cols"
            val exhaustive = countByEnumeration(rows, cols, endpoints)
            assertEquals(
                expected,
                verdictOf(exhaustive),
                "$label: exhaustive count was $exhaustive",
            )
            assertEquals(
                expected,
                NumberlinkSolver.classify(rectangle(rows, cols), endpoints),
                label,
            )
        }
    }

    private fun verdictOf(count: Int) = when {
        count == 0 -> NumberlinkSolver.Verdict.NONE
        count == 1 -> NumberlinkSolver.Verdict.UNIQUE
        else -> NumberlinkSolver.Verdict.MULTIPLE
    }

    /** Counts legal edge subsets: degree <= 2, endpoints degree 1, blanks 0 or 2, no stray loops. */
    private fun countByEnumeration(rows: Int, cols: Int, endpoints: List<EndpointPair>): Int {
        val colorOf = HashMap<CellPos, Int>()
        endpoints.forEachIndexed { i, ep -> ep.cells.forEach { colorOf[it] = i } }
        val edges = gridEdges(rows, cols)
        var count = 0
        for (mask in 0 until (1 shl edges.size)) {
            val adjacent = adjacencyForMask(edges, mask)
            if (hasLegalDegrees(rows, cols, colorOf, adjacent) && walksAllEndpoints(endpoints, adjacent)) {
                count++
            }
        }
        return count
    }

    private fun gridEdges(rows: Int, cols: Int): List<Pair<CellPos, CellPos>> {
        val edges = mutableListOf<Pair<CellPos, CellPos>>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (c + 1 < cols) edges += CellPos(r, c) to CellPos(r, c + 1)
                if (r + 1 < rows) edges += CellPos(r, c) to CellPos(r + 1, c)
            }
        }
        return edges
    }

    private fun adjacencyForMask(
        edges: List<Pair<CellPos, CellPos>>,
        mask: Int,
    ): Map<CellPos, List<CellPos>> {
        val adjacent = HashMap<CellPos, MutableList<CellPos>>()
        for (i in edges.indices) {
            if (mask shr i and 1 == 0) continue
            val (a, b) = edges[i]
            adjacent.getOrPut(a) { mutableListOf() } += b
            adjacent.getOrPut(b) { mutableListOf() } += a
        }
        return adjacent
    }

    private fun hasLegalDegrees(
        rows: Int,
        cols: Int,
        colorOf: Map<CellPos, Int>,
        adjacent: Map<CellPos, List<CellPos>>,
    ): Boolean {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = CellPos(r, c)
                if (!isLegalDegree(cell in colorOf, adjacent[cell]?.size ?: 0)) return false
            }
        }
        return true
    }

    private fun isLegalDegree(isEndpoint: Boolean, degree: Int): Boolean {
        if (degree > MAX_PATH_DEGREE) return false
        if (isEndpoint) return degree == ENDPOINT_DEGREE
        return degree != DANGLING_DEGREE
    }

    private fun walksAllEndpoints(
        endpoints: List<EndpointPair>,
        adjacent: Map<CellPos, List<CellPos>>,
    ): Boolean {
        val walked = HashSet<CellPos>()
        for (ep in endpoints) {
            if (!walkEndpoints(ep, adjacent, walked)) return false
        }
        // Anything used but not walked is a loop of blanks, which is not a solution.
        return walked.size == adjacent.keys.size
    }

    private fun walkEndpoints(
        ep: EndpointPair,
        adjacent: Map<CellPos, List<CellPos>>,
        walked: MutableSet<CellPos>,
    ): Boolean {
        var previous: CellPos? = null
        var current = ep.cells[0]
        if (!walked.add(current)) return false
        while (true) {
            val next = adjacent[current]?.firstOrNull { it != previous } ?: break
            previous = current
            current = next
            if (!walked.add(current)) return false
        }
        return current == ep.cells[1]
    }

    private companion object {
        private const val MAX_PATH_DEGREE = 2
        private const val ENDPOINT_DEGREE = 1
        private const val DANGLING_DEGREE = 1
    }
}
