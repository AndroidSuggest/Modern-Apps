plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(libs.androidx.compose.foundation)

    // Tile fetching + memory/disk cache via library:image (replaces coil).
    implementation(project(":library:image"))

    // Own GeoPoint/GeoBounds – no spatialk exposure for non-maplibre apps.
    // :maps gets spatialk transitively via maplibre-compose where required.
    // spatialk dependency fully removed from :library:map after migration.

}
