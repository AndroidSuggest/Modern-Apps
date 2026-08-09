package com.vayunmathur.networklocation.cache

/**
 * A size- and time-bounded LRU cache. Entries older than [ttlMillis] are treated
 * as absent; once [maxSize] is exceeded the least-recently-used live entry is
 * evicted. Used as the in-memory front of the persistent beacon cache so repeat
 * scans in one session never touch the DB (or the network).
 *
 * Not thread-safe on its own; callers synchronize (the reporting loop is
 * single-threaded, but [get]/[put] are `@Synchronized` for safety).
 */
class TimedLruCache<K, V>(
    private val maxSize: Int,
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Timed<V>(val value: V, val insertedAt: Long)

    // accessOrder=true makes this a genuine LRU: get() moves the entry to the tail.
    private val map = object : LinkedHashMap<K, Timed<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Timed<V>>): Boolean =
            size > maxSize
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (clock() - entry.insertedAt > ttlMillis) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Timed(value, clock())
    }

    @Synchronized
    fun clear() = map.clear()
}
