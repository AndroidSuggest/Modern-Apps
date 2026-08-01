package com.vayunmathur.appstore.data.security

import android.util.Log
import com.android.apksig.SourceStampVerifier
import java.io.File
import java.security.cert.X509Certificate

/**
 * Reads an APK's **source stamp**.
 *
 * A source stamp is a signature block distinct from the APK signing block, carrying its
 * own certificate (`META-INF/stamp-cert-sha256` plus a v1/v2 stamp block). It matters
 * here because it **survives Play App Signing re-signing**: Google replaces the APK
 * signing key with its own, but the stamp identity persists across updates.
 *
 * That makes the stamp the only stable per-package identity available for Play apps.
 * It is worth being precise about what pinning it buys: it gives **continuity, not
 * provenance**. A change of stamp is detectable; who holds the stamp key for any given
 * Play-App-Signing app is not something the device can determine. For F-Droid and Modern
 * Apps the APK signing key itself is pinned, so the stamp is redundant there.
 *
 * Verified empirically against the Play Store's own APK, which reports
 * `Verified for SourceStamp: true` with a stamp certificate distinct from its signer.
 */
object SourceStamp {

    /**
     * @param current fingerprint of the stamp certificate this APK carries
     * @param lineage every fingerprint in the stamp's rotation lineage, including
     *   [current]. A stamp key may be rotated legitimately, and the lineage is what
     *   proves the new key descends from the pinned one.
     */
    data class Stamp(val current: String, val lineage: Set<String>)

    /** Verified source stamp of [apk], or null if it carries none. */
    fun of(apk: File): Stamp? = try {
        val result = SourceStampVerifier.Builder(apk)
            // Match the platform's own verification range. Below 24 the stamp uses the
            // v1 scheme, which apksig handles through the same entry point.
            .setMinCheckedPlatformVersion(24)
            .build()
            .verifySourceStamp()

        val info = result.sourceStampInfo
        if (!result.isVerified || info == null) {
            null
        } else {
            val current = fingerprint(info.certificate)
            val lineage = info.certificatesInLineage.orEmpty()
                .mapNotNull(::fingerprint)
                .toMutableSet()
            if (current != null) lineage += current
            if (current == null) null else Stamp(current, lineage)
        }
    } catch (t: Throwable) {
        // An absent stamp is normal and not an error; a malformed one is treated the
        // same as absent, and the caller decides whether absence is acceptable.
        Log.d(TAG, "No verifiable source stamp on ${apk.name}: ${t.message}")
        null
    }

    private fun fingerprint(cert: X509Certificate?): String? =
        cert?.let { runCatching { ApkCertificates.sha256(it.encoded) }.getOrNull() }

    private const val TAG = "SourceStamp"
}
