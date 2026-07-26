plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "picture_as_pdf"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.pdf"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native memory-safe PDF renderer (Rust + lopdf). See pdf/src/main/rust/.
rustNativeLib("pdf_render", "pdf")

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.coil.compose)
    implementation(project(":library:ocr"))
}
