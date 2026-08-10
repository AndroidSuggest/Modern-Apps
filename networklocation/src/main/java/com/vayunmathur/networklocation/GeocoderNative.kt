package com.vayunmathur.networklocation

/**
 * JNI bridge to the native offline geocoder search in the `networklocation` Rust library
 * (see networklocation/src/main/rust/src/geocoder.rs). The database itself is generated
 * offline by scripts/geocoder_gen.cpp and bundled uncompressed as the `geocoder.geodb` asset;
 * this object opens it straight from the APK asset fd (no copy to disk) and answers reverse /
 * forward geocoding queries entirely in Rust.
 *
 * Results are flat String arrays with [FIELDS_PER_ADDRESS] entries per address:
 * `[lat, lon, house, street, city, state, country, postcode]` (lat/lon formatted to 6 dp).
 *
 * The fully-qualified name MUST stay `com.vayunmathur.networklocation.GeocoderNative` so the
 * JNI symbol mangling (`Java_com_vayunmathur_networklocation_GeocoderNative_*`) matches.
 */
object GeocoderNative {
    /** Strings per address in the flat arrays returned by [reverse] / [forward]. */
    const val FIELDS_PER_ADDRESS = 8

    /** Whether the `.so` loaded. Guarded so host/unit contexts degrade gracefully. */
    val available: Boolean = runCatching { System.loadLibrary("networklocation") }.isSuccess

    /**
     * Open the geocoder DB from an APK asset file descriptor. [fd] is the (uncompressed) asset's
     * fd, [offset] its start offset within the APK; [length] is reserved for future validation.
     * Returns an opaque handle, or 0 on failure. The native side dups [fd], so the caller may
     * close its own descriptor after this returns.
     */
    external fun open(fd: Int, offset: Long, length: Long): Long

    /** Nearest stored address to (lat, lon): [FIELDS_PER_ADDRESS] strings, or null. */
    external fun reverse(handle: Long, lat: Double, lon: Double): Array<String>?

    /**
     * Addresses matching the components exactly. Returns a flat array of
     * `FIELDS_PER_ADDRESS * k` strings (k results, possibly empty), or null on error.
     */
    external fun forward(
        handle: Long,
        country: String,
        state: String,
        city: String,
        street: String,
        limit: Int,
    ): Array<String>?

    /** Free the handle and its underlying descriptor. Safe to call with 0. */
    external fun close(handle: Long)
}
