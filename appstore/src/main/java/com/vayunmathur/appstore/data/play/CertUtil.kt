package com.vayunmathur.appstore.data.play

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
        val packageInfo = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }

        val signingInfo = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo
        } else null

        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val si = signingInfo
            if (si == null) emptyArray()
            else if (si.hasMultipleSigners()) {
                si.apkContentsSigners
            } else {
                si.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures ?: emptyArray()
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
