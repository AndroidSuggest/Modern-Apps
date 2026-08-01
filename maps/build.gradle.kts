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
        versionCode = 20260731
        versionName = "v2.6.4"
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
