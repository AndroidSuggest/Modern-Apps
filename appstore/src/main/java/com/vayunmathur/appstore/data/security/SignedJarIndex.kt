package com.vayunmathur.appstore.data.security

import java.io.File
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/**
 * Verifies an F-Droid repository index, which ships as a JAR signed with the repo's key
 * (`entry.jar` for index-v2, `index-v1.jar` for the legacy format).
 *
 * This is the check that makes everything downstream mean anything: the per-APK SHA-256
 * and `signer` fingerprints only bind the APK to the index, so an index we haven't
 * authenticated could simply state a hostile APK's own hash and key. Pinning the index
 * signing certificate is what stops a malicious mirror from rewriting both sides.
 *
 * [JarFile] with verification enabled checks the PKCS#7 signature block against the
 * per-entry digests in `META-INF/MANIFEST.MF` as entries are read — but only for entries
 * that are actually consumed, and an unsigned entry simply reports no certificates. Hence
 * [readVerified] reads the entry to completion first and treats a missing certificate as
 * a failure rather than as "unsigned but fine".
 */
object SignedJarIndex {

    class VerificationException(message: String) : Exception(message)

    data class Verified(
        val content: ByteArray,
        /** Lowercase-hex SHA-256 of the signing certificate. */
        val signerSha256: String,
    )

    /**
     * Read [entryName] out of the signed [jar], enforcing that it is covered by the JAR
     * signature. When [pinnedFingerprint] is non-null the signing certificate must match
     * it; when null the caller is doing trust-on-first-use and should persist the
     * returned [Verified.signerSha256].
     */
    fun readVerified(jar: File, entryName: String, pinnedFingerprint: String?): Verified {
        var content = ByteArray(0)
        val fingerprint = consumeVerified(jar, entryName, pinnedFingerprint) { input ->
            content = input.readBytes()
        }
        return Verified(content, fingerprint)
    }

    /**
     * Streaming form of [readVerified] for entries too large to hold in memory — the
     * full F-Droid `index-v1.json` runs to hundreds of megabytes. Writes the entry to
     * [dest] and returns the signing certificate fingerprint. [dest] is deleted if
     * verification fails, so a rejected index is never left behind to be parsed.
     */
    fun extractVerified(
        jar: File,
        entryName: String,
        pinnedFingerprint: String?,
        dest: File,
    ): String = try {
        consumeVerified(jar, entryName, pinnedFingerprint) { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
    } catch (t: Throwable) {
        dest.delete()
        throw t
    }

    private fun consumeVerified(
        jar: File,
        entryName: String,
        pinnedFingerprint: String?,
        consume: (java.io.InputStream) -> Unit,
    ): String {
        JarFile(jar, true).use { jarFile ->
            if (jarFile.manifest == null) {
                throw VerificationException("Index JAR has no manifest — it is not signed")
            }
            val entry = jarFile.getJarEntry(entryName)
                ?: throw VerificationException("Index JAR does not contain $entryName")

            // Certificates are only attached once the entry has been read to completion
            // and its digest checked, so this read *is* the verification step, not just IO.
            jarFile.getInputStream(entry).use(consume)

            val certificates = entry.certificates
                ?: throw VerificationException("$entryName is not covered by the JAR signature")
            val signer = certificates.filterIsInstance<X509Certificate>().firstOrNull()
                ?: throw VerificationException("$entryName has no X.509 signer")

            val fingerprint = ApkCertificates.sha256(signer.encoded)
            if (pinnedFingerprint != null && !fingerprint.equals(pinnedFingerprint, true)) {
                throw VerificationException(
                    "Repository signing key changed: pinned " +
                        "${ApkCertificates.abbreviate(pinnedFingerprint)}, " +
                        "got ${ApkCertificates.abbreviate(fingerprint)}"
                )
            }
            return fingerprint
        }
    }
}
