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
        versionCode = 20260809
        versionName = "v2.6.6"
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

// Native code for this module, loaded as libnetworklocation.so: RANSAC + EM multilateration
// device-position estimation over the cached gs-loc beacon fixes, AND the offline geocoder
// search over geocoder.geodb. Both live in networklocation/src/main/rust/.
rustNativeLib("networklocation")

// The packed geocoder DB (~1-1.5 GB) is not committed (see src/main/assets/.gitignore).
// Fetch it into assets at build time so the assembled APK — which MAOS also consumes —
// ships the offline geocoder. Only downloads when the asset is absent; fails soft (warns,
// leaves no partial file) so a network hiccup or an intentionally DB-less dev build still
// compiles.
val geocoderDbUrl = "https://data.vayunmathur.com/geocoder/geocoder.geodb"
val geocoderDbFile = layout.projectDirectory.file("src/main/assets/geocoder.geodb").asFile

val fetchGeocoderDb = tasks.register("fetchGeocoderDb") {
    // Capture plain String/File locals so the task actions below don't hold references to the
    // build script itself — Gradle's configuration cache cannot serialize script object refs.
    val url = geocoderDbUrl
    val outFile = geocoderDbFile
    description = "Downloads geocoder.geodb into src/main/assets if it is missing."
    outputs.file(outFile)
    outputs.upToDateWhen { outFile.exists() }
    doLast {
        if (outFile.exists()) {
            logger.lifecycle("geocoder.geodb already present (${outFile.length()} bytes); skipping download.")
            return@doLast
        }
        outFile.parentFile.mkdirs()
        val tmp = File(outFile.parentFile, "geocoder.geodb.part")
        tmp.delete()
        logger.lifecycle("Fetching geocoder.geodb from $url (this is large; first build only)…")
        try {
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
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
            tmp.renameTo(outFile)
            logger.lifecycle("Fetched geocoder.geodb (${outFile.length()} bytes).")
        } catch (e: Exception) {
            tmp.delete()
            logger.warn(
                "WARNING: could not fetch geocoder.geodb ({}). The APK will build WITHOUT the offline " +
                    "geocoder DB. Generate it locally (scripts/geocoder_gen.sh) or check {} and rebuild.",
                e.message, url,
            )
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(fetchGeocoderDb)
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
    // The offline geocoder search runs natively (Rust/ruzstd) over the bundled geocoder.geodb
    // asset; there is no Kotlin-side DB code, so no zstd-jni / serialization deps are needed here.
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
