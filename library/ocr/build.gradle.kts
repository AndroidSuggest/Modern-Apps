plugins {
    id("common-conventions-library")
}

dependencies {
    // On-device OCR via PP-OCRv5 on ncnn (BSD-3, FOSS, no Play Services / no ML
    // Kit). The models + native pipeline ship inside this AAR; consumers only
    // see the OcrEngine API.
    implementation("com.github.vayun-mathur:ncnn-android:1.1.0")
    implementation(libs.kotlinx.coroutines.android)
}
