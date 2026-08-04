plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:appstore:metadata` task name either way.
    id("common-conventions-preview-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "store"
}

android {
    defaultConfig {
        versionCode = 20260804
        versionName = "v2.6.5"
        applicationId = "com.vayunmathur.appstore"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:network"))
    implementation(project(":library:work"))
    implementation(libs.androidx.datastore.preferences)
    implementation(project(":library:image"))
    implementation(libs.jsoup)
    // APK source-stamp verification. The stamp is a second signing identity that
    // survives Play App Signing re-signing, so it can be pinned per package where the
    // APK signing key (held by Google) cannot be.
    implementation(libs.apksig)
    // HttpURLConnection-based PlayHttpClient/AnonymousAuthRepository/PlayDownloader – no okhttp
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.auroraoss.gplayapi)
}
