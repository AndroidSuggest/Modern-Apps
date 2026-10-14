plugins {
    id("common-conventions-library")
}

dependencies {
    // Android-only networking – HttpURLConnection + own WS for most paths; pooled OkHttp
    // behind NetworkClient.execute only (issue 815 range fetches).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.brotli.dec) // manual br decoding – keep catalog entry
    implementation(libs.kotlinx.coroutines.android)
    // Pooled engine for NetworkClient.execute (issue 815): connection reuse + HTTP/2 at
    // MapLibre's 20/host concurrency for the JNI range-fetch path. Nothing else moves.
    implementation(libs.okhttp)
}
