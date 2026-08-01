plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "science"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.games.alchemist"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
