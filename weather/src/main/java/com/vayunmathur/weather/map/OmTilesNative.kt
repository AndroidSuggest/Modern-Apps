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
     * Returns a row-major `FloatArray` of length `outW * outH` with **row 0 =
     * north** (top of the image) and `NaN` where there is no data, or `null`
     * on any error (network, parse, unsupported variable). Grid geometry is
     * passed in from [OmDomain] so the native side stays model-agnostic.
     *
     * The derived measure `wind_speed_10m` is computed natively from the
     * `wind_u_component_10m` / `wind_v_component_10m` children.
     *
     * Blocking; call off the main thread.
     *
     * Deprecated: use [decodeRegionBytes] – Rust no longer does HTTP (ureq removed)
     * to cut ring/rustls/icu supply-chain. New path fetches .om bytes via
     * [com.vayunmathur.library.network.NetworkClient] (HttpURLConnection, armv8-only)
     * and passes them to Rust slice decoder.
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

    /**
     * New path: decode from in-memory `.om` bytes (fetched in Kotlin via
     * NetworkClient/OkHttp, armv8-only std stack). Eliminates `ureq` crate
     * (~100 crates including ring, rustls, webpki-roots, icu) from Rust.
     *
     * Returns row-major FloatArray length outW*outH, row 0 = north, NaN where no data,
     * or null on parse/unsupported.
     */
    external fun decodeRegionBytes(
        omData: ByteArray,
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
