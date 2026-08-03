package com.vayunmathur.appstore.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * What is on the device, and which of the store's sources offers it.
 *
 * Published in two waves on purpose. The package list itself is one cheap PackageManager
 * sweep and goes out immediately; launcher icons cost a binder call and a bitmap decode
 * each, so several hundred of them arrive afterwards. Doing it the other way round is
 * what used to leave the Installed tab blank for ten seconds.
 */
class InstalledAppsRepository(private val context: Context) {

    private val _apps = MutableStateFlow<List<InstalledInfo>>(emptyList())
    val apps: StateFlow<List<InstalledInfo>> = _apps.asStateFlow()

    private val _icons = MutableStateFlow<Map<String, Drawable>>(emptyMap())
    val icons: StateFlow<Map<String, Drawable>> = _icons.asStateFlow()

    /** Re-read the device. Safe to call from `onResume`. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val userApps = try {
            pm.getInstalledApplications(PackageManager.MATCH_ALL)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        } catch (_: Exception) {
            emptyList<ApplicationInfo>()
        }

        _apps.value = userApps.mapNotNull { ai -> pm.readInstalledInfo(ai) }
            .sortedBy { it.name.lowercase() }

        _icons.value = userApps.mapNotNull { ai ->
            try {
                ai.packageName to pm.getApplicationIcon(ai.packageName)
            } catch (_: Exception) {
                null
            }
        }.toMap()
    }

    private fun PackageManager.readInstalledInfo(ai: ApplicationInfo): InstalledInfo? = try {
        val pi = getPackageInfo(ai.packageName, 0)
        InstalledInfo(
            packageName = ai.packageName,
            name = getApplicationLabel(ai).toString(),
            versionName = pi.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong(),
            lastUpdateTime = pi.lastUpdateTime,
        )
    } catch (_: Exception) {
        null
    }
}
