package com.vayunmathur.flashcards.util

import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.Deck

/**
 * The UI contract between [FlashcardsViewModel] plus the nav back stack and the screens.
 *
 * Screens take a state value and an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the binders in `ui` implement these interfaces. Every actions
 * method has a no-op default so a preview can use the [Noop] implementation with no VM.
 */

/** A deck plus its derived review counts, as drawn on the deck list. */
data class DeckSummary(
    val deck: Deck,
    val dueCount: Int = 0,
    val totalCount: Int = 0,
)

/** Everything the deck list draws. */
data class DeckListUiState(
    val decks: List<DeckSummary> = emptyList(),
)

interface DeckListActions {
    fun openDeck(id: Long) {}
    fun addDeck(name: String) {}
    fun deleteDeck(deck: Deck) {}
    fun startReview(deckId: Long) {}

    companion object {
        val Noop: DeckListActions = object : DeckListActions {}
    }
}

/** Everything the card list draws for a single deck. */
data class CardListUiState(
    val deckName: String = "",
    val cards: List<Card> = emptyList(),
    val dueCount: Int = 0,
)

interface CardListActions {
    fun back() {}
    fun openCard(id: Long) {}
    fun addCard() {}
    fun deleteCard(card: Card) {}
    fun study() {}

    companion object {
        val Noop: CardListActions = object : CardListActions {}
    }
}

/** Everything the card editor draws: the two plain-text sides. */
data class CardEditUiState(
    val front: String = "",
    val back: String = "",
    val isNew: Boolean = true,
)

interface CardEditActions {
    fun back() {}
    fun setFront(front: String) {}
    fun setBack(back: String) {}
    fun save() {}
    fun deleteCard() {}

    companion object {
        val Noop: CardEditActions = object : CardEditActions {}
    }
}

/** Everything a review session draws for the current card. */
data class ReviewUiState(
    val front: String = "",
    val back: String = "",
    /** Cards still due in this session, including the one shown. */
    val remaining: Int = 0,
    /** True when nothing is due, i.e. the session is complete. */
    val done: Boolean = false,
)

interface ReviewActions {
    fun back() {}
    fun grade(grade: Grade) {}

    companion object {
        val Noop: ReviewActions = object : ReviewActions {}
    }
}
