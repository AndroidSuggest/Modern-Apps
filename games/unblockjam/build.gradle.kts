plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "grid_on"
}

android {
    defaultConfig {
        versionCode = 20260809
        versionName = "v2.6.6"
        applicationId = "com.vayunmathur.games.unblockjam"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
