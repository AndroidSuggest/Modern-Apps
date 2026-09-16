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
        // The ExecuTorch Vulkan model is staged the same way: ExecutorchSessions
        // copies it out of the APK before Module.load, so it must not be deflated.
        // Quantised weights barely compress, so this costs nothing on download size.
        noCompress += "pte"
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(project(":library:ml"))
}
