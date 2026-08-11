package com.vayunmathur.flashcards.util

import com.vayunmathur.flashcards.data.Card
import kotlin.math.roundToInt

/**
 * SM-2 grades. [q] is the SuperMemo quality-of-recall score (0–5); we expose the
 * four Anki-style buttons and map them onto the passing/failing halves of that
 * scale (q < 3 fails and resets the card, q >= 3 passes).
 */
enum class Grade(val q: Int) {
    AGAIN(2),
    HARD(3),
    GOOD(4),
    EASY(5),
}

/**
 * Pure SM-2 spaced-repetition scheduler. Kept free of Android dependencies so it
 * can be unit-tested directly. [schedule] returns a copy of [card] with updated
 * ease factor, interval, repetition count and due date; it never mutates input.
 */
object Scheduler {
    const val DAY_MS: Long = 24L * 60 * 60 * 1000

    /** Ease factor is never allowed below this SM-2 floor. */
    const val MIN_EASE = 1.3

    fun schedule(card: Card, grade: Grade, now: Long): Card {
        val q = grade.q

        val repetitions: Int
        val intervalDays: Int
        if (q < 3) {
            // Failed recall: relearn from the start with a one-day interval.
            repetitions = 0
            intervalDays = 1
        } else {
            intervalDays = when (card.repetitions) {
                0 -> 1
                1 -> 6
                else -> (card.intervalDays * card.easeFactor).roundToInt()
            }
            repetitions = card.repetitions + 1
        }

        val easeFactor = (card.easeFactor + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
            .coerceAtLeast(MIN_EASE)

        return card.copy(
            easeFactor = easeFactor,
            intervalDays = intervalDays,
            repetitions = repetitions,
            dueDate = now + intervalDays * DAY_MS,
        )
    }
}
