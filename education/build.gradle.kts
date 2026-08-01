plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "school"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.education"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))

    // Video playback (Khan/YouTube streaming via NewPipe + media3), like :youpipe.
    implementation(project(":youpipe:extractor"))
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose.material3)
    implementation(project(":library:network"))

}
