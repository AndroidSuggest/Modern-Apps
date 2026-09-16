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
}

rustNativeLib("ml_vulkan", "ml", features = listOf("vulkan"))
