package com.vayunmathur.appstore.data

/**
 * The GrapheneOS "Sandboxed Google Play" bundle: Google's own Play components, installed as
 * ordinary unprivileged user apps rather than as a privileged system blob.
 *
 * These three packages are **not** installable from Google Play, so the store never fetches
 * them through the anonymous-Play path. They come from GrapheneOS's app release server
 * (apps.grapheneos.org), which re-hosts Google's official signed APKs alongside signed repo
 * metadata. Installing them reuses the store's ordinary signed-APK download and
 * user-confirmed [android.content.pm.PackageInstaller] flow — the same
 * [com.vayunmathur.appstore.data.installer.InstallCoordinator] `installFromUrl` path every
 * F-Droid / Modern Apps listing uses, and the same mechanism GrapheneOS's own Apps client
 * uses for Sandboxed Google Play. The gmscompat shim that lets these run without the
 * privileged access the real Play client expects lives in the OS, not here.
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

    /**
     * GrapheneOS's app release server. The three packages, and the signed metadata that
     * verifies them, are served from here — nothing is fetched from Google Play.
     */
    const val RELEASE_SERVER = "https://apps.grapheneos.org"

    /** Human-readable names, shown before richer metadata has been fetched. */
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

    /** The GrapheneOS release-server download URL for [pkg]'s signed APK. */
    fun apkUrlFor(pkg: String): String = "$RELEASE_SERVER/packages/$pkg.apk"

    /**
     * Stand-in listings for the three packages.
     *
     * The home section shows these immediately. They carry [AppSource.GRAPHENEOS] and the
     * GrapheneOS release-server [UnifiedApp.apkUrl], so tapping install routes down the
     * signed-APK download + [android.content.pm.PackageInstaller] path against
     * apps.grapheneos.org — never the Play path. A catalogue sync that has cached richer
     * rows (icon, size, signer, hash) replaces these in the ViewModel.
     */
    fun placeholders(): List<UnifiedApp> = PACKAGES.map { pkg ->
        UnifiedApp(
            packageName = pkg,
            source = AppSource.GRAPHENEOS,
            name = DISPLAY_NAMES[pkg] ?: pkg,
            author = "Google LLC",
            apkUrl = apkUrlFor(pkg),
            repoUrl = RELEASE_SERVER,
        )
    }
}
