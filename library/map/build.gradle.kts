plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(libs.androidx.compose.foundation)

    // Tile fetching + memory/disk cache.
    implementation(libs.coil.compose)

    // Own GeoPoint/GeoBounds – no spatialk exposure for non-maplibre apps.
    // :maps gets spatialk transitively via maplibre-compose where required.
    // spatialk dependency fully removed from :library:map after migration.

}
