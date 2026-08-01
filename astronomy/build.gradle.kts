plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "star"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.astronomy"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native celestial transforms + sky projection (Rust). See astronomy/src/main/rust/.
rustNativeLib("astronomy_engine", "astronomy")

metadataScreenshots {
    // SkyMapPage gates behind location via PermissionsChecker — grant so first-run
    // system prompt doesn't hijack the screenshots. Camera is optional AR overlay.
    permissions.addAll(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION"
    )
}

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.datastore.preferences)
}
