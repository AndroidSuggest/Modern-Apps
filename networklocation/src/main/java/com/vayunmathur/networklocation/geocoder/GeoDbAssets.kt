package com.vayunmathur.networklocation.geocoder

import android.content.Context
import java.io.FileInputStream
import java.io.IOException

/**
 * Opens the bundled geocoder database straight from the APK asset via its file descriptor —
 * no unzip, no copy to filesDir. Requires the asset to be stored uncompressed
 * (`androidResources { noCompress += "geodb" }`), which lets [android.content.res.AssetManager.openFd]
 * return a real fd + offset that we mmap.
 */
object GeoDbAssets {
    const val ASSET_NAME = "geocoder.geodb"

    /** Returns a reader, or null if the DB isn't bundled (e.g. a dev build without the fetch). */
    fun open(context: Context, assetName: String = ASSET_NAME): GeoDbReader? = try {
        val afd = context.assets.openFd(assetName)
        val channel = FileInputStream(afd.fileDescriptor).channel
        GeoDbReader(ChannelByteSource(channel, afd.startOffset, afd.length))
    } catch (_: IOException) {
        null
    }
}
