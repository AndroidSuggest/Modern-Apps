package org.schabi.newpipe.extractor.timeago

import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `unique_patterns.json` is loaded eagerly for all locales at once, so a single unparseable
 * entry breaks time parsing everywhere — which is how one Hebrew dual form turned into
 * "Video load error" on every video in every language.
 */
class PatternsManagerTest {

    @Test
    fun `every bundled locale loads`() {
        // English is arbitrary; the point is that resolving any locale forces the whole file.
        assertNotNull(PatternsManager.getPatterns("en", null), "en patterns should load")
        assertNotNull(PatternsManager.getPatterns("de", null))
        assertNotNull(PatternsManager.getPatterns("ja", null))
    }

    @Test
    fun `hebrew dual forms become special cases, not plain words`() {
        val hebrew = assertNotNull(PatternsManager.getPatterns("iw", null))

        // "שעתיים" means "two hours" and contains no digits, so TimeAgoParser can only resolve
        // it through specialCases.
        val hours = assertNotNull(hebrew.specialCases()[ChronoUnit.HOURS])
        assertEquals(2, hours["שעתיים"], "dual form should map to an amount of 2")

        for ((unit, text) in listOf(
            ChronoUnit.DAYS to "יומיים",
            ChronoUnit.WEEKS to "שבועיים",
            ChronoUnit.MONTHS to "חודשיים",
            ChronoUnit.YEARS to "שנתיים",
        )) {
            assertEquals(2, hebrew.specialCases()[unit]?.get(text), "$unit dual form")
        }
    }

    @Test
    fun `special-case phrases stay matchable as unit words`() {
        val hebrew = assertNotNull(PatternsManager.getPatterns("iw", null))
        assertTrue(
            hebrew.hours().contains("שעתיים"),
            "the phrase must still identify the unit, not only the amount",
        )
    }

    @Test
    fun `plain locales are unaffected`() {
        val afrikaans = assertNotNull(PatternsManager.getPatterns("af", null))
        assertTrue(afrikaans.seconds().contains("sekonde"))
        assertTrue(afrikaans.specialCases().isEmpty(), "af declares no special cases")
    }

    @Test
    fun `unknown locales return null rather than throwing`() {
        assertEquals(null, PatternsManager.getPatterns("zz", null))
    }
}
