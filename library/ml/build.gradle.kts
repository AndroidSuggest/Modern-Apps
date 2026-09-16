plugins {
    id("common-conventions-library")
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

dependencies {
    // On-device inference executor: full ONNX Runtime (the reduced 1.27.0-r1 rebuild lacks
    // Conv(11) and other vision ops; it returns as -r2 with the full op set).
    implementation(libs.onnxruntime.android)
    // LiteRT (TFLite) CompiledModel API for the quantized ladder ship rungs.
    implementation(libs.litert.android)
    // ExecuTorch Module API: custom Vulkan+XNNPACK AAR built from source
    // (pytorch/executorch v1.4.0, EXECUTORCH_BUILD_VULKAN=ON, arm64+x86_64),
    // checked in at library/ml/libs/. Replaces the stock executorch-android:1.4.0
    // from Maven (XNNPACK-only — verified zero Vulkan refs in its .so). File deps
    // carry no POM, so the AAR's runtime deps are declared explicitly below.
    implementation(files("libs/executorch-vulkan-1.4.0.aar"))
    implementation(libs.fbjni)
    implementation(libs.soloader.nativeloader)
}

rustNativeLib("ml_vulkan", "ml", features = listOf("vulkan"))
