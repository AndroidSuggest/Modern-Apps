plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:code:metadata` task name either way.
    id("common-conventions-preview-metadata")
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
    implementation(libs.jgit)
}
