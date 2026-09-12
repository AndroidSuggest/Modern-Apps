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
}
