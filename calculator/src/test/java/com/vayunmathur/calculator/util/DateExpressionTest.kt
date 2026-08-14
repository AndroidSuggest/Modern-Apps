package com.vayunmathur.calculator.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers the date/datetime support added to the expression engine: the `#<epoch>` literal and
 * the instant arithmetic rules (instant−instant=duration, instant±duration=instant). */
class DateExpressionTest {

    private fun q(src: String): Quantity = Expression.parse(src).evalQuantity()

    @Test
    fun dateMinusDateGivesDuration() {
        val result = q("#1000000 - #900000")
        assertEquals(Dimension.TIME, result.dimension)
        assertFalse(result.instant, "a difference of two dates is a duration, not an instant")
        assertEquals(100000.0, result.value, 1e-9)
    }

    @Test
    fun datePlusDurationGivesLaterDate() {
        val result = q("#1000000 + 1day")
        assertTrue(result.instant)
        assertEquals(1000000.0 + 86400.0, result.value, 1e-9)
    }

    @Test
    fun durationPlusDateGivesLaterDate() {
        val result = q("2h + #1000000")
        assertTrue(result.instant)
        assertEquals(1000000.0 + 7200.0, result.value, 1e-9)
    }

    @Test
    fun dateMinusDurationGivesEarlierDate() {
        val result = q("#1000000 - 1day")
        assertTrue(result.instant)
        assertEquals(1000000.0 - 86400.0, result.value, 1e-9)
    }

    @Test
    fun addingTwoDatesIsRejected() {
        assertFailsWith<ExpressionError> { q("#1000000 + #900000") }
    }

    @Test
    fun subtractingDateFromDurationIsRejected() {
        assertFailsWith<ExpressionError> { q("1day - #1000000") }
    }

    @Test
    fun multiplyingADateIsRejected() {
        assertFailsWith<ExpressionError> { q("#1000000 * 2") }
    }

    @Test
    fun addingAScalarToADateIsRejected() {
        assertFailsWith<ExpressionError> { q("#1000000 + 5") }
    }
}
