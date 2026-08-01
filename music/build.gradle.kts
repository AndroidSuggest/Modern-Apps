plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "music_note"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.music"
    }
}

metadataScreenshots {
    permissions.add("android.permission.READ_MEDIA_AUDIO")
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)

    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(project(":library:image"))
}