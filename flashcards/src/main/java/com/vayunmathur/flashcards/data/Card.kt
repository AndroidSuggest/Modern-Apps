package com.vayunmathur.flashcards.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.serialization.Serializable

/** FSRS learning states stored in [Card.state]. */
object CardState {
    const val NEW = 0
    const val LEARNING = 1
    const val REVIEW = 2
    const val RELEARNING = 3
}

/**
 * A single flashcard plus its FSRS spaced-repetition memory state.
 *
 * [front]/[back] are treated as **markdown** (rendered via `parseMarkdown`).
 * [dueDate] is epoch millis; `0` means the card is new and always due.
 *
 * FSRS bookkeeping ([stability], [difficulty], [state], [lastReview], [lapses],
 * [reps]) is updated by `Scheduler.schedule` each time the card is graded. The
 * legacy SM-2 columns ([easeFactor], [intervalDays], [repetitions]) are retained
 * across the v1 -> v2 migration for safety and are otherwise unused.
 */
@Serializable
@Entity
data class Card(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val deckId: Long,
    val front: String,
    val back: String,
    val tags: String = "",
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val state: Int = CardState.NEW,
    val lastReview: Long = 0,
    val lapses: Int = 0,
    val reps: Int = 0,
    val dueDate: Long = 0,
    val easeFactor: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    override val position: Double = 0.0,
) : ReorderableDatabaseItem<Card> {
    override fun withPosition(position: Double) = copy(position = position)

    /** True while the card has never been graded. */
    val isNew: Boolean get() = state == CardState.NEW
}
