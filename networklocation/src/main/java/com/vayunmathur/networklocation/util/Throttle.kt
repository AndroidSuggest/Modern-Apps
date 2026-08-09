package com.vayunmathur.networklocation.util

/**
 * Minimal rate limiter: [tryAcquire] succeeds at most once per [minIntervalMillis].
 * Guards the gs-loc network queries so a tight request interval from the framework
 * cannot hammer Apple's proxy (which also protects the user's privacy/quota).
 */
class Throttle(
    private val minIntervalMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastAllowed = Long.MIN_VALUE

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (now - lastAllowed < minIntervalMillis) return false
        lastAllowed = now
        return true
    }

    @Synchronized
    fun reset() {
        lastAllowed = Long.MIN_VALUE
    }
}
