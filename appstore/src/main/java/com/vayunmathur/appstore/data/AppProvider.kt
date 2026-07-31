package com.vayunmathur.appstore.data

/**
 * Generic app store source — F-Droid (Droid-ify style) or Play Store (Aurora style).
 * The app registers 2 providers in order: fdroid base repo + play store.
 * [isPresent] is used for installed filtering: check F-Droid first, then Play,
 * if none — hide from installed list.
 */
interface AppProvider {
    val id: String
    val name: String
    val source: AppSource

    /** All apps available from this provider (browse / sync). Must filter targetSdk < 35. */
    suspend fun fetchAll(): List<UnifiedApp>

    /** Search within this provider. */
    suspend fun search(query: String): List<UnifiedApp>

    /** Presence check for installed filtering: true if package exists in this store. */
    suspend fun isPresent(packageName: String): Boolean

    /** Detailed listing for a package, or null. */
    suspend fun getDetails(packageName: String): UnifiedApp?

    companion object {
        const val MIN_TARGET_SDK = 35

        /** Keep only apps targeting >= MIN_TARGET_SDK, or unknown targetSdk (can't verify). */
        fun filterTargetSdk(apps: List<UnifiedApp>): List<UnifiedApp> {
            return apps.filter { app -> app.targetSdk == null || app.targetSdk >= MIN_TARGET_SDK }
        }
    }
}
