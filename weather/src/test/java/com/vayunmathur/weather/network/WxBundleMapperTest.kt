package com.vayunmathur.weather.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden tests for the self-hosted bundle mapper: wire epochs in, the exact
 * naive-local strings the domain layer consumes. Anchored on Berlin
 * (CEST, UTC+2 in October): 2026-10-07T01:00Z == "2026-10-07T03:00" local.
 */
class WxBundleMapperTest {

    private fun bundle() = WxBundle(
        lat = 52.52,
        lon = 13.41,
        tz = "Europe/Berlin",
        tzAbbr = "CEST",
        utcOffset = 7200,
        current = WxCurrent(
            time = 1791334800, temp = 13.05, feels = 12.3, rh = 91,
            dew = 11.6, code = 2, windSpd = 3.6, windDir = 96,
            pres = 1017.4, vis = 8000.0, cloud = 62, gust = 6.5, day = 0,
        ),
        hourly = WxHourly(
            start = 1791334800, step = 3600, count = 2,
            temp = listOf(13.05, 12.8), feels = listOf(12.3, 12.0),
            rh = listOf(91, 92), dew = listOf(11.6, 11.4),
            code = listOf(2, 2), pprob = listOf(5, 5),
            precip = listOf(0.0, 0.0), windSpd = listOf(3.6, 4.0),
            windDir = listOf(96, 100), pres = listOf(1017.4, 1017.0),
            vis = listOf(8000.0, 8000.0), uv = listOf(0.0, 0.0),
            cloud = listOf(62, 65), gust = listOf(6.5, 7.0),
            day = listOf(0, 0),
        ),
        daily = WxDaily(
            start = "2026-10-07", count = 2,
            code = listOf(2, 3), tmax = listOf(18.0, 17.0),
            tmin = listOf(11.0, 10.0), feelsMax = listOf(17.5, 16.5),
            feelsMin = listOf(10.5, 9.5),
            sunrise = listOf(1791315600, 1791402000),
            sunset = listOf(1791356400, 1791442800),
            daylight = listOf(40800, 40800), sunshine = listOf(20000, 18000),
            uvMax = listOf(3.0, 2.5), pprobMax = listOf(10, 20),
            precipSum = listOf(0.0, 0.5), moonPhase = listOf(0.98, 0.01),
            moonrise = listOf(1791320000, null), moonset = listOf(1791340000, 1791420000),
        ),
        min15 = WxMin15(start = 1791334800, step = 900, count = 4, precip = listOf(0.0, 0.0, 0.0, 0.0)),
        aq = WxAq(
            time = 1791334800, usAqi = 61, grass = 0.2, birch = 0.0,
            olive = 0.0, alder = 0.0, ragweed = 0.0, mugwort = 0.6,
        ),
    )

    @Test
    fun current_mapsToNaiveLocalStrings() {
        val f = bundle().toForecastResponse()
        assertEquals("Europe/Berlin", f.timezone)
        assertEquals("CEST", f.timezoneAbbreviation)
        assertEquals(7200, f.utcOffsetSeconds)
        val c = assertNotNull(f.current)
        assertEquals("2026-10-07T03:00", c.time)
        assertEquals(13.05, c.temperature)
        assertEquals(2, c.weatherCode)
    }

    @Test
    fun hourly_expandsSeriesToLocalIso() {
        val h = assertNotNull(bundle().toForecastResponse().hourly)
        assertEquals(listOf("2026-10-07T03:00", "2026-10-07T04:00"), h.time)
        assertEquals(listOf(13.05, 12.8), h.temperature)
        assertEquals(listOf(2, 2), h.weatherCode)
    }

    @Test
    fun daily_datesAreConsecutiveFromStart() {
        val d = assertNotNull(bundle().toForecastResponse().daily)
        assertEquals(listOf("2026-10-07", "2026-10-08"), d.time)
        assertEquals(listOf(18.0, 17.0), d.temperatureMax)
        // Epoch sun/moon instants become naive-local `yyyy-MM-dd'T'HH:mm`;
        // monthly-skipped moon events stay null.
        val iso = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
        assertTrue(d.sunrise.all { iso.matches(it) })
        assertTrue(d.sunset.all { iso.matches(it) })
        assertEquals(null, d.moonrise[1])
        assertNotNull(d.moonset[1])
    }

    @Test
    fun min15_expandsQuarterHours() {
        val m = assertNotNull(bundle().toForecastResponse().minutely15)
        assertEquals(
            listOf("2026-10-07T03:00", "2026-10-07T03:15", "2026-10-07T03:30", "2026-10-07T03:45"),
            m.time,
        )
    }

    @Test
    fun aq_mapsPollenBySpecies() {
        val a = assertNotNull(bundle().toAirQuality()?.current)
        assertEquals(61, a.usAqi)
        assertEquals(0.2, a.grassPollen)
        assertEquals("2026-10-07T03:00", a.time)
    }

    @Test
    fun missingAq_mapsToNull() {
        assertNull(bundle().copy(aq = null).toAirQuality())
    }

    @Test
    fun tzBundle_mapsToRegionTimezone() {
        val tz = bundle().toRegionTimezone()
        assertEquals("Europe/Berlin", tz.timezone)
        assertEquals("CEST", tz.abbreviation)
        assertEquals(7200, tz.utcOffsetSeconds)
    }

    @Test
    fun nullUv_passesThrough_notCrash() {
        // CAMS UV horizon (~4d) is shorter than the 14-day window: the wire
        // carries JSON null past coverage. Nulls pass through end-to-end
        // (DTO lists are nullable-element, JSON- and cache-safe); consumers
        // skip them via getOrNull fallbacks. NaN must never appear: JSON can
        // neither parse nor emit it under strict kotlinx settings.
        val b = bundle().copy(
            hourly = bundle().hourly!!.copy(uv = listOf(3.0, null)),
            daily = bundle().daily!!.copy(uvMax = listOf(3.0, null)),
        )
        val f = b.toForecastResponse()
        assertEquals(listOf(3.0, null), assertNotNull(f.hourly).uvIndex)
        assertEquals(listOf(3.0, null), assertNotNull(f.daily).uvIndexMax)
    }
}
