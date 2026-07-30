plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "record_voice_over"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.speech"
    }
    androidResources {
        // Keep the bundled whisper model uncompressed so the ncnn AAR can open it by
        // file descriptor / mmap straight from the APK (compressed assets can't be fd-opened).
        noCompress += listOf("bin", "param", "txt")
    }
}

dependencies {
    // Offline speech-to-text runs in the ncnn AAR (Whisper, com.vayunmathur.ncnn.Whisper);
    // the multilingual whisper-tiny model is bundled in assets/whisper-tiny (fetch via
    // scripts/speech/fetch_whisper_model.sh) and loaded from the APK by AssetManager.
    implementation(libs.ncnn.android)
}
