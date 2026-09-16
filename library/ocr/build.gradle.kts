plugins {
    id("common-conventions-library")
}

android {
    androidResources {
        // The PP-OCR `.tflite` pair is read straight out of the APK. Uncompressed entries
        // avoid an inflate into a heap buffer before LiteRT opens them. The ExecuTorch
        // `.pte` candidate stages the same way: ExecutorchSessions copies it out of the
        // APK before Module.load, so it must not be deflated either (mirrors `:camera`).
        noCompress += "tflite"
        noCompress += "pte"
    }
}
dependencies {
    // On-device OCR via PP-OCRv6: the LiteRT `.tflite` pair (`TextRecognizer`) with an
    // ExecuTorch-first multifunction `.pte` (`TextRecognizerEt`) resolving
    // opportunistically from the same assets; consumers only see the OcrEngine API.
    //
    // `implementation` is enough even though `:library:ml` supplies `libmodelrunner.so`:
    // an Android library's jniLibs are packaged into the consuming APK transitively, and
    // OcrEngine maps `RecognizedLine` into its own TextBox rather than re-exporting it.
    implementation(project(":library:ml"))
    implementation(libs.kotlinx.coroutines.android)
}
