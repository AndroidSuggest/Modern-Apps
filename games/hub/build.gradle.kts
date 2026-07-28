plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "stadia_controller"
}

android {
    defaultConfig {
        versionCode = 20260728
        versionName = "v2.6.3"
        applicationId = "com.vayunmathur.games.hub"
    }
}

dependencies {
    implementation(project(":library"))
    implementation(project(":library:ui"))
    implementation(project(":library:room"))
    implementation(project(":sdk:games"))
    implementRoom(libs)
    implementation("androidx.compose.material:material-icons-extended")
}
