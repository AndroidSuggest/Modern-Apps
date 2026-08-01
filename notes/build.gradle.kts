plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "subject"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.notes"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:ink"))
    implementation(project(":library:image"))
}