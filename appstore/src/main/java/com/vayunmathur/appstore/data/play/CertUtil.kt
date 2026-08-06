package com.vayunmathur.appstore.data.play

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import android.util.Base64

/**
 * Port of Aurora's CertUtil.getEncodedCertificateHashes / gplayapi CertUtil
 * Returns list of base64-encoded SHA1 hashes of signing certificates.
 * Used for key rotation purchase flow when app already installed.
 */
object CertUtil {

    fun getEncodedCertificateHashes(context: Context, packageName: String): List<String> {
        return try {
            val certs = getCertificateHashes(context, packageName)
            certs.map { hash ->
                Base64.encodeToString(hash, Base64.NO_WRAP)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getCertificateHashes(context: Context, packageName: String): List<ByteArray> {
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)

        val signingInfo = packageInfo.signingInfo

        val signatures = if (signingInfo == null) {
            emptyArray()
        } else if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }

        return signatures.mapNotNull { sig ->
            try {
                val md = MessageDigest.getInstance("SHA1")
                md.digest(sig.toByteArray())
            } catch (_: Exception) {
                null
            }
        }
    }
}
