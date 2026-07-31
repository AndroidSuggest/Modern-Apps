plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "location_on"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.maps"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native offline routing engine + traffic MVT tile encoder (Rust). See
// maps/src/main/rust/. Replaces the previous CMake/C++ libofflinerouter.
rustNativeLib("offlinerouter", "maps")

dependencies {
    implementation(libs.maplibre.compose)
    implementation(project(":library:image"))
    // Installs a disk-caching Call.Factory into MapLibre for the streamed
    // pmtiles basemap (see MapTileCache). okhttp is already on the runtime
    // classpath via maplibre android-sdk; declare it for compilation.
    implementation(libs.okhttp)

    implementation(project(":library:network"))

    implementation(libs.flatgeobuf)


    // room
    implementRoom(libs)
    implementation(project(":library:downloadservice"))
}