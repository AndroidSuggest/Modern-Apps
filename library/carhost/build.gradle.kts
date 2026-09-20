plugins {
    id("common-conventions-library")
}

android {
    lint {
        // The renderers/parsers intentionally read experimental car-app template
        // types (Chip, ConversationItem, InCall/TelephoneKeypad, MediaPlayback,
        // SectionedItem). Kotlin opt-in is handled via @file:OptIn; this Android
        // lint variant doesn't honor Kotlin @OptIn, so disable it module-wide
        // rather than scatter androidx.annotation.OptIn across every parser.
        disable += "UnsafeOptInUsageError"
    }
}

dependencies {
    // Renderers use library:ui components (Button/Card/ListItem/Text/TextButton/
    // OutlinedTextField/CircularProgressIndicator/MaterialTheme). Apps that render
    // car views in screenshot tests get these transitively.
    implementation(project(":library:ui"))

    // Car App Library: the HostTemplate model + parsers reference androidx.car.app.*
    // template types, and consumers (auto host, app screenshot tests) build real
    // Templates to feed the renderer, so expose it via `api`.
    api(libs.androidx.car.app)
}
