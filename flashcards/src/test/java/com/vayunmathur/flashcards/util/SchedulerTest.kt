package com.vayunmathur.flashcards.util

import com.vayunmathur.flashcards.data.Card
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerTest {

    private val now = 1_000_000_000_000L

    private fun card(
        easeFactor: Double = 2.5,
        intervalDays: Int = 0,
        repetitions: Int = 0,
    ) = Card(
        id = 1,
        deckId = 1,
        front = "q",
        back = "a",
        easeFactor = easeFactor,
        intervalDays = intervalDays,
        repetitions = repetitions,
    )

    @Test
    fun againResetsIntervalAndRepetitions() {
        val graded = Scheduler.schedule(
            card(intervalDays = 20, repetitions = 5),
            Grade.AGAIN,
            now,
        )
        assertEquals(0, graded.repetitions)
        assertEquals(1, graded.intervalDays)
        assertEquals(now + Scheduler.DAY_MS, graded.dueDate)
    }

    @Test
    fun firstSuccessIsOneDay() {
        val graded = Scheduler.schedule(card(), Grade.GOOD, now)
        assertEquals(1, graded.intervalDays)
        assertEquals(1, graded.repetitions)
        assertEquals(now + Scheduler.DAY_MS, graded.dueDate)
    }

    @Test
    fun secondSuccessIsSixDays() {
        val graded = Scheduler.schedule(card(intervalDays = 1, repetitions = 1), Grade.GOOD, now)
        assertEquals(6, graded.intervalDays)
        assertEquals(2, graded.repetitions)
    }

    @Test
    fun thirdSuccessScalesByEaseFactor() {
        // interval = round(6 * 2.5) = 15
        val graded = Scheduler.schedule(
            card(easeFactor = 2.5, intervalDays = 6, repetitions = 2),
            Grade.GOOD,
            now,
        )
        assertEquals(15, graded.intervalDays)
        assertEquals(3, graded.repetitions)
    }

    @Test
    fun easeFactorIsClampedAtFloor() {
        var c = card(easeFactor = 2.5, intervalDays = 10, repetitions = 3)
        // Repeated failures drive the ease factor down; it must never go below 1.3.
        repeat(10) {
            c = Scheduler.schedule(c, Grade.AGAIN, now)
            assertTrue(c.easeFactor >= Scheduler.MIN_EASE, "ease below floor: ${c.easeFactor}")
        }
        assertEquals(Scheduler.MIN_EASE, c.easeFactor)
    }
}
