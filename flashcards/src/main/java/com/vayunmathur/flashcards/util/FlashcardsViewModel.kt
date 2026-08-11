package com.vayunmathur.flashcards.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardDao
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.DeckDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Flashcards app.
 *
 * Owns the deck and card [StateFlow]s collected by the screens plus IO-dispatched
 * write helpers. [gradeCard] runs the pure [Scheduler] then upserts the result, so
 * a graded card's due date advances and it leaves the deck's due queue.
 */
class FlashcardsViewModel(
    application: Application,
    private val deckDao: DeckDao,
    private val cardDao: CardDao,
) : AndroidViewModel(application) {

    val decks: StateFlow<List<Deck>> = deckDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All cards across every deck; the deck list derives per-deck due counts from this. */
    val cards: StateFlow<List<Card>> = cardDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun cardsFor(deckId: Long): Flow<List<Card>> = cardDao.getByDeckFlow(deckId)

    /**
     * Cards currently due in [deckId]. The `now` cutoff is captured once when the
     * flow is created (a review session), so grading a card pushes its due date
     * past the cutoff and drops it from the emitted list.
     */
    fun dueCardsFor(deckId: Long): Flow<List<Card>> =
        cardDao.getDueByDeckFlow(deckId, System.currentTimeMillis())

    fun cardById(id: Long): Flow<Card?> = cardDao.getByIdFlow(id)

    fun upsertDeck(deck: Deck) {
        viewModelScope.launch(Dispatchers.IO) { deckDao.upsert(deck) }
    }

    fun deleteDeck(deck: Deck) {
        viewModelScope.launch(Dispatchers.IO) {
            cardDao.deleteByDeck(deck.id)
            deckDao.delete(deck)
        }
    }

    fun upsertCard(card: Card) {
        viewModelScope.launch(Dispatchers.IO) { cardDao.upsert(card) }
    }

    fun deleteCard(card: Card) {
        viewModelScope.launch(Dispatchers.IO) { cardDao.delete(card) }
    }

    fun gradeCard(card: Card, grade: Grade) {
        viewModelScope.launch(Dispatchers.IO) {
            cardDao.upsert(Scheduler.schedule(card, grade, System.currentTimeMillis()))
        }
    }
}

/** Factory for constructing [FlashcardsViewModel] with the DAOs. */
class FlashcardsViewModelFactory(
    private val application: Application,
    private val deckDao: DeckDao,
    private val cardDao: CardDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FlashcardsViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return FlashcardsViewModel(application, deckDao, cardDao) as T
    }
}
