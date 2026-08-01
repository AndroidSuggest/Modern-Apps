plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "travel"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.travel"
    }
}

dependencies {
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(project(":library:network"))

    implementation(project(":library:image"))
}
