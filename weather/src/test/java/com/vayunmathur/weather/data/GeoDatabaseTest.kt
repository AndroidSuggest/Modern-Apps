package com.vayunmathur.weather.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeoDatabaseTest {

    @Test
    fun escaper_prefixMatchesEveryToken() {
        // Prefix `*` on every token: half-typed words must match (see
        // ReferenceCatalog.escapeFtsQuery — same contract).
        assertEquals("\"Ber\"*", GeoDatabase.escapeFtsQuery("Ber"))
        assertEquals("\"New\"* \"York\"*", GeoDatabase.escapeFtsQuery("New York"))
    }

    @Test
    fun escaper_quotesOperators() {
        assertEquals("\"Ben\"* \"Jerry\"* \"s\"*", GeoDatabase.escapeFtsQuery("Ben & Jerry's"))
    }

    @Test
    fun escaper_unicodeSurvivesWhole() {
        assertEquals("\"caf\u00e9\"*", GeoDatabase.escapeFtsQuery("café"))
    }

    @Test
    fun escaper_blankIsNull() {
        assertNull(GeoDatabase.escapeFtsQuery(""))
        assertNull(GeoDatabase.escapeFtsQuery("   "))
        assertNull(GeoDatabase.escapeFtsQuery("- *"))
    }
}
