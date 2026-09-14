package com.vayunmathur.screentime.platform

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.util.Log
import androidx.core.content.getSystemService

private const val TAG = "ScreenTimeUsageAccess"

/**
 * Reads usage stats on our own behalf.
 *
 * `PACKAGE_USAGE_STATS` is `signature|privileged|development|appop|retailDemo`: declared in
 * the manifest but denied until the app-op is allowed. The dashboard cannot wait for the user
 * to find the Settings toggle, so - exactly like parental controls' `UsageAccess` - this sets
 * the op on our own uid. The privilege arrives with the `SYSTEM_WELLBEING` role; the
 * reflection buys visibility into the `@SystemApi` setter, not the privilege itself.
 *
 * Idempotent: called on every reconcile before anything reads usage, so a revoked op heals
 * itself instead of silently zeroing the dashboard.
 */
object UsageAccess {

    fun ensure(context: Context) {
        val appOps = context.getSystemService<AppOpsManager>() ?: return
        val uid = Process.myUid()
        val pkg = context.packageName
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, pkg)
        }.getOrElse {
            Log.w(TAG, "could not check the usage-stats op", it)
            return
        }
        if (mode == AppOpsManager.MODE_ALLOWED) return
        runCatching {
            val setUidMode = AppOpsManager::class.java.getMethod(
                "setUidMode",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            setUidMode.invoke(appOps, AppOpsManager.OPSTR_GET_USAGE_STATS, uid, AppOpsManager.MODE_ALLOWED)
        }.onFailure { Log.w(TAG, "could not self-grant the usage-stats op", it) }
    }
}
