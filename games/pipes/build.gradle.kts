plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:pipes:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "diagonal_line"
}

android {
    defaultConfig {
        versionCode = 20260809
        versionName = "v2.6.6"
        applicationId = "com.vayunmathur.games.pipes"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
