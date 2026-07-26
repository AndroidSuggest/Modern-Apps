plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "science"
}

android {
    defaultConfig {
        versionCode = 20260725
        versionName = "v2.6.2"
        applicationId = "com.vayunmathur.games.alchemist"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
