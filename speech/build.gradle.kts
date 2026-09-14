plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:speech:metadata` task name either way.
    id("common-conventions-preview-metadata")
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
        // Load-bearing, not an optimisation. The two whisper-base int8 ONNX exports are read
        // straight out of the APK, and a compressed asset would have to be inflated into a
        // heap buffer before ORT could open it. Int8 weights barely deflate, so it costs
        // nothing on download size. (The remaining `maml` entry covers the Supertonic and
        // legacy bundles until Phase 4 deletes them.)
        noCompress += "onnx"
    }
}
dependencies {
    // Speech-to-text is whisper-base on the reduced ONNX Runtime build (two int8 exports in
    // `assets/whisper-base/`, decode loop in WhisperHandle). Text-to-speech is Supertonic 3
    // on the same build (four upstream exports, sampler loop in SupertonicSynthesizer).
    // Both handles live in :library:ml, which also carries the ORT dependency.
    implementation(project(":library:ml"))
    // No `:library:downloadservice` and no DataStore: both models ship in the APK, so this app
    // downloads nothing and stores no preferences. They went when Piper's 1,834 MB of voices did.
}
