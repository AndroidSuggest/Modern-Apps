package com.vayunmathur.communicate.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Epoch bumped on activity resume (after a pause) so permission gates re-check
 * grants made outside the app — e.g. SMS granted from Settings, whose result
 * no permission launcher ever reports (#735). Read at the call site's scope so
 * the bump recomposes the gates even when the gated content isn't composed.
 * Scoped to the calling screen — no shared permissions-architecture change.
 */
@Composable
fun rememberResumeRevision(): Int {
    val context = LocalContext.current
    var resumeRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val app = context.applicationContext as? Application
        if (app == null) {
            onDispose {}
        } else {
            val callbacks = object : Application.ActivityLifecycleCallbacks {
                // Only resumes after a pause can reflect an out-of-app grant;
                // this also skips the initial onResume so callers don't load
                // twice at startup.
                var seenPause = false
                override fun onActivityResumed(activity: Activity) {
                    if (seenPause) resumeRevision++
                }
                override fun onActivityPaused(activity: Activity) {
                    seenPause = true
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
            app.registerActivityLifecycleCallbacks(callbacks)
            onDispose { app.unregisterActivityLifecycleCallbacks(callbacks) }
        }
    }
    return resumeRevision
}
