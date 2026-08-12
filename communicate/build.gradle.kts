plugins {
    id("common-conventions-app")
}

launcherIcon {
    symbol = "call"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.communicate"
    }
}

dependencies {
    // Google Voice virtual line: protojson RPCs + SIP-over-WSS transport go through
    // the repo's own Android-only HTTP/WebSocket stack (no OkHttp/Ktor).
    implementation(project(":library:network"))
    // Maintained prebuilt WebRTC (org.webrtc.*) for the calling audio session.
    implementation(libs.stream.webrtc.android)
    // E.164 normalization to reconcile SIM vs Google Voice numbers.
    implementation(libs.libphonenumber)
    // Persist the Google Voice session (cookies, API key, authuser, GV number).
    implementation(libs.androidx.datastore.preferences)
    // Document-start JS injection to hook fetch/XHR before the GV web app captures them.
    implementation(libs.androidx.webkit)
}
