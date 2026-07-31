package com.vayunmathur.appstore.data

/**
 * Generic abstraction for any app provider (F-Droid base repo, Play Store via Aurora anonymous).
 * App registers 2 providers in order: fdroid base repo + play store.
 * Installed presence check: F-Droid first -> Play Store -> if none, hide app from list.
 */
interface AppProvider {
    val id: String
    val name: String
    val source: AppSource

    /** Full catalog — must pre-filter targetSdk < MIN_TARGET_SDK. */
    suspend fun fetchAll(): List<UnifiedApp>

    /** Search within this provider. */
    suspend fun search(query: String): List<UnifiedApp>

    /** Presence check for installed filtering. */
    suspend fun isPresent(packageName: String): Boolean

    /** Detailed entry or null. */
    suspend fun getDetails(packageName: String): UnifiedApp?

    companion object {
        const val MIN_TARGET_SDK = 35

        fun filterTargetSdk(apps: List<UnifiedApp>): List<UnifiedApp> {
            return apps.filter { app -> app.targetSdk == null || app.targetSdk >= MIN_TARGET_SDK }
        }

        /** Resolve source for a package using ordered providers: fdroid first, then play. Returns null if absent in all. */
        suspend fun resolveSource(packageName: String, providers: List<AppProvider>): AppSource? {
            for (provider in providers) {
                if (provider.isPresent(packageName)) return provider.source
            }
            return null
        }
    }
}
