plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "crossword"
    scale = 0.5
}

android {
    defaultConfig {
        versionCode = 20260725
        versionName = "v2.6.2"
        applicationId = "com.vayunmathur.games.wordmaker"
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.brotli.dec)
}
