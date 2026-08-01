plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:<module>:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "science"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.alchemist"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
