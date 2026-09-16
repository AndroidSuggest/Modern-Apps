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
 * itself instead of silently zeroing the dashboard. Returns the post-attempt status so the
 * dashboard can tell "denied - needs a Settings visit" apart from "granted but empty".
 */
object UsageAccess {

    /** The usage-stats op state after [ensure] ran; denied means the user must grant it. */
    enum class Status { GRANTED, DENIED }

    fun ensure(context: Context): Status {
        val appOps = context.getSystemService<AppOpsManager>() ?: return Status.DENIED
        val uid = Process.myUid()
        val pkg = context.packageName
        val mode = runCatching {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, pkg)
        }.getOrElse {
            Log.w(TAG, "could not check the usage-stats op", it)
            return Status.DENIED
        }
        if (mode == AppOpsManager.MODE_ALLOWED) return Status.GRANTED
        val granted = runCatching {
            val setUidMode = AppOpsManager::class.java.getMethod(
                "setUidMode",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            setUidMode.invoke(appOps, AppOpsManager.OPSTR_GET_USAGE_STATS, uid, AppOpsManager.MODE_ALLOWED)
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, pkg)
        }.getOrElse {
            Log.w(TAG, "could not self-grant the usage-stats op", it)
            return Status.DENIED
        }
        return if (granted == AppOpsManager.MODE_ALLOWED) Status.GRANTED else Status.DENIED
    }

    /** True when the op is currently allowed; the cheap check half of [ensure]. */
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService<AppOpsManager>() ?: return false
        return runCatching {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
