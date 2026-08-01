plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "folder"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    // Zip/unzip workers now use java.io.File + java.util.zip – no okio needed
    implementation(libs.androidx.work.runtime.ktx)
}
