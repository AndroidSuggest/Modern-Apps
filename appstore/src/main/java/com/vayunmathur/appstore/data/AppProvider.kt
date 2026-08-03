package com.vayunmathur.appstore.data

/**
 * Rules that apply to every listing, whichever source produced it.
 *
 * This used to be an interface that each source implemented — `fetchAll`, `search`,
 * `isPresent`, `getDetails` — with the ViewModel picking between three implementations at
 * every call site. All reads now go through [CatalogRepository] (offline) or
 * [com.vayunmathur.appstore.data.play.PlayRepository] (live), so the sources are left with
 * one job each: fetch a catalogue and write it to the cache table. What survived is the
 * part that was genuinely shared.
 */
object AppProvider {

    /**
     * The oldest Android an app may be built for and still be listed.
     *
     * Enforced twice: here, against the target SDK the source *claims*, and again at
     * install time against the manifest actually downloaded (see
     * [com.vayunmathur.appstore.data.security.InstallVerifier]), because a source may
     * claim nothing at all.
     */
    const val MIN_TARGET_SDK = 35

    fun filterTargetSdk(apps: List<UnifiedApp>): List<UnifiedApp> =
        apps.filter { app -> app.targetSdk == null || app.targetSdk >= MIN_TARGET_SDK }
}
