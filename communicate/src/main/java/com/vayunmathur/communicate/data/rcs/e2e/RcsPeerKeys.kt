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
 * (backed by nothing — packages are single-use and republished on demand, so
 * staleness fails closed at the crate layer and the creator simply requests a
 * fresh one by sending a key-package request message).
 *
 * v1 scope: in-memory only. A Room table lands with the key-rotation pass.
 */
object RcsPeerKeys {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun store(context: Context, peerE164: String, keyPackage: ByteArray) {
        if (!RcsFeature.enabled || peerE164.isBlank() || keyPackage.isEmpty()) return
        cache[peerE164] = keyPackage
    }

    fun get(peerE164: String): ByteArray? {
        if (!RcsFeature.enabled) return null
        return cache[peerE164]
    }

    fun consume(peerE164: String): ByteArray? {
        if (!RcsFeature.enabled) return null
        return cache.remove(peerE164)
    }

    fun clear() {
        cache.clear()
    }
}
