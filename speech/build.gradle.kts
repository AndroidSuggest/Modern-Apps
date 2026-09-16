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
        // Load-bearing, not an optimisation. The LiteRT ship rungs are read straight out
        // of the APK, and a compressed asset would have to be inflated into a heap buffer
        // before the runtime could open it. Quantised weights barely deflate, so it costs
        // nothing on download size.
        noCompress += "tflite"
        // The ExecuTorch candidates stage the same way: ExecutorchSessions copies a `.pte`
        // out of the APK before Module.load, so it must not be deflated either.
        noCompress += "pte"
    }
}
dependencies {
    // Speech-to-text is whisper-base, ExecuTorch-first with a LiteRT fallback (raw PCM at
    // the Vulkan `.pte` when present, else log-mel plus the int8 rung). Text-to-speech is
    // Supertonic 3, ExecuTorch-first with a LiteRT fallback the same way. The decode and
    // sampler loops live in the handles. Both handles live in :library:ml, which also
    // carries the inference runtimes.
    implementation(project(":library:ml"))
    // Runtime model download (mirror-hosted, SHA-256 pinned) — the Supertonic LiteRT
    // bundle and the Whisper export.
    implementation(project(":library:downloadservice"))
}
