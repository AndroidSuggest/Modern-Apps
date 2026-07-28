plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "folder"
}

android {
    defaultConfig {
        versionCode = 20260728
        versionName = "v2.6.3"
        applicationId = "com.vayunmathur.files"
    }
}

dependencies {
    implementation(libs.okio) // isolated: zip/unzip workers, FileSystem facade – not networking
    implementation(libs.androidx.work.runtime.ktx)
}
