plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "ecg_heart"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.health"
    }
}

dependencies {
    // healthconnect
    implementation(libs.androidx.connect.client)

    implementation(libs.androidx.work.runtime.ktx)

    // room
    implementRoom(libs)
    implementation(project(":library:room"))

    implementation(project(":library:network"))
}