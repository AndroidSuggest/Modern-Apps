plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))
    implementation(project(":library:network"))

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // SVG rendering is now internal via Android stdlib (Canvas/Path/Paint/XmlPullParser) – no third-party

    // Foundation needed for Image composable in AsyncImage
    implementation(libs.androidx.compose.foundation)
}
