package com.vayunmathur.networklocation.geocoder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real geocoder-DB generator. Not a unit test — it reuses the JVM test classpath (GeoDb* are
 * pure Kotlin) so it can be run without an Android device or a bespoke JavaExec setup.
 *
 * Pipeline (see tools/extract-osm-addresses.sh):
 *   planet.osm.pbf --osmium--> addr.geojsonseq  (one GeoJSON Feature per line, addr:* tags)
 *   addr.geojsonseq --this--> geocoder.geodb     (packed, mmap'd by the app at runtime)
 *
 * Run (give it a big heap for the whole planet):
 *   GEOCODER_HEAP=100g ./gradlew :networklocation:testDebugUnitTest \
 *       --tests '*GeocoderGenerator' \
 *       -Dgeocoder.input=/data/addr.geojsonseq \
 *       -Dgeocoder.output=networklocation/src/main/assets/geocoder.geodb
 *
 * With no -Dgeocoder.input it is a no-op, so ordinary test runs skip it.
 */
class GeocoderGenerator {

    @Test
    fun generate() {
        val input = System.getProperty("geocoder.input") ?: run {
            println("GeocoderGenerator: no -Dgeocoder.input set; skipping.")
            return
        }
        val output = System.getProperty("geocoder.output")
            ?: "networklocation/src/main/assets/geocoder.geodb"

        val inFile = File(input)
        require(inFile.exists()) { "input not found: $input" }
        val outFile = File(output).apply { parentFile?.mkdirs() }
        buildDb(inFile, outFile)
    }

    /**
     * Parse a GeoJSONSeq file (one Feature per line, optional RS separators) into a GeoDb.
     * Returns the number of addresses kept. Shared by [generate] and the self-test so both
     * exercise exactly the same parse -> write path.
     */
    internal fun buildDb(inFile: File, outFile: File): Int {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val addresses = ArrayList<GeoAddress>(1 shl 20)
        var lines = 0L
        var skipped = 0L

        inFile.bufferedReader().useLines { seq ->
            for (raw in seq) {
                val line = raw.trim().trim('\u001e') // GeoJSONSeq may use RS (0x1e) separators
                if (line.isEmpty() || line[0] != '{') continue
                lines++
                val a = parseFeature(json, line)
                if (a == null) skipped++ else addresses.add(a)
                if (lines % 5_000_000L == 0L) {
                    println("…parsed $lines features, kept ${addresses.size}")
                }
            }
        }

        println("Parsed $lines features; kept ${addresses.size}; skipped $skipped (no coord/street).")
        GeoDbWriter().write(outFile, addresses)
        val mb = outFile.length() / (1024.0 * 1024.0)
        println("Wrote ${outFile.absolutePath} — %.1f MB for ${addresses.size} addresses.".format(mb))
        return addresses.size
    }

    /**
     * Self-contained end-to-end check: synthesise a tiny GeoJSONSeq sample (Point + LineString +
     * Polygon features spread across two grid cells, plus RS separators and a junk line that must
     * be skipped), run the real parse -> write pipeline, then read it back and assert both reverse
     * and forward lookups. Uses the same [buildDb] path as the planet build, so it protects the
     * parser and the on-disk format in ordinary unit-test runs.
     */
    @Test
    fun selfTestSyntheticSample() {
        val dir = Files.createTempDirectory("geocoder-selftest").toFile()
        val seq = File(dir, "addr.geojsonseq")
        val out = File(dir, "geocoder.geodb")
        try {
            // Cell A ~ (39.78, -89.65); Cell B ~ (40.10, -89.00). CELL is 0.05deg so these differ.
            fun feature(
                house: String, street: String, city: String, state: String,
                country: String, postcode: String, geometry: String,
            ): String = """{"type":"Feature","properties":{""" +
                """"addr:housenumber":"$house","addr:street":"$street",""" +
                """"addr:city":"$city","addr:state":"$state",""" +
                """"addr:country":"$country","addr:postcode":"$postcode"},""" +
                """"geometry":$geometry}"""

            val rs = "\u001e" // GeoJSONSeq record separator; parser must tolerate it.
            val lines = listOf(
                rs + feature("10", "Main St", "Springfield", "IL", "US", "62704",
                    """{"type":"Point","coordinates":[-89.6500,39.7800]}"""),
                feature("12", "Main St", "Springfield", "IL", "US", "62704",
                    """{"type":"Point","coordinates":[-89.6510,39.7810]}"""),
                // A way (LineString): centroid is the average of the two endpoints.
                feature("20", "Oak Ave", "Springfield", "IL", "US", "62704",
                    """{"type":"LineString","coordinates":[[-89.6400,39.7700],[-89.6420,39.7720]]}"""),
                // A building (Polygon) in a different grid cell; centroid ~ (40.10, -89.00).
                rs + feature("5", "Elm St", "Lincoln", "IL", "US", "62656",
                    """{"type":"Polygon","coordinates":[[[-89.0010,40.0990],[-88.9990,40.0990],""" +
                    """[-88.9990,40.1010],[-89.0010,40.1010],[-89.0010,40.0990]]]}"""),
                // No street/house -> must be skipped.
                """{"type":"Feature","properties":{"addr:city":"Nowhere"},""" +
                    """"geometry":{"type":"Point","coordinates":[0,0]}}""",
                "not-json-garbage-line",
            )
            seq.writeText(lines.joinToString("\n"))

            val kept = buildDb(seq, out)
            assertEquals(4, kept, "expected 4 kept addresses (1 no-addr + 1 junk skipped)")

            GeoDbReader(out).use { db ->
                assertEquals(4, db.size)

                // Reverse: query the exact coord of "10 Main St" -> nearest must be it.
                val r = db.reverse(39.7800, -89.6500)!!
                assertEquals("10", r.house, "reverse house")
                assertEquals("Main St", r.street, "reverse street")
                assertEquals(39.7800, r.lat, 1e-4, "reverse lat")
                assertEquals(-89.6500, r.lon, 1e-4, "reverse lon")

                // Reverse in the other cell -> Elm St polygon centroid.
                val r2 = db.reverse(40.1000, -89.0000)!!
                assertEquals("Elm St", r2.street, "reverse cell B street")
                assertEquals(40.1000, r2.lat, 1e-3, "polygon centroid lat")
                assertEquals(-89.0000, r2.lon, 1e-3, "polygon centroid lon")

                // Forward: both Main St houses come back, none from Oak Ave.
                val hits = db.forward("US", "IL", "Springfield", "Main St")
                assertEquals(2, hits.size, "forward Main St count")
                assertTrue(hits.all { it.street == "Main St" && it.city == "Springfield" })
                assertTrue(hits.map { it.house }.toSet() == setOf("10", "12"), "forward houses")

                // Unknown street -> empty.
                assertTrue(db.forward("US", "IL", "Springfield", "No Such St").isEmpty())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun parseFeature(json: Json, line: String): GeoAddress? {
        val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val props = obj["properties"]?.jsonObject ?: return null
        val street = props.str("addr:street")
        val house = props.str("addr:housenumber")
        // Require at least a street or house number to be a useful address.
        if (street.isEmpty() && house.isEmpty()) return null

        val geom = obj["geometry"]?.jsonObject ?: return null
        val coords = geom["coordinates"]?.jsonArray ?: return null
        val (lon, lat) = averageCoord(coords) ?: return null

        return GeoAddress(
            lat = lat,
            lon = lon,
            house = house,
            street = street,
            city = props.str("addr:city"),
            state = props.str("addr:state").ifEmpty { props.str("addr:province") },
            country = props.str("addr:country"),
            postcode = props.str("addr:postcode"),
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content ?: ""

    /** Average of all [lon,lat] leaves — a cheap representative point for ways/areas. */
    private fun averageCoord(arr: JsonArray): Pair<Double, Double>? {
        var sumLon = 0.0
        var sumLat = 0.0
        var n = 0
        fun walk(e: JsonArray) {
            // A coordinate pair is [number, number]; otherwise recurse into nested arrays.
            val first = e.firstOrNull()
            if (first is JsonPrimitive && first.doubleOrNull != null) {
                val lon = (e[0] as JsonPrimitive).doubleOrNull ?: return
                val lat = (e[1] as JsonPrimitive).doubleOrNull ?: return
                sumLon += lon; sumLat += lat; n++
            } else {
                for (child in e) if (child is JsonArray) walk(child)
            }
        }
        walk(arr)
        return if (n == 0) null else (sumLon / n) to (sumLat / n)
    }
}
