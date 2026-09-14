package com.vayunmathur.maps.ui

import com.vayunmathur.maps.data.google.GooglePoiInfo
import com.vayunmathur.maps.data.google.GoogleReview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The coarse enrichment identity behind the inner place sheet's contentKey.
 *
 * Late-arriving reviews/photos grow the sheet content after measurement, which
 * the scaffold's learned ceiling would otherwise never re-learn — so the key
 * must move when the enrichment arrives. But it must stay coarse
 * (counts/presence, not the lists): progressive review streaming would reset
 * the ceiling on every partial if the key named the content itself.
 */
class PoiEnrichmentKeyTest {

    private fun review(i: Int) = GoogleReview(
        author = "a$i",
        authorPhoto = null,
        rating = 5,
        relativeTime = null,
        text = "review $i",
    )

    @Test
    fun `null enrichment keys to zero`() {
        assertEquals(PoiEnrichmentKey(0, 0, false), PoiEnrichmentKey.of(null))
    }

    @Test
    fun `arriving reviews move the key`() {
        val before = PoiEnrichmentKey.of(GooglePoiInfo())
        val after = PoiEnrichmentKey.of(GooglePoiInfo(reviews = List(8, ::review)))
        assertNotEquals(before, after)
    }

    @Test
    fun `arriving photos move the key`() {
        val before = PoiEnrichmentKey.of(GooglePoiInfo())
        val after = PoiEnrichmentKey.of(GooglePoiInfo(photoUrls = listOf("u1", "u2")))
        assertNotEquals(before, after)
    }

    @Test
    fun `a featured review moves the key`() {
        val before = PoiEnrichmentKey.of(GooglePoiInfo())
        val after = PoiEnrichmentKey.of(GooglePoiInfo(featuredReview = "great soup"))
        assertNotEquals(before, after)
    }

    @Test
    fun `review text churn without growth keeps the key`() {
        // Progressive streaming re-sends the accumulated list on every parse:
        // same size, same shape → same key → the ceiling survives the partials.
        val a = PoiEnrichmentKey.of(GooglePoiInfo(reviews = List(8, ::review)))
        val b = PoiEnrichmentKey.of(
            GooglePoiInfo(reviews = List(8) { review(it).copy(text = "edited $it") })
        )
        assertEquals(a, b)
    }
}
