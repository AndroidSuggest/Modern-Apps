plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "photo_library"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.photos"
    }
    androidResources {
        // Face (SCRFD), embedding (MobileFaceNet), segmentation (U²-Net) and OCR
        // all come from the generalist ncnn-android AAR; photos does not use the
        // portrait (erdnet) model, so strip it from this APK.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:erdnet.*"
    }
    packaging {
        jniLibs {
            pickFirsts.add("**/libc++_shared.so")
        }
    }
}

metadataScreenshots {
    permissions.addAll(
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.ACCESS_MEDIA_LOCATION",
    )
    appops.add("MANAGE_MEDIA")
}

dependencies {
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.fragment.ktx)
    implementation(project(":library:map"))
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.exifinterface)
    // On-device face detection (SCRFD), face embedding (MobileFaceNet) and
    // subject segmentation (U²-Net) run on ncnn via the generalist AAR (BSD-3,
    // no ONNX Runtime / Play Services / MediaPipe). OCR uses it via :library:ocr.
    implementation(libs.ncnn.android)

    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:ink"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)


    implementation(project(":library:widgets"))
    implementation(project(":library:biometric"))
    implementation(project(":library:ocr"))
    // Semantic photo search now delegates image/text embedding to the
    // OpenAssistant app via this thin cross-app client (no on-device CLIP).
    implementation(project(":sdk:openassistant"))

    // The metadata screenshot generator writes EXIF GPS into seeded JPEGs.
    androidTestImplementation(libs.androidx.exifinterface)
}
