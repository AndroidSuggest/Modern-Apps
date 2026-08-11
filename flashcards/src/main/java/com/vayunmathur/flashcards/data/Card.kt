package com.vayunmathur.flashcards.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.serialization.Serializable

/**
 * A single plain-text flashcard plus its SM-2 spaced-repetition state.
 *
 * [dueDate] is epoch millis; `0` means the card is new / never studied and is
 * therefore always due. [easeFactor], [intervalDays] and [repetitions] are the
 * SM-2 bookkeeping updated by `Scheduler.schedule` each time the card is graded.
 */
@Serializable
@Entity
data class Card(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val deckId: Long,
    val front: String,
    val back: String,
    val easeFactor: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    val dueDate: Long = 0,
    override val position: Double = 0.0,
) : ReorderableDatabaseItem<Card> {
    override fun withPosition(position: Double) = copy(position = position)
}
