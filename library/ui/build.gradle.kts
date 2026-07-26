plugins {
    id("common-conventions-library")
}

dependencies {
    implementation(project(":library"))

    // Expose Material 3 to consumers via `api` so the same-named wrappers/aliases in this
    // module compile in app code. Apps depend only on `:library:ui` and must not import
    // `androidx.compose.material*` directly (enforced by convention + repo grep check).
    api(libs.androidx.compose.material3)
    // NavigationSuiteScaffold / currentWindowAdaptiveInfo used (via the facade) by openassistant.
    api(libs.androidx.compose.material3.adaptive.navigation.suite)
    // Used only internally (editor toolbars); not re-exported to apps.
    implementation("androidx.compose.material:material-icons-extended")

}
