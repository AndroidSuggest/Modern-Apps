plugins {
    id("common-conventions-library")
}

dependencies {
    // Android-only networking – HttpURLConnection + own WS, no OkHttp/Ktor.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.brotli.dec) // manual br decoding – keep catalog entry
    implementation(libs.kotlinx.coroutines.android)
}
