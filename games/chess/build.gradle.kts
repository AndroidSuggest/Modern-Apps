plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "chess"
}

android {
    defaultConfig {
        versionCode = 20260728
        versionName = "v2.6.3"
        applicationId = "com.vayunmathur.games.chess"
    }
    androidResources {
        noCompress += "nnue"
    }
}

dependencies {
    implementation(project(":sdk:games"))
    implementation(libs.stockfish.library)

}
