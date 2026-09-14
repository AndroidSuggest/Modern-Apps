plugins {
    id("common-conventions-library")
}

dependencies {
    // On-device inference executor: reduced-operator ONNX Runtime rebuild (arm64-only,
    // 10 MB native). All models run as `.onnx` through the handles in this module; the old
    // Vulkan `modelrunner` crate is deleted.
    implementation(libs.onnxruntime.reduced.android)
}
