plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "calculate"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.calculator"
    }
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
}
