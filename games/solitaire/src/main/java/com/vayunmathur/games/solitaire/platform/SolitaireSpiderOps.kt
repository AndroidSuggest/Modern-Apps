package com.vayunmathur.games.solitaire.platform

import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.SpiderState
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.data.createSpiderDeck
import com.vayunmathur.games.solitaire.data.isOneHigherThan
import kotlinx.coroutines.flow.update

// ---- Spider ----
// Moved from SolitaireViewModel.kt (FileLength split); behavior identical.

internal fun SolitaireViewModel.newSpiderGameImpl(config: GameConfig) {
    val suitCount = config.spiderSuits
    val deck = createSpiderDeck(suitCount)
    val tableauPiles = mutableListOf<TableauPile>()
    var index = 0
    for (i in 0 until 10) {
        val count = if (i < 4) 6 else 5
        val faceDown = deck.subList(index, index + count - 1)
        index += count - 1
        val faceUp = listOf(deck[index])
        index++
        tableauPiles.add(TableauPile(faceDown, faceUp))
    }
    val remaining = deck.subList(index, deck.size)
    val stockGroups = remaining.chunked(10)
    val variant = "${suitCount}SUIT"
    _uiState.value = SolitaireUiState(
        gameMode = GameMode.SPIDER,
        spider = SpiderState(
            tableauPiles = tableauPiles,
            stockGroups = stockGroups,
            suitCount = suitCount,
            variant = variant
        ),
        history = emptyList()
    )
    statsRepository.recordGamePlayed(GameMode.SPIDER, variant)
}

internal fun SolitaireViewModel.dealSpiderStockImpl() {
    val state = _uiState.value.spider ?: return
    if (state.isWon || state.stockGroups.isEmpty()) return
    if (state.tableauPiles.any { it.faceUp.isEmpty() && it.faceDown.isEmpty() }) return
    saveHistory()
    val group = state.stockGroups.first()
    val newPiles = state.tableauPiles.toMutableList()
    for (i in 0 until minOf(group.size, 10)) {
        val pile = newPiles[i]
        newPiles[i] = pile.copy(faceUp = pile.faceUp + group[i])
    }
    _uiState.update {
        it.copy(spider = state.copy(
            tableauPiles = newPiles,
            stockGroups = state.stockGroups.drop(1),
            moveCount = state.moveCount + 1
        ))
    }
    checkSpiderCompletedSuits()
}

internal fun SolitaireViewModel.spiderMoveCardsImpl(fromColumn: Int, cardIndex: Int, toColumn: Int) {
    val state = _uiState.value.spider ?: return
    if (state.isWon) return
    val fromPile = state.tableauPiles[fromColumn]
    if (cardIndex < 0 || cardIndex >= fromPile.faceUp.size) return
    val movingCards = fromPile.faceUp.subList(cardIndex, fromPile.faceUp.size)
    if (!isSpiderSequence(movingCards)) return
    val toPile = state.tableauPiles[toColumn]
    if (!canPlaceOnSpiderTableau(movingCards.first(), toPile)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[fromColumn] = autoFlip(fromPile.copy(faceUp = fromPile.faceUp.subList(0, cardIndex)))
    newPiles[toColumn] = toPile.copy(faceUp = toPile.faceUp + movingCards)
    _uiState.update {
        it.copy(spider = state.copy(
            tableauPiles = newPiles,
            moveCount = state.moveCount + 1
        ))
    }
    checkSpiderCompletedSuits()
}

internal fun SolitaireViewModel.isSpiderSequence(cards: List<Card>): Boolean =
    cards.zipWithNext().all { (a, b) -> a.suit == b.suit && a.isOneHigherThan(b) }

internal fun SolitaireViewModel.canPlaceOnSpiderTableau(card: Card, pile: TableauPile): Boolean = when {
    pile.faceUp.isEmpty() && pile.faceDown.isEmpty() -> true
    pile.faceUp.isEmpty() -> false
    else -> pile.faceUp.last().rank.value == card.rank.value + 1
}

internal fun SolitaireViewModel.checkSpiderCompletedSuits() {
    val state = _uiState.value.spider ?: return
    val newPiles = state.tableauPiles.toMutableList()
    var completed = state.completedSuits
    for (i in newPiles.indices) {
        val pile = newPiles[i]
        if (pile.faceUp.size >= 13) {
            val last13 = pile.faceUp.takeLast(13)
            if (isSpiderSequence(last13) && last13.first().rank == Rank.KING && last13.last().rank == Rank.ACE) {
                newPiles[i] = autoFlip(pile.copy(faceUp = pile.faceUp.dropLast(13)))
                completed++
            }
        }
    }
    if (completed != state.completedSuits) {
        val newState = state.copy(tableauPiles = newPiles, completedSuits = completed, isWon = completed >= 8)
        _uiState.update { it.copy(spider = newState) }
        if (newState.isWon) {
            onGameWon(GameMode.SPIDER, state.elapsedSeconds, state.moveCount, state.usedUndo)
        }
    }
}

internal fun SolitaireViewModel.handleSpiderDropImpl(sourceId: String, targetId: String) {
    if (!targetId.startsWith("tableau_") || !sourceId.startsWith("tableau_")) return
    val toCol = targetId.removePrefix("tableau_").toInt()
    val parts = sourceId.removePrefix("tableau_").split("_")
    val fromCol = parts[0].toInt()
    val cardIdx = parts.getOrNull(1)?.toInt() ?: return
    spiderMoveCards(fromCol, cardIdx, toCol)
}
