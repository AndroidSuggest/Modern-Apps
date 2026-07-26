plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "playing_cards"
}

android {
    defaultConfig {
        versionCode = 20260725
        versionName = "v2.6.2"
        applicationId = "com.vayunmathur.games.solitaire"
    }
}

dependencies {
    implementation(project(":sdk:games"))
}
