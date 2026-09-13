package com.vayunmathur.auto.telephony

import android.content.Intent
import android.os.IBinder
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

/**
 * The non-car half of gearhead's NonCar / CarProjection `InCallService`
 * split (`NonCarInCallServiceImpl`, manifest-disabled): present so the
 * package declares the same InCall surface, but disabled -- the platform
 * must never bind it.
 *
 * Manifest-disabled (`enabled=false`): even an explicit bind intent cannot
 * instantiate it, and [onBind] rejects defensively anyway. Phone-UI calls
 * stay with the platform dialer; projected calls belong to
 * [CarProjectionInCallService].
 */
class NonCarInCallService : InCallService() {

    override fun onCallAdded(call: Call) {
        // Disabled: must never happen. Log and release the call reference
        // without registering anything, so a stray bind leaks nothing.
        Log.w(TAG, "non-car InCall bound while disabled; ignoring call")
    }

    override fun onCallRemoved(call: Call) = Unit

    override fun onBind(intent: Intent?): IBinder? {
        // Explicit no-op-with-comment: a head unit (or any third party)
        // binding the non-car half must be safely rejected -- the platform
        // owns phone-UI calls and this half is manifest-disabled.
        Log.w(TAG, "non-car InCall bind rejected (disabled half)")
        return null
    }

    private companion object {
        const val TAG = "MaAuto.InCallNonCar"
    }
}
