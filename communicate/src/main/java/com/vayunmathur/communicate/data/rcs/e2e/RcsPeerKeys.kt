package com.vayunmathur.communicate.data.rcs.e2e

import android.content.Context
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Peer key-package cache for E2EE group creation.
 *
 * Key packages arrive as unencrypted publications on 1:1 threads
 * ([RcsKeyDirectory]). The latest package per peer E.164 is kept in memory
 * with a receive timestamp; packages older than [PACKAGE_TTL_MS] are treated
 * as expired (§5.1 — single-use init keys go stale, and a stale add fails
 * closed at the crate layer, so expiry here just skips the doomed attempt
 * and triggers a fresh request instead).
 *
 * In-memory only: packages are single-use and republished on demand, so a
 * process restart simply re-requests.
 */
object RcsPeerKeys {
    /** Peer key-package TTL (7 days, matching the outbox window). */
    const val PACKAGE_TTL_MS = 7L * 24 * 3600 * 1000

    private data class Entry(val packageTls: ByteArray, val receivedAt: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun store(peerE164: String, keyPackage: ByteArray) {
        if (!RcsFeature.enabled || peerE164.isBlank() || keyPackage.isEmpty()) return
        cache[peerE164] = Entry(keyPackage, System.currentTimeMillis())
    }

    fun get(peerE164: String): ByteArray? {
        if (!RcsFeature.enabled) return null
        val entry = cache[peerE164] ?: return null
        if (System.currentTimeMillis() - entry.receivedAt > PACKAGE_TTL_MS) {
            cache.remove(peerE164)
            return null
        }
        return entry.packageTls
    }

    fun consume(peerE164: String): ByteArray? {
        if (!RcsFeature.enabled) return null
        val entry = cache.remove(peerE164) ?: return null
        if (System.currentTimeMillis() - entry.receivedAt > PACKAGE_TTL_MS) return null
        return entry.packageTls
    }

    fun clear() {
        cache.clear()
    }
}
