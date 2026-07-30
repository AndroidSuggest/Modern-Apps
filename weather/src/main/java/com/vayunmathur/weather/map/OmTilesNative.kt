package com.vayunmathur.weather.map

/**
 * JNI bridge to the native Rust `.om` decoder (`libweather_om.so`, built from
 * `weather/rust/`). Loads the library once; [isAvailable] is false if the
 * native lib is missing for the current ABI so callers can degrade gracefully
 * instead of crashing.
 */
object OmTilesNative {

    val isAvailable: Boolean =
        try {
            System.loadLibrary("weather_om")
            android.util.Log.i("OmMap", "libweather_om loaded")
            true
        } catch (t: Throwable) {
            android.util.Log.e("OmMap", "System.loadLibrary(weather_om) failed", t)
            false
        }

    /**
     * Decode [variable] from the `.om` file at [omUrl] over the bounding box
     * [west]/[south]/[east]/[north], resampling into an [outW] × [outH] raster.
     *
     * Restored efficient path (fixed crash): Rust no longer embeds `ureq`
     * (~90 crates) for HTTP – it now calls back to
     * `OmRangeFetcher.getFileSize` / `fetchRange` via JNI (HttpURLConnection
     * + 64KB block cache + LRU of 12 files). Only covering chunks are fetched,
     * avoiding OOM from full 148 MB file downloads that the `decodeRegionBytes`
     * experiment caused.
     *
     * Blocking; call off the main thread. Returns null on any error so callers
     * degrade gracefully instead of crashing.
     */
    external fun decodeRegion(
        omUrl: String,
        variable: String,
        nx: Int,
        ny: Int,
        lonMin: Double,
        latMin: Double,
        dx: Double,
        dy: Double,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        outW: Int,
        outH: Int,
    ): FloatArray?
}
