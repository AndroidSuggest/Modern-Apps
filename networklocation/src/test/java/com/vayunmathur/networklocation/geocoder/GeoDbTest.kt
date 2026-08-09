package com.vayunmathur.networklocation.geocoder

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoDbTest {
    private val tmp: File = File.createTempFile("geodb", ".bin").apply { deleteOnExit() }

    @AfterTest fun cleanup() { tmp.delete() }

    /** Synthetic world with realistic field reuse (few countries/states, many streets/houses). */
    private fun synth(count: Int): List<GeoAddress> {
        val countries = listOf("US", "CA", "GB", "FR", "DE")
        val out = ArrayList<GeoAddress>(count)
        var k = 0
        while (out.size < count) {
            val country = countries[k % countries.size]
            val state = "State${k % 50}"
            val city = "City${k % 2000}"
            val street = "Street ${k % 10000}"
            val house = ((k % 500) + 1).toString()
            val postcode = (10000 + (k % 90000)).toString()
            // Spread coordinates deterministically across the globe, distinct per record.
            val lat = -60.0 + (k % 12000) * 0.01
            val lon = -179.0 + ((k / 12000) % 35000) * 0.01
            out.add(GeoAddress(lat, lon, house, street, city, state, country, postcode))
            k++
        }
        return out
    }

    @Test fun roundTripReverseAndForward() {
        val addrs = synth(20_000)
        GeoDbWriter().write(tmp, addrs)

        GeoDbReader(tmp).use { db ->
            assertEquals(addrs.size, db.size)

            // Reverse: query the exact coordinate of a few addresses -> nearest should match.
            for (i in listOf(0, 7, 123, 9999, 19_999)) {
                val a = addrs[i]
                val r = db.reverse(a.lat, a.lon)!!
                assertEquals(a.lat, r.lat, 1e-4, "reverse lat @${i}")
                assertEquals(a.lon, r.lon, 1e-4, "reverse lon @${i}")
            }

            // Forward: structured lookup returns matching addresses on that street.
            val a = addrs[123]
            val hits = db.forward(a.country, a.state, a.city, a.street)
            assertTrue(hits.isNotEmpty(), "forward returned nothing")
            assertTrue(hits.all { it.street == a.street && it.city == a.city && it.country == a.country })
            assertTrue(hits.any { it.house == a.house }, "target house not found")

            // Unknown location name -> empty.
            assertTrue(db.forward("US", "State0", "Nowhere", "No Street").isEmpty())
        }
    }

    @Test fun sizeExtrapolation() {
        val n = 50_000
        GeoDbWriter().write(tmp, synth(n))
        val bytesPerRecord = tmp.length().toDouble() / n
        val projectedGb = bytesPerRecord * 170_000_000 / 1e9
        println(
            "GeoDb size: ${tmp.length()} bytes for $n records = " +
                "%.2f B/record -> ~%.2f GB at 170M".format(bytesPerRecord, projectedGb)
        )
        // Sanity only: the packed format must stay well under the naive fixed-width (~24 B/rec).
        assertTrue(bytesPerRecord < 24.0, "packing worse than fixed-width: $bytesPerRecord B/rec")
    }
}
