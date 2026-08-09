package com.vayunmathur.networklocation.wifi

import android.content.Context
import android.net.wifi.WifiManager
import com.vayunmathur.networklocation.BeaconId

/**
 * Reads nearby WiFi access points from the platform's last scan. As a privileged
 * system app we can rely on the framework's ongoing scan results rather than
 * kicking (deprecated, rate-limited) scans ourselves.
 */
class NearbyWifi(context: Context) {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun scan(): List<BeaconId.Wifi> {
        val results = runCatching { wifiManager?.scanResults }.getOrNull() ?: return emptyList()
        return results
            .mapNotNull { it.BSSID }
            .filter { it.isNotBlank() && it != NULL_BSSID }
            .distinct()
            .map { BeaconId.Wifi(it) }
    }

    private companion object {
        // Some drivers report this placeholder for hidden/!associated entries.
        const val NULL_BSSID = "00:00:00:00:00:00"
    }
}
