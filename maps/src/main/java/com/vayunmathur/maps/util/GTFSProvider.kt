package com.vayunmathur.maps.util

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

object GTFSProvider {
    // Accessed concurrently from OfflineRouter.getRoute (Dispatchers.Default)
    // and from MyMapLayers on the Main thread, so use a thread-safe map —
    // a plain HashMap mutation race can corrupt internal buckets and cause
    // ConcurrentModificationException or infinite get() loops.
    private val routeColors = ConcurrentHashMap<String, String>() // Key: feedName:routeName, Value: #HEX

    fun getRouteColor(context: Context, feedName: String, routeName: String): String? {
        val cacheKey = "$feedName:$routeName"
        if (routeColors.containsKey(cacheKey)) return routeColors[cacheKey]

        try {
            val assetPath = "$feedName/routes.txt"
            context.assets.open(assetPath).use { inputStream ->
                val reader = inputStream.bufferedReader()
                val header = reader.readLine()?.split(",") ?: return null
                val shortNameIdx = header.indexOf("route_short_name")
                val longNameIdx = header.indexOf("route_long_name")
                val colorIdx = header.indexOf("route_color")

                if (colorIdx == -1) return null

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(",")
                    val shortName = if (shortNameIdx != -1) parts.getOrNull(shortNameIdx) else null
                    val longName = if (longNameIdx != -1) parts.getOrNull(longNameIdx) else null

                    if (shortName == routeName || longName == routeName) {
                        val color = parts.getOrNull(colorIdx)
                        if (!color.isNullOrEmpty()) {
                            val fullColor = "#$color"
                            routeColors[cacheKey] = fullColor
                            return fullColor
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Asset not found or read error
        }
        return null
    }
}
