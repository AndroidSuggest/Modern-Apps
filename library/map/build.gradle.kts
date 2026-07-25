plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(libs.androidx.compose.foundation)

    // Tile fetching + memory/disk cache.
    implementation(libs.coil.compose)

    // Pure-Kotlin coordinate types (Position / BoundingBox) shared with the apps.
    api(libs.spatialk.geojson)

    testImplementation("junit:junit:4.13.2")
}
