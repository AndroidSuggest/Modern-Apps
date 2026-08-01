package com.vayunmathur.library.network

import android.content.Context
import android.util.Log
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds SSLSocketFactory from bundled DER roots in assets/ca/.
 * Reuses CertPinning pattern (CertificateFactory -> KeyStore -> TMF -> SSLContext).
 * Caches per-bundle to avoid repeated asset I/O.
 */
object BundledTrust {
    private const val TAG = "BundledTrust"

    @Volatile
    private var cache: Map<TrustBundle, Pair<SSLSocketFactory, X509TrustManager>> = emptyMap()

    @Synchronized
    fun createFactory(
        context: Context,
        bundle: TrustBundle,
    ): Pair<SSLSocketFactory, X509TrustManager>? {
        if (bundle == TrustBundle.SYSTEM) return null

        cache[bundle]?.let { return it }

        val assetPaths = bundle.assetPaths()
        if (assetPaths.isEmpty()) {
            Log.w(TAG, "Bundle $bundle has no asset paths")
            return null
        }

        try {
            val cf = CertificateFactory.getInstance("X.509")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }

            var loaded = 0
            for ((idx, path) in assetPaths.withIndex()) {
                try {
                    context.assets.open(path).use { ins ->
                        val cert = cf.generateCertificate(ins) as? X509Certificate
                        if (cert != null) {
                            ks.setCertificateEntry("ca-$idx-${path.hashCode()}", cert)
                            loaded++
                        } else {
                            Log.w(TAG, "Asset $path did not decode to X509Certificate")
                        }
                    }
                } catch (e: Exception) {
                    // Missing asset is expected during early dev before DERs are bundled.
                    Log.w(TAG, "Failed to load CA asset $path: ${e.message}")
                }
            }

            if (loaded == 0) {
                Log.w(TAG, "No CAs loaded for bundle $bundle (checked ${assetPaths.size} assets) — falling back to system trust")
                return null
            }

            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(ks)
            }
            val trustManager = tmf.trustManagers.firstOrNull { it is X509TrustManager } as? X509TrustManager
            if (trustManager == null) {
                Log.e(TAG, "No X509TrustManager found for bundle $bundle")
                return null
            }

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, tmf.trustManagers, null)
            }

            val pair = sslContext.socketFactory to trustManager
            cache = cache + (bundle to pair)
            Log.i(TAG, "Bundle $bundle loaded $loaded roots")
            return pair
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create factory for bundle $bundle", e)
            return null
        }
    }

    /** For tests / debug: clear cache. */
    @Synchronized
    fun clearCache() {
        cache = emptyMap()
    }
}
