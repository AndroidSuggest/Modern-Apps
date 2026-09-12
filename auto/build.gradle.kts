plugins {
    id("common-conventions-app")
    id("common-conventions-preview-metadata")
}
launcherIcon {
    symbol = "directions_car"
}
android {
    defaultConfig {
        applicationId = "com.vayunmathur.auto"
    }
}
dependencies {
    // The GAL wire format. Nothing in the app re-implements it, and keeping it out of here is
    // what lets the handshake be tested on the JVM.
    implementation(project(":auto:protocol"))
    // Media3 controller for the Phase 4 now-playing feed from the on-device media session.
    implementation(libs.androidx.media3.session)
}
