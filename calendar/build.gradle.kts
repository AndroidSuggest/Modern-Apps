plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:calendar:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "calendar_month"
}

android {
    defaultConfig {
        versionCode = 20260809
        versionName = "v2.6.6"
        applicationId = "com.vayunmathur.calendar"
    }
}

dependencies {
    implementation(project(":library:widgets"))

}
