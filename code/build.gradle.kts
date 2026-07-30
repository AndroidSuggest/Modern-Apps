plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "code"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.code"
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
}
