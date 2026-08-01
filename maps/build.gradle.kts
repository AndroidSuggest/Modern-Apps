plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
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
    // MapTileCache installs a MapLibre ModuleProvider whose HttpRequest runs on
    // library:network, so the map stack never touches MapLibre's bundled OkHttp
    // implementation.
    implementation(project(":library:network"))

    implementation(libs.flatgeobuf)


    // room
    implementRoom(libs)
    implementation(project(":library:downloadservice"))
}
