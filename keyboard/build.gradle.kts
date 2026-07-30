plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "keyboard"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.keyboard"
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
}
