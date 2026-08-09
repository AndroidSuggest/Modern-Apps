package com.vayunmathur.appstore.data

/**
 * The GrapheneOS "Sandboxed Google Play" bundle: Google's own Play components, installed as
 * ordinary unprivileged user apps rather than as a privileged system blob.
 *
 * The gmscompat shim that lets these run without the privileged access the real Play
 * client expects lives in the OS, not here — so all this store has to do is install the
 * three packages. It does that through the same anonymous-Play download and user-confirmed
 * [android.content.pm.PackageInstaller] path every other Play listing uses (see
 * [com.vayunmathur.appstore.data.installer.InstallCoordinator]); there is no dedicated
 * release server to point at and nothing to hardcode.
 *
 * Order matters at install. Google Services Framework and Play Services provide the
 * accounts, the GSF ID and the provider the store front-end talks to, so they go on before
 * Vending — the same order GrapheneOS installs them in.
 */
object SandboxedGooglePlay {
    /** Google Services Framework — the account/provider layer the rest builds on. */
    const val GSF = "com.google.android.gsf"

    /** Google Play Services — the bulk of the compatibility surface apps call into. */
    const val GMS = "com.google.android.gms"

    /** Google Play Store (Vending) — the store client, installed last. */
    const val VENDING = "com.android.vending"

    /** Stable id for the curated home section, shared by the ViewModel and the home screen. */
    const val SECTION_ID = "sandboxed-google-play"

    /** Human-readable names, used before a Play listing has been fetched (or if it can't be). */
    val DISPLAY_NAMES: Map<String, String> = mapOf(
        GSF to "Google Services Framework",
        GMS to "Google Play Services",
        VENDING to "Google Play Store",
    )

    /**
     * Install order: framework and services first, the store that depends on them last.
     * The curated section and the ordered install both read this, so the two never drift.
     */
    val PACKAGES: List<String> = listOf(GSF, GMS, VENDING)

    /**
     * Minimal stand-in listings for the three packages.
     *
     * The home section shows these immediately; when Play is reachable the ViewModel
     * replaces them with real listings (icon, size, rating) fetched by package name. They
     * carry [AppSource.PLAYSTORE] so an install routes down the existing Play path.
     */
    fun placeholders(): List<UnifiedApp> = PACKAGES.map { pkg ->
        UnifiedApp(
            packageName = pkg,
            source = AppSource.PLAYSTORE,
            name = DISPLAY_NAMES[pkg] ?: pkg,
            author = "Google LLC",
        )
    }
}
