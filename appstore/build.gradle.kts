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
        applicationId = "com.vayunmathur.appstore"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:network"))
    implementation(project(":library:work"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.auroraoss.gplayapi)
}
