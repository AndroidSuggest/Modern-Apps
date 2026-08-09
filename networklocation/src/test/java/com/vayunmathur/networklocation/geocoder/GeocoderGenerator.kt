package com.vayunmathur.networklocation.geocoder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test

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
