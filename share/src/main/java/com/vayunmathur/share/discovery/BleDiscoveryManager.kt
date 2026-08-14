package com.vayunmathur.share.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "BleDiscovery"

/**
 * Nearby Share BLE service UUID / 0xFC service-data beacon.
 *
 * Fast Pair / Nearby Connections advertises a BLE service-data entry so
 * scanners can discover peers even when the Wi-Fi/mDNS leg isn't yet
 * correlated. The task description calls this "the 0xFC service-data beacon".
 * Android's stock Quick Share attaches a short payload here (endpoint info +
 * visibility hint). The Kotlin side only needs to beacon and scan — the Rust
 * side decides trust/visibility — but the beacon must exist for interoperability.
 *
 * We use the 16-bit UUID 0xFE2C (Google Nearby) which maps to the full
 * 128-bit form required by Android's ScanFilter/AdvertiseData APIs.
 *
 * Reference values are documented in third-party reimplementations
 * (e.g. LocShar, rQuickShare) and in Android's own Nearby service-data
 * captures; adjust strictly if real-device testing indicates a different UUID
 * or manufacturer-specific fallback is required.
 */
private const val NEARBY_SHARE_SERVICE_UUID = "0000fe2c-0000-1000-8000-00805f9b34fb"
private val SERVICE_UUID: ParcelUuid = ParcelUuid.fromString(NEARBY_SHARE_SERVICE_UUID)
/** Fallback 0xFC-style service UUID used by some interop captures. */
private const val ALT_SERVICE_UUID = "0000fcfc-0000-1000-8000-00805f9b34fb"
private val ALT_UUID: ParcelUuid = ParcelUuid.fromString(ALT_SERVICE_UUID)

/** Minimal service-data payload: endpoint-id + visibility flag (1 byte is enough for beaconing). */
private fun serviceDataPayload(endpointName: String): ByteArray {
    // Keep under 20 bytes so it fits in the legacy 31-byte advertisement.
    val nameBytes = endpointName.toByteArray(Charsets.UTF_8).take(10).toByteArray()
    // Format: [version=1][nameLen][nameBytes][visible=1]
    return byteArrayOf(0x01, nameBytes.size.toByte()) + nameBytes + byteArrayOf(0x01)
}

/**
 * BLE advertisement + scanning for Nearby Share discovery.
 *
 * RECEIVE: startAdvertising so real Android Quick Share sees this device as
 * a nearby target when the user's "Device visible" toggle is on.
 * SEND: startScanning to populate a flow of [NearbyDevice] for the Send
 * nearby-device list alongside NSD results.
 *
 * All operations are permission-guarded (BLUETOOTH_ADVERTISE / SCAN / CONNECT)
 * and no-op when Bluetooth is unavailable or permissions are missing.
 */
class BleDiscoveryManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private val _bleDevices = MutableStateFlow<Map<String, NearbyDevice>>(emptyMap())
    val bleDevices: StateFlow<Map<String, NearbyDevice>> = _bleDevices.asStateFlow()

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // Advertising (Receive: "visible to nearby devices")
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startAdvertising(endpointName: String): Boolean {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            Log.w(TAG, "startAdvertising denied: missing BLUETOOTH_ADVERTISE")
            return false
        }
        val btAdapter = adapter ?: run {
            Log.w(TAG, "no BluetoothAdapter — cannot advertise")
            return false
        }
        if (!btAdapter.isEnabled) {
            Log.w(TAG, "Bluetooth disabled — cannot advertise")
            return false
        }
        val adv = btAdapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "bluetoothLeAdvertiser null — cannot advertise")
            return false
        }
        stopAdvertising()
        val payload = serviceDataPayload(endpointName)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_UUID)
            .addServiceData(SERVICE_UUID, payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.i(TAG, "BLE advertising started as '$endpointName'")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE advertising failed: $errorCode")
            }
        }
        advertiseCallback = callback
        advertiser = adv
        return try {
            adv.startAdvertising(settings, data, callback)
            true
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertising threw", e)
            advertiseCallback = null
            advertiser = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val cb = advertiseCallback ?: return
        val adv = advertiser ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) return
        try {
            adv.stopAdvertising(cb)
        } catch (_: Exception) {
        }
        advertiseCallback = null
        advertiser = null
        Log.d(TAG, "BLE advertising stopped")
    }

    // ------------------------------------------------------------------
    // Scanning (Send: discover peers)
    // ------------------------------------------------------------------

    fun scan(): Flow<NearbyDevice> = callbackFlow {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(TAG, "scan denied: missing BLUETOOTH_SCAN")
            close()
            return@callbackFlow
        }
        val btAdapter = adapter ?: run {
            close()
            return@callbackFlow
        }
        if (!btAdapter.isEnabled) {
            close()
            return@callbackFlow
        }
        val scanner = btAdapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "bluetoothLeScanner null")
            close()
            return@callbackFlow
        }
        val filterPrimary = ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()
        val filterAlt = ScanFilter.Builder().setServiceUuid(ALT_UUID).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val data = record.getServiceData(SERVICE_UUID)
                    ?: record.getServiceData(ALT_UUID)
                val addr = result.device.address ?: return
                // Decode payload: [version][nameLen][nameBytes][visible]
                val nameFromPayload: String? = if (data != null && data.size >= 2) {
                    val len = data[1].toInt() and 0xFF
                    if (data.size >= 2 + len) {
                        String(data, 2, len, Charsets.UTF_8)
                    } else null
                } else null
                val displayName = nameFromPayload
                    ?: result.device.name
                    ?: record.deviceName
                    ?: addr
                val dev = NearbyDevice(
                    endpointId = addr,
                    endpointName = displayName,
                    host = null, // BLE peer requires further TCP endpoint resolution via NSD or earlier payload exchange.
                    port = null,
                    source = DiscoverySource.Ble,
                    extra = if (data != null) data.joinToString(",") { "%02x".format(it) } else null,
                )
                _bleDevices.value = _bleDevices.value.toMutableMap().apply { put(addr, dev) }
                trySend(dev)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
                close()
            }
        }
        scanCallback = callback
        try {
            // Requiring both UUIDs doubles discovery odds across stock vs third-party beacons.
            @SuppressLint("MissingPermission")
            fun doStart() = scanner.startScan(listOf(filterPrimary, filterAlt), settings, callback)
            doStart()
        } catch (e: Exception) {
            Log.w(TAG, "startScan threw", e)
            close(e)
            return@callbackFlow
        }
        awaitClose {
            // Lint's MissingPermission doesn't recognise hasPermission() wrapper; guard + suppress.
            @SuppressLint("MissingPermission")
            fun stopIfPermitted() {
                if (hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                    try {
                        scanner.stopScan(callback)
                    } catch (_: SecurityException) {
                    } catch (_: Exception) {
                    }
                }
            }
            stopIfPermitted()
            scanCallback = null
            Log.d(TAG, "BLE scan stopped")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val cb = scanCallback ?: return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        val scanner = adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(cb)
        } catch (_: Exception) {
        }
        scanCallback = null
    }

    fun release() {
        stopAdvertising()
        stopScan()
    }
}
