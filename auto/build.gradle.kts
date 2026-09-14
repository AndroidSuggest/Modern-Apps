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
    // Car App Library host: CarAppHost binds the :maps car-app service and
    // implements the host binders (ICarHost/IAppHost/INavigationHost/
    // IConstraintHost) so the nav card renders whatever Maps publishes --
    // its own SurfaceContainer surface plus its NavigationTemplate -- instead
    // of MA Auto re-rendering a second map. Same 1.4.0 the maps app uses.
    implementation(libs.androidx.car.app)
}
