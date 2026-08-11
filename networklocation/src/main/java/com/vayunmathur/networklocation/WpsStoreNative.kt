package com.vayunmathur.networklocation

/**
 * JNI bridge to the native offline WPSDB reader in the `networklocation` Rust library
 * (see networklocation/src/main/rust/src/wpsdb.rs). It answers exact key → coordinate
 * lookups over a bundled `WPSDB1` store opened straight from an APK asset fd (no copy to
 * disk), and serves both offline stores the app ships:
 *   * `wifi.wpsdb`  — 48-bit MAC (BSSID) key → coord
 *   * `cells.wpsdb` — 64-bit packed cell key → coord
 *
 * A single generic reader handles both because the WPSDB1 Elias–Fano header carries the key
 * universe width; the key is always passed as a [Long] (a 48-bit MAC and a 64-bit packed
 * cell key both fit).
 *
 * The fully-qualified name MUST stay `com.vayunmathur.networklocation.WpsStoreNative` so the
 * JNI symbol mangling (`Java_com_vayunmathur_networklocation_WpsStoreNative_*`) matches.
 */
object WpsStoreNative {
    /** Whether the `.so` loaded. Guarded so host/unit contexts degrade gracefully. */
    val available: Boolean = runCatching { System.loadLibrary("networklocation") }.isSuccess

    /**
     * Open a WPSDB store from an APK asset file descriptor. [fd] is the (uncompressed) asset's
     * fd, [offset] its start offset within the APK; [length] is reserved for future validation.
     * Returns an opaque handle, or 0 on failure. The native side dups [fd], so the caller may
     * close its own descriptor after this returns.
     */
    external fun open(fd: Int, offset: Long, length: Long): Long

    /** Coordinate `[lat, lon]` for [key], or null if the store does not contain [key]. */
    external fun lookup(handle: Long, key: Long): DoubleArray?

    /** Free the handle and its underlying descriptor. Safe to call with 0. */
    external fun close(handle: Long)
}
