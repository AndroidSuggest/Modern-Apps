package com.vayunmathur.networklocation.provider

import android.content.Context
import android.content.res.AssetFileDescriptor
import com.vayunmathur.networklocation.BeaconFix
import com.vayunmathur.networklocation.BeaconId
import com.vayunmathur.networklocation.WpsStoreNative
import java.io.IOException

/**
 * Offline beacon → coordinate resolver over the two bundled WPSDB stores
 * (`wifi.wpsdb`, `cells.wpsdb`), opened straight from their APK asset fds via
 * [WpsStoreNative] (native Rust reader, no copy to disk) — the same pattern as the
 * offline geocoder in `GeocodeService`.
 *
 * Degrades gracefully: if an asset is missing or the native library did not load, the
 * corresponding handle stays 0 and every lookup misses, so the provider falls back to
 * pure-online behaviour and a DB-less dev build still works.
 *
 * The 48-bit MAC packing and 64-bit cell-key packing here MUST stay byte-for-byte identical
 * to `wtfps-experiment/store.py` (`parse_mac` / `pack_cell`), which builds the stores — see
 * FORMAT.md "Cell key packing".
 */
class OfflineBeaconStore(context: Context) {
    private val appContext = context.applicationContext

    private var wifiAfd: AssetFileDescriptor? = null
    private var cellAfd: AssetFileDescriptor? = null
    private var wifiHandle = 0L
    private var cellHandle = 0L

    // Serializes lookups against close(): native close() frees the reader (Box::from_raw),
    // so it must not run while another thread is inside WpsStoreNative.lookup on that handle.
    private val lock = Any()

    init {
        if (WpsStoreNative.available) {
            wifiHandle = openAsset(WIFI_ASSET) { wifiAfd = it }
            cellHandle = openAsset(CELL_ASSET) { cellAfd = it }
        }
    }

    private fun openAsset(name: String, keep: (AssetFileDescriptor) -> Unit): Long = try {
        val fd = appContext.assets.openFd(name)
        keep(fd)
        WpsStoreNative.open(fd.parcelFileDescriptor.fd, fd.startOffset, fd.length)
    } catch (_: IOException) {
        0L
    }

    /** Resolve WiFi APs present in the offline store. Absent MACs are simply omitted. */
    fun resolveWifi(bssids: List<BeaconId.Wifi>): Map<BeaconId.Wifi, BeaconFix> {
        if (bssids.isEmpty()) return emptyMap()
        synchronized(lock) {
            if (wifiHandle == 0L) return emptyMap()
            val out = HashMap<BeaconId.Wifi, BeaconFix>()
            for (id in bssids) {
                val key = parseMac(id.bssid) ?: continue
                val r = WpsStoreNative.lookup(wifiHandle, key) ?: continue
                if (r.size >= 2) out[id] = BeaconFix(id, r[0], r[1], OFFLINE_ACCURACY_METERS)
            }
            return out
        }
    }

    /** Resolve cell towers present in the offline store. Absent towers are omitted. */
    fun resolveCell(cells: List<BeaconId.Cell>): Map<BeaconId.Cell, BeaconFix> {
        if (cells.isEmpty()) return emptyMap()
        synchronized(lock) {
            if (cellHandle == 0L) return emptyMap()
            val out = HashMap<BeaconId.Cell, BeaconFix>()
            for (id in cells) {
                val r = WpsStoreNative.lookup(cellHandle, packCell(id)) ?: continue
                if (r.size >= 2) out[id] = BeaconFix(id, r[0], r[1], OFFLINE_ACCURACY_METERS)
            }
            return out
        }
    }

    /**
     * Release both handles and their descriptors. Idempotent. Serialized with the resolvers so
     * it never frees a native reader that a concurrent lookup is still dereferencing.
     */
    fun close() {
        synchronized(lock) {
            if (wifiHandle != 0L) {
                WpsStoreNative.close(wifiHandle)
                wifiHandle = 0L
            }
            if (cellHandle != 0L) {
                WpsStoreNative.close(cellHandle)
                cellHandle = 0L
            }
            wifiAfd?.close()
            wifiAfd = null
            cellAfd?.close()
            cellAfd = null
        }
    }

    private companion object {
        const val WIFI_ASSET = "wifi.wpsdb"
        const val CELL_ASSET = "cells.wpsdb"

        // The stores quantize coordinates to a ~20 m global grid (see quantize.py); use a
        // fixed accuracy radius matching that precision for every offline fix.
        const val OFFLINE_ACCURACY_METERS = 20.0

        // Cell key packing — MUST match store.pack_cell:
        //   key = (mcc<<54) | (mnc<<44) | (tac<<28) | (cellId & 0x0FFFFFFF)
        //   mcc:10 | mnc:10 | tac:16 | cellId:28  (= 64 bits)
        // 5G NCI cell ids (36-bit) are truncated to 28 bits; LTE/UMTS/GSM fit.
        const val CID_MASK = 0x0FFF_FFFFL

        fun packCell(id: BeaconId.Cell): Long =
            ((id.mcc.toLong() and 0x3FF) shl 54) or
                ((id.mnc.toLong() and 0x3FF) shl 44) or
                ((id.tacOrLac.toLong() and 0xFFFF) shl 28) or
                (id.cellId.toLong() and CID_MASK)

        /** Parse "aa:bb:cc:dd:ee:ff" to a 48-bit key (mirrors store.parse_mac); null if bad. */
        fun parseMac(bssid: String): Long? {
            val parts = bssid.split(":")
            if (parts.size != 6) return null
            var v = 0L
            for (p in parts) {
                val b = p.toIntOrNull(16) ?: return null
                if (b < 0 || b > 0xFF) return null
                v = (v shl 8) or b.toLong()
            }
            return v
        }
    }
}
