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
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.speech"
    }
    packaging {
        jniLibs {
            // Both native AARs (ncnn, sherpa-onnx) may ship libc++_shared.so; take one
            // rather than failing the merge with a duplicate.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    // Offline speech-to-text runs in the ncnn AAR (Whisper, com.vayunmathur.ncnn.Whisper);
    // the whisper-tiny model is downloaded at runtime from the mirror (WhisperModel.FILES)
    // and loaded from the filesystem via Whisper(dirPath).
    implementation(libs.ncnn.android)

    // Offline text-to-speech (Piper/VITS) runs in the sherpa-onnx AAR
    // (com.k2fsa.sherpa.onnx.OfflineTts). The AAR bundles its own libonnxruntime.so, so
    // this module must NOT also depend on onnxruntime-android (duplicate .so). Vendor it
    // with scripts/speech/fetch_sherpa_onnx.sh. The Piper voice model is downloaded at
    // runtime (PiperModel) — only the native code ships in the APK.
    implementation(":sherpa-onnx@aar")

    // Runtime model download (mirror-hosted, SHA-256 pinned) — same infra as Translate.
    implementation(project(":library:downloadservice"))
    implementation(libs.androidx.datastore.preferences)
}
