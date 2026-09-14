package com.vayunmathur.games.solitaire.platform

import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.alternatesColorWith
import com.vayunmathur.games.solitaire.data.createShuffledDeck
import com.vayunmathur.games.solitaire.data.isOneHigherThan
import kotlinx.coroutines.flow.update

// ---- FreeCell ----
// Moved from SolitaireViewModel.kt (FileLength split); behavior identical.

internal fun SolitaireViewModel.newFreeCellGameImpl() {
    val deck = createShuffledDeck()
    val piles = List(8) { mutableListOf<Card>() }
    for (i in deck.indices) {
        piles[i % 8].add(deck[i])
    }
    _uiState.value = SolitaireUiState(
        gameMode = GameMode.FREECELL,
        freeCell = FreeCellState(
            tableauPiles = piles.map { it.toList() },
            freeCells = List(4) { null },
            foundations = List(4) { emptyList() },
            variant = "STANDARD"
        ),
        history = emptyList()
    )
    statsRepository.recordGamePlayed(GameMode.FREECELL, "STANDARD")
}

internal fun SolitaireViewModel.freeCellMoveToFreeCellImpl(fromColumn: Int, cellIndex: Int) {
    val state = _uiState.value.freeCell ?: return
    if (state.isWon) return
    val pile = state.tableauPiles[fromColumn]
    if (pile.isEmpty()) return
    if (state.freeCells[cellIndex] != null) return
    saveHistory()
    val card = pile.last()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[fromColumn] = pile.dropLast(1)
    val newCells = state.freeCells.toMutableList()
    newCells[cellIndex] = card
    _uiState.update {
        it.copy(freeCell = state.copy(
            tableauPiles = newPiles,
            freeCells = newCells,
            moveCount = state.moveCount + 1
        ))
    }
}

internal fun SolitaireViewModel.freeCellMoveFromFreeCellImpl(cellIndex: Int, toColumn: Int) {
    val state = _uiState.value.freeCell ?: return
    if (state.isWon) return
    val card = state.freeCells[cellIndex] ?: return
    val pile = state.tableauPiles[toColumn]
    if (!canPlaceOnFreeCellTableau(card, pile)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[toColumn] = pile + card
    val newCells = state.freeCells.toMutableList()
    newCells[cellIndex] = null
    _uiState.update {
        it.copy(freeCell = state.copy(
            tableauPiles = newPiles,
            freeCells = newCells,
            moveCount = state.moveCount + 1
        ))
    }
}

internal fun SolitaireViewModel.freeCellMoveFreeCellToFoundationImpl(cellIndex: Int, foundationIndex: Int) {
    val state = _uiState.value.freeCell ?: return
    if (state.isWon) return
    val card = state.freeCells[cellIndex] ?: return
    if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
    saveHistory()
    val newCells = state.freeCells.toMutableList()
    newCells[cellIndex] = null
    val newFoundations = state.foundations.toMutableList()
    newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
    _uiState.update {
        it.copy(freeCell = state.copy(
            freeCells = newCells,
            foundations = newFoundations,
            moveCount = state.moveCount + 1
        ))
    }
    checkFreeCellWin()
}

internal fun SolitaireViewModel.freeCellMoveTableauToFoundationImpl(fromColumn: Int, foundationIndex: Int) {
    val state = _uiState.value.freeCell ?: return
    if (state.isWon) return
    val pile = state.tableauPiles[fromColumn]
    if (pile.isEmpty()) return
    val card = pile.last()
    if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[fromColumn] = pile.dropLast(1)
    val newFoundations = state.foundations.toMutableList()
    newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
    _uiState.update {
        it.copy(freeCell = state.copy(
            tableauPiles = newPiles,
            foundations = newFoundations,
            moveCount = state.moveCount + 1
        ))
    }
    checkFreeCellWin()
}

internal fun SolitaireViewModel.freeCellMoveTableauToTableauImpl(fromColumn: Int, cardIndex: Int, toColumn: Int) {
    val state = _uiState.value.freeCell ?: return
    if (state.isWon) return
    val fromPile = state.tableauPiles[fromColumn]
    if (cardIndex < 0 || cardIndex >= fromPile.size) return
    val movingCards = fromPile.subList(cardIndex, fromPile.size)
    if (!isFreeCellSequence(movingCards)) return
    val toPile = state.tableauPiles[toColumn]
    if (movingCards.size > 1) {
        val emptyFreeCells = state.freeCells.count { it == null }
        val emptyColumns = state.tableauPiles.indices.count { it != fromColumn && it != toColumn && state.tableauPiles[it].isEmpty() }
        val maxMove = (1 + emptyFreeCells) * (1 shl emptyColumns)
        if (movingCards.size > maxMove) return
    }
    if (!canPlaceOnFreeCellTableau(movingCards.first(), toPile)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[fromColumn] = fromPile.subList(0, cardIndex)
    newPiles[toColumn] = toPile + movingCards
    _uiState.update {
        it.copy(freeCell = state.copy(
            tableauPiles = newPiles,
            moveCount = state.moveCount + 1
        ))
    }
}

internal fun SolitaireViewModel.isFreeCellSequence(cards: List<Card>): Boolean =
    cards.zipWithNext().all { (a, b) -> a.alternatesColorWith(b) && a.isOneHigherThan(b) }

internal fun SolitaireViewModel.canPlaceOnFreeCellTableau(card: Card, pile: List<Card>): Boolean =
    pile.isEmpty() || (card.alternatesColorWith(pile.last()) && pile.last().isOneHigherThan(card))

internal fun SolitaireViewModel.checkFreeCellWin() {
    val state = _uiState.value.freeCell ?: return
    if (state.foundations.all { it.size == 13 }) {
        _uiState.update { it.copy(freeCell = state.copy(isWon = true)) }
        onGameWon(GameMode.FREECELL, state.elapsedSeconds, state.moveCount, state.usedUndo)
    }
}

internal fun SolitaireViewModel.handleFreeCellDropImpl(sourceId: String, targetId: String) {
    when {
        targetId.startsWith("freecell_") -> {
            val ci = targetId.removePrefix("freecell_").toInt()
            if (sourceId.startsWith("tableau_")) {
                val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                freeCellMoveToFreeCell(col, ci)
            }
        }
        targetId.startsWith("foundation_") -> {
            val fi = targetId.removePrefix("foundation_").toInt()
            if (isSingleCardDrag(sourceId)) {
                when {
                    sourceId.startsWith("freecell_") -> {
                        val ci = sourceId.removePrefix("freecell_").toInt()
                        freeCellMoveFreeCellToFoundation(ci, fi)
                    }
                    sourceId.startsWith("tableau_") -> {
                        val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                        freeCellMoveTableauToFoundation(col, fi)
                    }
                }
            }
        }
        targetId.startsWith("tableau_") -> {
            val toCol = targetId.removePrefix("tableau_").toInt()
            when {
                sourceId.startsWith("freecell_") -> {
                    val ci = sourceId.removePrefix("freecell_").toInt()
                    freeCellMoveFromFreeCell(ci, toCol)
                }
                sourceId.startsWith("tableau_") -> {
                    val parts = sourceId.removePrefix("tableau_").split("_")
                    val fromCol = parts[0].toInt()
                    val cardIdx = parts.getOrNull(1)?.toInt() ?: return
                    freeCellMoveTableauToTableau(fromCol, cardIdx, toCol)
                }
            }
        }
    }
}

internal fun SolitaireViewModel.freeCellAutoMoveImpl(sourceId: String) {
    val state = _uiState.value.freeCell ?: return
    if (state.isWon) return
    val cards = draggedCards(sourceId)
    if (cards.isEmpty()) return
    val topCard = cards.first()
    val single = cards.size == 1

    val fromCol = if (sourceId.startsWith("tableau_"))
        sourceId.removePrefix("tableau_").substringBefore("_").toIntOrNull() else null
    val fromIdx = if (sourceId.startsWith("tableau_"))
        sourceId.removePrefix("tableau_").split("_").getOrNull(1)?.toIntOrNull() ?: 0 else 0
    val fromCell = if (sourceId.startsWith("freecell_"))
        sourceId.removePrefix("freecell_").toIntOrNull() else null

    // 1) Foundation (single card only), the preferred move.
    if (single) {
        for (fi in state.foundations.indices) {
            if (canPlaceOnFoundation(topCard, state.foundations[fi], fi)) {
                when {
                    fromCell != null -> freeCellMoveFreeCellToFoundation(fromCell, fi)
                    fromCol != null -> freeCellMoveTableauToFoundation(fromCol, fi)
                    else -> return
                }
                return
            }
        }
    }

    // 2) Tableau — first column that legally accepts the run.
    for (toCol in state.tableauPiles.indices) {
        if (toCol == fromCol) continue
        val toPile = state.tableauPiles[toCol]
        if (!canPlaceOnFreeCellTableau(topCard, toPile)) continue
        // Skip moving a lone card between empty columns — pointless churn.
        if (toPile.isEmpty() && fromCol != null && single && state.tableauPiles[fromCol].size == 1) continue
        when {
            fromCell != null -> freeCellMoveFromFreeCell(fromCell, toCol)
            fromCol != null -> freeCellMoveTableauToTableau(fromCol, fromIdx, toCol)
            else -> return
        }
        return
    }

    // 3) Empty free cell — last resort for a single card off a tableau column.
    if (single && fromCol != null) {
        val emptyCell = state.freeCells.indexOfFirst { it == null }
        if (emptyCell >= 0) freeCellMoveToFreeCell(fromCol, emptyCell)
    }
}
