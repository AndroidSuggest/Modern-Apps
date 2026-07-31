package com.vayunmathur.vpn.service

import com.vayunmathur.vpn.data.ConnectionLogEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory flow aggregation for hot-path packet inspection.
 * Thread-safe for single writer (VPN service tunnel thread). `drainDirty()` is called from flush job.
 */
class ConnectionTracker {

    enum class Direction { TX, RX }

    /**
     * Identifies a flow by its 4-tuple *without* uid: attribution often only succeeds a few packets
     * in, and keying on uid would fork the same connection into an unattributed row plus a real one.
     */
    data class FlowKey(
        val protocol: String,
        val remoteIp: String,
        val remotePort: Int,
        val localPort: Int,
    )

    private data class MutableAgg(
        var timestampStart: Long,
        var timestampLast: Long,
        var uid: Int,
        var packageName: String?,
        var appLabel: String,
        var localIp: String,
        var remoteIp: String,
        var remotePort: Int,
        var localPort: Int,
        var protocol: String,
        var domain: String?,
        var txBytes: Long,
        var rxBytes: Long,
        var requestCount: Long,
        @Volatile var dirty: Boolean = true,
        @Volatile var id: Long = 0,
    )

    private val flows = ConcurrentHashMap<FlowKey, MutableAgg>()

    @Volatile var dnsCacheRef: DnsCache? = null

    fun setDnsCache(cache: DnsCache) {
        dnsCacheRef = cache
    }

    fun onPacket(
        parsed: PacketInspector.ParsedPacket,
        direction: Direction,
        bytes: Int,
        domainOverride: String? = null,
        uid: Int,
        packageName: String?,
        appLabel: String,
    ) {
        val now = System.currentTimeMillis()
        val key = FlowKey(
            protocol = parsed.protocol,
            remoteIp = if (direction == Direction.TX) parsed.dstIp else parsed.srcIp,
            remotePort = if (direction == Direction.TX) parsed.dstPort else parsed.srcPort,
            localPort = if (direction == Direction.TX) parsed.srcPort else parsed.dstPort,
        )

        val isNewKey = !flows.containsKey(key)
        val effectiveRequestIncrement = if (isNewKey) 1L else 0L

        var domain = domainOverride
        if (domain == null) {
            domain = dnsCacheRef?.get(key.remoteIp) ?: flows[key]?.domain
        }

        flows.compute(key) { _, existing ->
            if (existing == null) {
                MutableAgg(
                    timestampStart = now,
                    timestampLast = now,
                    uid = uid,
                    packageName = packageName,
                    appLabel = appLabel,
                    localIp = if (direction == Direction.TX) parsed.srcIp else parsed.dstIp,
                    remoteIp = key.remoteIp,
                    remotePort = key.remotePort,
                    localPort = key.localPort,
                    protocol = parsed.protocol,
                    domain = domain,
                    txBytes = if (direction == Direction.TX) bytes.toLong() else 0L,
                    rxBytes = if (direction == Direction.RX) bytes.toLong() else 0L,
                    requestCount = effectiveRequestIncrement.coerceAtLeast(1),
                    dirty = true,
                )
            } else {
                existing.timestampLast = now
                existing.txBytes = if (direction == Direction.TX) existing.txBytes + bytes else existing.txBytes
                existing.rxBytes = if (direction == Direction.RX) existing.rxBytes + bytes else existing.rxBytes
                if (effectiveRequestIncrement > 0 && existing.requestCount == 0L) existing.requestCount = effectiveRequestIncrement
                if (domain != null && existing.domain == null) existing.domain = domain
                // Late attribution wins: uid -1 means nothing has identified this flow yet.
                if (existing.uid < 0 && uid >= 0) {
                    existing.uid = uid
                    existing.packageName = packageName
                    existing.appLabel = appLabel
                } else if (existing.packageName == null && packageName != null) {
                    existing.packageName = packageName
                    existing.appLabel = appLabel
                }
                existing.dirty = true
                existing
            }
        }
    }

    fun recordDnsMapping(ip: String, domain: String) {
        dnsCacheRef?.put(ip, domain)
    }

    fun drainDirty(): List<ConnectionLogEntity> {
        if (flows.isEmpty()) return emptyList()
        val batch = mutableListOf<ConnectionLogEntity>()
        for ((_, agg) in flows) {
            if (agg.dirty) {
                batch.add(
                    ConnectionLogEntity(
                        id = agg.id,
                        timestampStart = agg.timestampStart,
                        timestampLast = agg.timestampLast,
                        uid = agg.uid,
                        packageName = agg.packageName,
                        appLabel = agg.appLabel,
                        protocol = agg.protocol,
                        localIp = agg.localIp,
                        localPort = agg.localPort,
                        remoteIp = agg.remoteIp,
                        remotePort = agg.remotePort,
                        domain = agg.domain,
                        txBytes = agg.txBytes,
                        rxBytes = agg.rxBytes,
                        requestCount = agg.requestCount,
                    )
                )
                agg.dirty = false
            }
        }
        return batch
    }

    fun updateIds(entities: List<ConnectionLogEntity>) {
        for (e in entities) {
            val key = FlowKey(
                protocol = e.protocol,
                remoteIp = e.remoteIp,
                remotePort = e.remotePort,
                localPort = e.localPort,
            )
            flows[key]?.id = e.id
        }
    }

    fun clear() {
        flows.clear()
    }

    fun snapshot(): List<ConnectionLogEntity> {
        return flows.values.map {
            ConnectionLogEntity(
                id = it.id,
                timestampStart = it.timestampStart,
                timestampLast = it.timestampLast,
                uid = it.uid,
                packageName = it.packageName,
                appLabel = it.appLabel,
                protocol = it.protocol,
                localIp = it.localIp,
                localPort = it.localPort,
                remoteIp = it.remoteIp,
                remotePort = it.remotePort,
                domain = it.domain,
                txBytes = it.txBytes,
                rxBytes = it.rxBytes,
                requestCount = it.requestCount,
            )
        }
    }

    companion object {
        @Volatile
        var globalInstance: ConnectionTracker? = null
            private set

        fun getOrCreate(): ConnectionTracker {
            val existing = globalInstance
            if (existing != null) return existing
            synchronized(this) {
                val doubleCheck = globalInstance
                if (doubleCheck != null) return doubleCheck
                val created = ConnectionTracker()
                globalInstance = created
                return created
            }
        }

        fun clearGlobal() {
            synchronized(this) {
                globalInstance?.clear()
                globalInstance = null
            }
        }
    }
}
