import java.io.File
import java.net.HttpURLConnection
import java.net.URI

plugins {
    id("common-conventions-app")
    alias(libs.plugins.protobuf)
}

launcherIcon {
    symbol = "my_location"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.networklocation"
    }
    androidResources {
        // The geocoder DB must stay uncompressed in the APK so it can be mmap'd directly from
        // the asset file descriptor (no unzip, no copy to filesDir).
        noCompress += "geodb"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native device-position estimation (weighted-centroid / inverse-variance least
// squares over the cached gs-loc beacon fixes). See networklocation/src/main/rust/.
rustNativeLib("networklocation")

// The packed geocoder DB (~1-1.5 GB) is not committed (see src/main/assets/.gitignore).
// Fetch it into assets at build time so the assembled APK — which MAOS also consumes —
// ships the offline geocoder. Only downloads when the asset is absent; fails soft (warns,
// leaves no partial file) so a network hiccup or an intentionally DB-less dev build still
// compiles.
val geocoderDbUrl = "https://data.vayunmathur.com/geocoder/geocoder.geodb"
val geocoderDbFile = layout.projectDirectory.file("src/main/assets/geocoder.geodb").asFile

val fetchGeocoderDb = tasks.register("fetchGeocoderDb") {
    description = "Downloads geocoder.geodb into src/main/assets if it is missing."
    outputs.file(geocoderDbFile)
    outputs.upToDateWhen { geocoderDbFile.exists() }
    doLast {
        if (geocoderDbFile.exists()) {
            logger.lifecycle("geocoder.geodb already present (${geocoderDbFile.length()} bytes); skipping download.")
            return@doLast
        }
        geocoderDbFile.parentFile.mkdirs()
        val tmp = File(geocoderDbFile.parentFile, "geocoder.geodb.part")
        tmp.delete()
        logger.lifecycle("Fetching geocoder.geodb from $geocoderDbUrl (this is large; first build only)…")
        try {
            val conn = (URI(geocoderDbUrl).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                error("server returned HTTP $code")
            }
            conn.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) } }
            conn.disconnect()
            tmp.renameTo(geocoderDbFile)
            logger.lifecycle("Fetched geocoder.geodb (${geocoderDbFile.length()} bytes).")
        } catch (e: Exception) {
            tmp.delete()
            logger.warn(
                "WARNING: could not fetch geocoder.geodb ({}). The APK will build WITHOUT the offline " +
                    "geocoder DB. Generate it locally (tools/) or check {} and rebuild.",
                e.message, geocoderDbUrl,
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(fetchGeocoderDb)
}

// The geocoder generator runs on the JVM test classpath (GeoDb* are pure-Kotlin). The planet
// build needs a large heap; override with GEOCODER_HEAP (e.g. 100g). Normal unit tests are
// unaffected — they won't allocate near this ceiling.
tasks.withType<Test>().configureEach {
    maxHeapSize = System.getenv("GEOCODER_HEAP") ?: "2g"
}

dependencies {
    // Compile-only stubs for the framework's unbundled provider API (com.android.location.provider).
    // Provided at runtime by <uses-library>; must NOT be packaged.
    compileOnly(project(":library:locationprovider"))
    // Apple gs-loc request/response wire format (proto/apple_wps.proto).
    implementation(libs.protobuf.javalite)
    // Beacon-location cache (in-memory TimedLruCache in front of a Room table).
    implementRoom(libs)
    // Reporting loop + IO for the gs-loc queries.
    implementation(libs.kotlinx.coroutines.android)
    // Geocoder DB is a self-contained mmap'd binary (see geocoder/). Block compression uses
    // java.util.zip (Deflate) — no external dependency needed for the core.
    // kotlinx-serialization-json (from the app convention) is used only by the generator to
    // parse osmium's GeoJSONSeq export.
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobufJavalite.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}
