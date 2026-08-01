plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "stadia_controller"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.games.hub"
    }
}

dependencies {
    implementation(project(":library"))
    implementation(project(":library:ui"))
    implementation(project(":library:room"))
    implementation(project(":sdk:games"))
    implementRoom(libs)
}
