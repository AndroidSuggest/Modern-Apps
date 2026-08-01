plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
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
