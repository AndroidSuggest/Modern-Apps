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
 * method has a no-op default so a preview can use the `Noop` implementation with no VM.
 */

// ---------------------------------------------------------------------------
// Deck list
// ---------------------------------------------------------------------------

/** A deck plus its derived review counts, as drawn on the deck list. */
data class DeckSummary(
    val deck: Deck,
    val dueCount: Int = 0,
    val newCount: Int = 0,
    val totalCount: Int = 0,
    /** Fraction of cards that have graduated to the review state (0..1). */
    val mastery: Float = 0f,
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
    fun reorder(decks: List<Deck>) {}

    companion object {
        val Noop: DeckListActions = object : DeckListActions {}
    }
}

// ---------------------------------------------------------------------------
// Card list
// ---------------------------------------------------------------------------

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
    fun reorder(cards: List<Card>) {}
    fun openStats() {}
    fun share() {}

    companion object {
        val Noop: CardListActions = object : CardListActions {}
    }
}

// ---------------------------------------------------------------------------
// Card editor
// ---------------------------------------------------------------------------

/** Everything the card editor draws: the two markdown sides plus tags. */
data class CardEditUiState(
    val front: String = "",
    val back: String = "",
    val tags: String = "",
    val isNew: Boolean = true,
)

interface CardEditActions {
    fun back() {}
    fun setFront(front: String) {}
    fun setBack(back: String) {}
    fun setTags(tags: String) {}
    fun save() {}
    fun deleteCard() {}

    companion object {
        val Noop: CardEditActions = object : CardEditActions {}
    }
}

// ---------------------------------------------------------------------------
// Review session
// ---------------------------------------------------------------------------

/** Everything a review session draws for the current card. */
data class ReviewUiState(
    val front: String = "",
    val back: String = "",
    /** Cards still queued in this session, including the one shown. */
    val remaining: Int = 0,
    /** True when the queue is empty, i.e. the session is complete. */
    val done: Boolean = false,
    val newCount: Int = 0,
    val learningCount: Int = 0,
    val reviewCount: Int = 0,
    val progress: Float = 0f,
    /** Predicted next-interval label per grade button (e.g. "10m", "4d"). */
    val intervalLabels: Map<Grade, String> = emptyMap(),
    val canUndo: Boolean = false,
) {
    fun label(grade: Grade): String = intervalLabels[grade] ?: ""
}

interface ReviewActions {
    fun back() {}
    fun grade(grade: Grade) {}
    fun undo() {}

    companion object {
        val Noop: ReviewActions = object : ReviewActions {}
    }
}

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

/** A single deck choice (plus an "All decks" option with a null id) in the stats picker. */
data class DeckOption(val id: Long?, val name: String)

/** One day of review history. [epochDay] is days since the Unix epoch (local). */
data class DailyStat(val epochDay: Long, val count: Int)

data class StatsUiState(
    val deckOptions: List<DeckOption> = emptyList(),
    val selectedDeckId: Long? = null,
    /** Chronological daily review counts covering roughly the last year. */
    val daily: List<DailyStat> = emptyList(),
    val totalReviews: Int = 0,
    val retentionPct: Int = 0,
    val streakDays: Int = 0,
    val matureCards: Int = 0,
    val totalCards: Int = 0,
)

interface StatsActions {
    fun back() {}
    fun selectDeck(id: Long?) {}

    companion object {
        val Noop: StatsActions = object : StatsActions {}
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

data class SettingsUiState(
    val desiredRetention: Double = 0.9,
    val newPerDay: Int = 20,
    val maxReviews: Int = 200,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val themeMode: Int = ThemeMode.SYSTEM,
)

interface SettingsActions {
    fun back() {}
    fun setDesiredRetention(value: Double) {}
    fun setNewPerDay(value: Int) {}
    fun setMaxReviews(value: Int) {}
    fun setReminderEnabled(enabled: Boolean) {}
    fun setReminderTime(hour: Int, minute: Int) {}
    fun setThemeMode(mode: Int) {}

    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
