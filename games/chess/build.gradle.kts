plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:games:chess:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "chess"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.games.chess"
    }
    androidResources {
        // Maia3's weights are read straight out of the APK, and a compressed asset would have
        // to be inflated into a heap buffer before ORT could open it. Costs nothing on download
        // size: quantised weights barely compress.
        noCompress += "onnx"
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":library:ml"))
}
