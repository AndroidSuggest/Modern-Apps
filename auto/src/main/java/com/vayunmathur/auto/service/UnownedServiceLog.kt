package com.vayunmathur.auto.service

import android.util.Log
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.gal.Service

/**
 * Observe-only pass for advertised services with no app owner. The channel
 * still opened generically in wire order (every advertised id gets a 0x7)
 * and inbound traffic lands in the session router's unowned branch --
 * observed and ignored, never answered, never crashed on. This only logs
 * what the descriptor already says, so a future owner (BT association,
 * radio, phone) can pick the channel up without changing the bring-up.
 */
internal fun observeUnownedService(service: Service) {
    when (service.id) {
        // Bluetooth: the wireless trigger path (CDM association, then
        // BT_START -> RFCOMM -> TCP) owns this when it lands; until then
        // the descriptor is the value -- which car, which pairing
        // methods -- and ch9 traffic stays ignored.
        GalService.BLUETOOTH.id -> if (service.hasBluetooth()) {
            val bt = service.bluetooth
            Log.i(
                TAG,
                "head-unit bluetooth ${bt.carAddress} " +
                    "(${bt.supportedPairingMethodsCount} pairing methods); no owner yet",
            )
        }
        // Phone status: control 24 carries the call verdict into
        // session.callAvailable; the ch13 descriptor and traffic stay
        // observed until an InCall owner needs more.
        GalService.PHONE_STATUS.id -> Log.i(TAG, "head-unit phone-status advertised; no owner yet")
        // Radio: opaque descriptor bytes (no typed decode recovered), no
        // owner; the tuner stays a head-unit affair.
        GalService.RADIO.id -> Log.i(TAG, "head-unit radio advertised; no owner yet")
        // Vendor extension ("vcar" slot): typed DTO, no owner; the name
        // and allowlist size say who is extending, nothing more.
        GalService.VENDOR_EXTENSION.id -> if (service.hasVendorExtension()) {
            val vendor = service.vendorExtension
            Log.i(
                TAG,
                "head-unit vendor extension ${vendor.name} " +
                    "(${vendor.packageAllowlistCount} packages); no owner yet",
            )
        }
        // Wireless trigger services: the CDM association owns these when
        // it lands (see CarCompanionDeviceService); until then the BSSID
        // hint is the value and ch17/18 traffic stays ignored.
        GalService.WIFI_PROJECTION.id -> if (service.hasWifiProjection()) {
            Log.i(
                TAG,
                "head-unit wifi-projection advertised " +
                    "(bssid=${service.wifiProjection.carWifiBssid}); no owner yet",
            )
        }
        GalService.WIFI_DISCOVERY.id ->
            Log.i(TAG, "head-unit wifi-discovery advertised; no owner yet")
        // Car-control family: descriptor payloads are generic bytes
        // (control.proto f15-18, per-service mapping unrecovered), so
        // there is nothing typed to observe -- open generically, ignore
        // inbound, never crash.
        GalService.CAR_CONTROL.id,
        GalService.CAR_LOCAL_MEDIA.id,
        GalService.BUFFERED_MEDIA_SINK.id,
        GalService.CAR_INTENT.id,
        -> Log.i(TAG, "head-unit car service ${service.id} advertised; no owner yet")
        // Owned services and the control channel need no observation.
        else -> Unit
    }
}

private const val TAG = "MaAuto.Service"
