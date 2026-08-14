package com.vayunmathur.share.discovery

import kotlinx.serialization.Serializable

/**
 * A nearby device discovered via NSD (mDNS) or BLE scanning.
 *
 * Produced by [NsdDiscoveryManager] + [BleDiscoveryManager] and consumed by
 * [com.vayunmathur.share.ui.ShareViewModel] for the Send flow's nearby-device list.
 * TCP endpoint is the resolved host/port for connecting the session's socket
 * (the same socket handed to ShareSession's pump).
 */
@Serializable
data class NearbyDevice(
    val endpointId: String,
    val endpointName: String,
    val serviceName: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val source: DiscoverySource = DiscoverySource.Nsd,
    val extra: String? = null,
)

enum class DiscoverySource { Nsd, Ble, Both }
