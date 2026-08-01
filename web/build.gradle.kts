plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "language"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
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
}
