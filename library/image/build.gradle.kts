plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))
    implementation(project(":library:network"))

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // SVG rendering – replaces coil-svg
    implementation(libs.androidsvg)

    // Foundation needed for Image composable in AsyncImage
    implementation(libs.androidx.compose.foundation)
}
