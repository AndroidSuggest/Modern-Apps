package com.vayunmathur.communicate.telephony

import android.os.PersistableBundle
import android.service.carrier.CarrierIdentifier
import android.service.carrier.CarrierService

/**
 * Carrier service declaration for the MA-OS privilege grant.
 *
 * MA-OS patches `UiccCarrierPrivilegeRules` to report
 * `com.vayunmathur.communicate` as carrier-privileged regardless of UICC
 * rules. That alone unlocks `hasCarrierPrivileges()` (SIP delegate, GBA,
 * UCE). The IMS *socket bind* additionally requires the platform to select
 * us as the carrier *service* (`CarrierPrivilegeAuthenticator` matches the
 * bound service UID): this service, with `LONG_LIVED_BINDING`, is what gets
 * selected once the privilege grant lands.
 *
 * `onLoadConfig` returns an empty bundle — we don't override carrier config,
 * we only need the binding identity. Never started directly; the platform
 * binds it.
 */
class CommunicateCarrierService : CarrierService() {
    override fun onLoadConfig(id: CarrierIdentifier): PersistableBundle =
        PersistableBundle()
}
