plugins {
    id("common-conventions-app")
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
    // Geocoder DB is a self-contained mmap'd binary (see geocoder/). Block compression uses
    // java.util.zip (Deflate) — no external dependency needed for the core.
    // kotlinx-serialization-json (from the app convention) is used only by the generator to
    // parse osmium's GeoJSONSeq export.
}
