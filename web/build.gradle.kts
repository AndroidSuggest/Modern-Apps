plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:web:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "language"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.web"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.datastore.preferences)
    implementation(project(":library:image"))
    // Browser must allow all certs (any host + corp proxies via user CAs) — SYSTEM permissive, documents intent.
    implementation(project(":library:network"))
}
