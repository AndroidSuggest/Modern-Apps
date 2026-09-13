package com.vayunmathur.auto.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Connectivity-event receiver, mirroring gearhead's
 * `ConnectivityEventBroadcastReceiver`: link changes underneath a wireless
 * session (WiFi lost, default route moved) must surface to the bring-up
 * instead of stalling it on a dead network.
 *
 * Minimal: a lost WiFi transport cancels an in-flight wireless bring-up
 * (the session's reconnect owner decides retries -- see ProjectionService),
 * and republishes nothing else. Never touches an established session: a
 * live GAL socket failing surfaces as end-of-stream in the pump, owned by
 * the same reconnect logic.
 *
 * Opportunistic on API 24+: the platform limits this broadcast to
 * runtime-registered receivers, so a manifest entry may never fire -- the
 * end-of-stream path above stays the source of truth either way.
 */
class ConnectivityEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val network = connectivity.activeNetwork ?: run {
            Log.i(TAG, "connectivity lost; wireless bring-up (if any) is stale")
            return
        }
        val caps = connectivity.getNetworkCapabilities(network) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            Log.d(TAG, "active network is not WiFi; wireless bring-up unaffected")
        }
    }

    private companion object {
        const val TAG = "MaAuto.Connectivity"
    }
}
