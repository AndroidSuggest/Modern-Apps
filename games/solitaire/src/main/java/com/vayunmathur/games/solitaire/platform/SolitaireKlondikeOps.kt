package com.vayunmathur.games.solitaire.platform

import androidx.compose.ui.geometry.Rect
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import com.vayunmathur.games.solitaire.data.alternatesColorWith
import com.vayunmathur.games.solitaire.data.createShuffledDeck
import com.vayunmathur.games.solitaire.data.isOneHigherThan
import com.vayunmathur.games.solitaire.data.redeals
import kotlinx.coroutines.flow.update

// ---- Klondike ----
// Moved from SolitaireViewModel.kt (FileLength split); behavior identical.

internal fun SolitaireViewModel.newKlondikeGameImpl(config: GameConfig) {
    val deck = createShuffledDeck()
    val tableauPiles = mutableListOf<TableauPile>()
    var index = 0
    for (i in 0 until 7) {
        val faceDown = deck.subList(index, index + i)
        index += i
        val faceUp = listOf(deck[index])
        index++
        tableauPiles.add(TableauPile(faceDown, faceUp))
    }
    val stock = deck.subList(index, deck.size)
    val variant = "${config.drawMode.name}_${config.klondikeDifficulty.name}"
    _uiState.value = SolitaireUiState(
        gameMode = GameMode.KLONDIKE,
        klondike = KlondikeState(
            stock = stock,
            waste = emptyList(),
            tableauPiles = tableauPiles,
            foundations = List(4) { emptyList() },
            drawMode = config.drawMode,
            difficulty = config.klondikeDifficulty,
            redealsRemaining = config.klondikeDifficulty.redeals(),
            variant = variant
        ),
        history = emptyList()
    )
    statsRepository.recordGamePlayed(GameMode.KLONDIKE, variant)
}

internal fun SolitaireViewModel.drawFromStockImpl() {
    val state = _uiState.value.klondike ?: return
    if (state.isWon) return
    if (state.stock.isEmpty() && state.waste.isEmpty()) return
    if (state.stock.isEmpty()) {
        // Recycle the waste into the stock. Redeals are limited by difficulty
        // (Hard = 0, Regular = 2, Relaxed = unlimited).
        if (state.redealsRemaining <= 0) return
        saveHistory()
        _uiState.update {
            it.copy(klondike = state.copy(
                stock = state.waste.reversed(),
                waste = emptyList(),
                redealsRemaining = if (state.redealsRemaining == Int.MAX_VALUE) Int.MAX_VALUE else state.redealsRemaining - 1,
                moveCount = state.moveCount + 1
            ))
        }
    } else {
        saveHistory()
    val drawCount = if (state.drawMode == DrawMode.DRAW_THREE) 3 else 1
        val drawn = state.stock.takeLast(drawCount).reversed()
        _uiState.update {
            it.copy(klondike = state.copy(
                stock = state.stock.dropLast(drawCount),
                waste = state.waste + drawn,
                moveCount = state.moveCount + 1
            ))
        }
    }
}

internal fun SolitaireViewModel.klondikeMoveWasteToTableauImpl(columnIndex: Int) {
    val state = _uiState.value.klondike ?: return
    if (state.isWon || state.waste.isEmpty()) return
    val card = state.waste.last()
    val pile = state.tableauPiles[columnIndex]
    if (!canPlaceOnKlondikeTableau(card, pile)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[columnIndex] = pile.copy(faceUp = pile.faceUp + card)
    _uiState.update {
        it.copy(klondike = state.copy(
            waste = state.waste.dropLast(1),
            tableauPiles = newPiles,
            moveCount = state.moveCount + 1
        ))
    }
    checkKlondikeWin()
}

internal fun SolitaireViewModel.klondikeMoveWasteToFoundationImpl(foundationIndex: Int) {
    val state = _uiState.value.klondike ?: return
    if (state.isWon || state.waste.isEmpty()) return
    val card = state.waste.last()
    if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
    saveHistory()
    val newFoundations = state.foundations.toMutableList()
    newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
    _uiState.update {
        it.copy(klondike = state.copy(
            waste = state.waste.dropLast(1),
            foundations = newFoundations,
            moveCount = state.moveCount + 1
        ))
    }
    checkKlondikeWin()
}

internal fun SolitaireViewModel.klondikeMoveTableauToFoundationImpl(fromColumn: Int, foundationIndex: Int) {
    val state = _uiState.value.klondike ?: return
    if (state.isWon) return
    val pile = state.tableauPiles[fromColumn]
    if (pile.faceUp.isEmpty()) return
    val card = pile.faceUp.last()
    if (!canPlaceOnFoundation(card, state.foundations[foundationIndex], foundationIndex)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    val newFaceUp = pile.faceUp.dropLast(1)
    newPiles[fromColumn] = autoFlip(pile.copy(faceUp = newFaceUp))
    val newFoundations = state.foundations.toMutableList()
    newFoundations[foundationIndex] = newFoundations[foundationIndex] + card
    _uiState.update {
        it.copy(klondike = state.copy(
            tableauPiles = newPiles,
            foundations = newFoundations,
            moveCount = state.moveCount + 1
        ))
    }
    checkKlondikeWin()
}

internal fun SolitaireViewModel.klondikeMoveTableauToTableauImpl(fromColumn: Int, cardIndex: Int, toColumn: Int) {
    val state = _uiState.value.klondike ?: return
    if (state.isWon) return
    val fromPile = state.tableauPiles[fromColumn]
    if (cardIndex < 0 || cardIndex >= fromPile.faceUp.size) return
    val movingCards = fromPile.faceUp.subList(cardIndex, fromPile.faceUp.size)
    val topCard = movingCards.first()
    val toPile = state.tableauPiles[toColumn]
    if (!canPlaceOnKlondikeTableau(topCard, toPile)) return
    saveHistory()
    val newPiles = state.tableauPiles.toMutableList()
    newPiles[fromColumn] = autoFlip(fromPile.copy(faceUp = fromPile.faceUp.subList(0, cardIndex)))
    newPiles[toColumn] = toPile.copy(faceUp = toPile.faceUp + movingCards)
    _uiState.update {
        it.copy(klondike = state.copy(
            tableauPiles = newPiles,
            moveCount = state.moveCount + 1
        ))
    }
}

internal fun SolitaireViewModel.klondikeAutoCompleteImpl() {
    val state = _uiState.value.klondike ?: return
    if (state.isWon) return
    if (state.tableauPiles.any { it.faceDown.isNotEmpty() }) return
    saveHistory()
    var current = state
    var madeProgress = true
    while (madeProgress) {
        madeProgress = false
        // Move as many waste/tableau cards to foundations as possible
        var moved = true
        while (moved) {
            moved = false
            if (current.waste.isNotEmpty()) {
                val card = current.waste.last()
                for (fi in current.foundations.indices) {
                    if (canPlaceOnFoundation(card, current.foundations[fi], fi)) {
                        val newFoundations = current.foundations.toMutableList()
                        newFoundations[fi] = newFoundations[fi] + card
                        current = current.copy(
                            waste = current.waste.dropLast(1),
                            foundations = newFoundations,
                            moveCount = current.moveCount + 1
                        )
                        moved = true
                        madeProgress = true
                        break
                    }
                }
                if (moved) continue
            }
            for (i in current.tableauPiles.indices) {
                val pile = current.tableauPiles[i]
                if (pile.faceUp.isNotEmpty()) {
                    val card = pile.faceUp.last()
                    for (fi in current.foundations.indices) {
                        if (canPlaceOnFoundation(card, current.foundations[fi], fi)) {
                            val newFoundations = current.foundations.toMutableList()
                            newFoundations[fi] = newFoundations[fi] + card
                            val newPiles = current.tableauPiles.toMutableList()
                            newPiles[i] = autoFlip(pile.copy(faceUp = pile.faceUp.dropLast(1)))
                            current = current.copy(
                                tableauPiles = newPiles,
                                foundations = newFoundations,
                                moveCount = current.moveCount + 1
                            )
                            moved = true
                            madeProgress = true
                            break
                        }
                    }
                    if (moved) break
                }
            }
        }
        // Draw one stock card and try again
        if (current.stock.isNotEmpty()) {
            current = current.copy(
                stock = current.stock.dropLast(1),
                waste = current.waste + current.stock.last()
            )
            madeProgress = true
        }
    }
    _uiState.update { it.copy(klondike = current) }
    checkKlondikeWin()
}

internal fun SolitaireViewModel.canPlaceOnKlondikeTableau(card: Card, pile: TableauPile): Boolean = when {
    pile.faceUp.isEmpty() && pile.faceDown.isEmpty() -> card.rank == Rank.KING
    pile.faceUp.isEmpty() -> false
    else -> pile.faceUp.last().isOneHigherThan(card) && card.alternatesColorWith(pile.faceUp.last())
}

internal fun SolitaireViewModel.canPlaceOnFoundation(card: Card, foundation: List<Card>, foundationIndex: Int): Boolean =
    if (foundation.isEmpty()) card.rank == Rank.ACE && card.suit == Suit.entries[foundationIndex]
    else card.suit == foundation.last().suit && card.isOneHigherThan(foundation.last())

internal fun SolitaireViewModel.autoFlip(pile: TableauPile): TableauPile =
    if (pile.faceUp.isEmpty() && pile.faceDown.isNotEmpty())
        pile.copy(faceDown = pile.faceDown.dropLast(1), faceUp = listOf(pile.faceDown.last()))
    else pile

internal fun SolitaireViewModel.checkKlondikeWin() {
    val state = _uiState.value.klondike ?: return
    if (state.foundations.all { it.size == 13 }) {
        _uiState.update { it.copy(klondike = state.copy(isWon = true)) }
        onGameWon(GameMode.KLONDIKE, state.elapsedSeconds, state.moveCount, state.usedUndo)
    }
}

internal fun SolitaireViewModel.handleKlondikeDropImpl(sourceId: String, targetId: String) {
    when {
        targetId.startsWith("foundation_") -> {
            val fi = targetId.removePrefix("foundation_").toInt()
            if (isSingleCardDrag(sourceId)) {
                when {
                    sourceId == "waste" -> klondikeMoveWasteToFoundation(fi)
                    sourceId.startsWith("tableau_") -> {
                        val col = sourceId.removePrefix("tableau_").substringBefore("_").toInt()
                        klondikeMoveTableauToFoundation(col, fi)
                    }
                }
            }
        }
        targetId.startsWith("tableau_") -> {
            val toCol = targetId.removePrefix("tableau_").toInt()
            when {
                sourceId == "waste" -> klondikeMoveWasteToTableau(toCol)
                sourceId.startsWith("tableau_") -> {
                    val parts = sourceId.removePrefix("tableau_").split("_")
                    val fromCol = parts[0].toInt()
                    val cardIdx = parts.getOrNull(1)?.toInt() ?: return
                    klondikeMoveTableauToTableau(fromCol, cardIdx, toCol)
                }
            }
        }
    }
}

internal fun SolitaireViewModel.klondikeAutoMoveImpl(sourceId: String) {
    val state = _uiState.value.klondike ?: return
    if (state.isWon) return
    val cards = draggedCards(sourceId)
    if (cards.isEmpty()) return
    val topCard = cards.first()

    val fromCol = if (sourceId.startsWith("tableau_"))
        sourceId.removePrefix("tableau_").substringBefore("_").toIntOrNull() else null
    val fromIdx = if (sourceId.startsWith("tableau_"))
        sourceId.removePrefix("tableau_").split("_").getOrNull(1)?.toIntOrNull() ?: 0 else 0

    // 1) Foundation — only a single top card can go up, and it is the preferred move.
    if (cards.size == 1) {
        for (fi in state.foundations.indices) {
            if (canPlaceOnFoundation(topCard, state.foundations[fi], fi)) {
                when {
                    sourceId == "waste" -> klondikeMoveWasteToFoundation(fi)
                    fromCol != null -> klondikeMoveTableauToFoundation(fromCol, fi)
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
        if (!canPlaceOnKlondikeTableau(topCard, toPile)) continue
        // Skip shuffling a lone King between empty columns — it accomplishes nothing.
        val toEmpty = toPile.faceUp.isEmpty() && toPile.faceDown.isEmpty()
        if (toEmpty && fromCol != null && fromIdx == 0 && state.tableauPiles[fromCol].faceDown.isEmpty()) continue
        when {
            sourceId == "waste" -> klondikeMoveWasteToTableau(toCol)
            fromCol != null -> klondikeMoveTableauToTableau(fromCol, fromIdx, toCol)
            else -> return
        }
        return
    }
}

internal fun overlapArea(a: Rect, b: Rect): Float {
    val left = maxOf(a.left, b.left)
    val right = minOf(a.right, b.right)
    val top = maxOf(a.top, b.top)
    val bottom = minOf(a.bottom, b.bottom)
    if (left >= right || top >= bottom) return 0f
    return (right - left) * (bottom - top)
}
