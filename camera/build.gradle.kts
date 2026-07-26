plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "photo_camera"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.camera"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native panorama stitcher + night burst aligner (Rust). See camera/src/main/rust/.
rustNativeLib("camera_stitch", "camera")

dependencies {
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)
    // On-device portrait segmentation via ncnn (Tencent, BSD-3, CPU-only), forked AAR.
    implementation(libs.ncnn.android)
}
