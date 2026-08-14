package com.vayunmathur.share.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Nearby Share mDNS service type.
 *
 * The Quick Share / Nearby Connections stack advertises and discovers this
 * service type over mDNS (via NsdManager) on the LAN so peers on the same
 * Wi-Fi can find each other without a cloud relay. The service type value
 * here mirrors what Nearby Share uses — the same one referenced in the task
 * description (_FC9F5ED42C8A._tcp). Tweak via constructor if a different
 * interop variant is needed.
 */
const val SHARE_SERVICE_TYPE = "_FC9F5ED42C8A._tcp"
private const val TAG = "NsdDiscovery"

/**
 * Manages mDNS (DNS-SD) advertisement + discovery for the Nearby Share TCP
 * endpoint — the "visible to nearby devices" surface in the Receive flow and
 * the nearby-device list in the Send flow.
 *
 * Responsibilities (per PROTOCOL_CONTRACT.md §8):
 *  - RECEIVE: registerService (device visible toggle) + TCP ServerSocket
 *  - SEND: discoverServices + resolveService to produce NearbyDevice endpoints
 *
 * One instance per activity/process; call [advertise] / [unadvertise] as the
 * visibility toggle flips, and collect [discoveredDevices] or [discover] for
 * the Send flow.
 */
class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<NearbyDevice>> = _discoveredDevices.asStateFlow()

    private var advertisedPort: Int = 0
    private var registeredServiceName: String? = null

    /**
     * Advertise this device as a Share endpoint on [port].
     *
     * Safe to call repeatedly; re-advertising first tears down the prior registration.
     */
    fun advertise(deviceName: String, port: Int) {
        val mgr = nsdManager ?: run {
            Log.w(TAG, "NsdManager unavailable — cannot advertise")
            return
        }
        if (advertisedPort == port && registrationListener != null) return
        unadvertise()
        advertisedPort = port
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = SHARE_SERVICE_TYPE
            setPort(port)
            // Attribute so nearby Send scanners can read endpoint info without extra round-trips.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+ supports service attributes for extra metadata; fallback to name only on older.
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                Log.i(TAG, "advertised as ${info.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "unregistered ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        try {
            mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "registerService threw", e)
        }
    }

    fun unadvertise() {
        val mgr = nsdManager ?: return
        val listener = registrationListener ?: return
        try {
            mgr.unregisterService(listener)
        } catch (_: Exception) {
        }
        registrationListener = null
        registeredServiceName = null
        advertisedPort = 0
    }

    /**
     * Start DNS-SD discovery and emit [NearbyDevice] values as services are
     * found/lost. Call from the Send flow's scanning coroutine; cancellation
     * tears down the discovery listener.
     */
    fun discover(): Flow<NearbyDevice> = callbackFlow {
        val mgr = nsdManager ?: run {
            close()
            return@callbackFlow
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "found: ${service.serviceName} type=${service.serviceType}")
                // Resolve host/port asynchronously to produce a connectable NearbyDevice.
                try {
                    mgr.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress
                            val port = info.port
                            val dev = NearbyDevice(
                                endpointId = info.serviceName,
                                endpointName = info.serviceName,
                                serviceName = info.serviceName,
                                host = host,
                                port = if (port > 0) port else null,
                                source = DiscoverySource.Nsd,
                            )
                            trySend(dev)
                            // Also maintain the snapshot StateFlow for legacy consumers.
                            val current = _discoveredDevices.value.toMutableList()
                            if (current.none { it.endpointId == dev.endpointId }) {
                                current += dev
                            } else {
                                val idx = current.indexOfFirst { it.endpointId == dev.endpointId }
                                current[idx] = dev
                            }
                            _discoveredDevices.value = current
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "resolveService threw for ${service.serviceName}", e)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "lost: ${service.serviceName}")
                _discoveredDevices.value = _discoveredDevices.value.filterNot {
                    it.endpointId == service.serviceName
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "startDiscovery failed $serviceType: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stopDiscovery failed $serviceType: $errorCode")
            }
        }
        discoveryListener = listener
        try {
            mgr.discoverServices(SHARE_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discoverServices threw", e)
            close(e)
            return@callbackFlow
        }
        awaitClose {
            try {
                discoveryListener?.let { mgr.stopServiceDiscovery(it) }
            } catch (_: Exception) {
            }
            discoveryListener = null
        }
    }

    /**
     * Convenience: start discovery and collect into [discoveredDevices] until
     * cancelled. Prefer [discover] when the caller wants the flow directly.
     */
    suspend fun startCollectingDiscovery(): Nothing {
        discover().collect { }
        error("unreachable — discover flow completed unexpectedly")
    }

    fun stopDiscovery() {
        val mgr = nsdManager ?: return
        val listener = discoveryListener ?: return
        try {
            mgr.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
        discoveryListener = null
    }

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptyList()
    }

    fun release() {
        unadvertise()
        stopDiscovery()
    }
}
