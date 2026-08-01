plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "store"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
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
    // HttpURLConnection-based PlayHttpClient/AnonymousAuthRepository/PlayDownloader – no okhttp
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.auroraoss.gplayapi)
}
