package com.vayunmathur.emergency.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Checks for the postal fallback join used when a picked contact's address
 * row has no preformatted address.
 */
class OwnerPostalFormatTest {

    @Test
    fun allComponentsJoinWithCommas() {
        assertEquals(
            "1 Main St, Springfield, IL, 62701, USA",
            formatPostalFallback("1 Main St", "Springfield", "IL", "62701", "USA"),
        )
    }

    @Test
    fun blankAndNullComponentsAreDropped() {
        assertEquals(
            "Springfield, USA",
            formatPostalFallback(null, "Springfield", "", "   ", "USA"),
        )
    }

    @Test
    fun allBlankGivesEmptyString() {
        assertEquals("", formatPostalFallback(null, "", "  ", null, ""))
    }
}
