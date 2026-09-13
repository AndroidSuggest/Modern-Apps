package com.vayunmathur.findfamily.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.UserManager
import android.util.Log
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant

internal fun LocationTrackingService.startDirectBootTracking() {
    if (directBootJob?.isActive == true) return
    if (!unlockReceiverRegistered) {
        registerReceiver(
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_UNLOCKED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        unlockReceiverRegistered = true
    }
    directBootJob = serviceScope.launch {
        val ctx = this@startDirectBootTracking
        // Expected on the first boot after this ships, and after a factory reset: there is
        // nothing to publish with yet. Seeding happens below once the user unlocks.
        if (!DirectBootStore.isSeeded(ctx)) {
            Log.i(LocationTrackingService.TAG_DIRECT_BOOT, "no device-protected mirror yet; idle until first unlock")
            return@launch
        }
        if (!DirectBootStore.isTrackingEnabled(ctx)) {
            Log.i(LocationTrackingService.TAG_DIRECT_BOOT, "tracking switched off by the user; staying idle")
            return@launch
        }
        if (!Networking.initDirectBoot(DirectBootStore.store(ctx))) {
            Log.w(LocationTrackingService.TAG_DIRECT_BOOT, "identity unavailable from the mirror; staying idle")
            return@launch
        }

        withContext(Dispatchers.Main) {
            registerSensors()
            isMoving = true
            lastMovementTime = System.currentTimeMillis()
            setupLocationUpdates()
        }
        // Inbound delivery needs Room to persist anything, so the pre-unlock socket is
        // publish-only. Peers' locations are picked up on reconnect after unlock.
        Networking.startLive(serviceScope, onLocations = {}, onUwb = {})

        val sharing = DirectBootStore.isGlobalSharingEnabled(ctx)
        val targets = if (sharing) DirectBootStore.roster(ctx) else emptyList()
        publishRoster = targets
        Log.i(LocationTrackingService.TAG_DIRECT_BOOT, "running pre-unlock, sharing=$sharing targets=${targets.size}")
        while (isActive) {
            publishDirectBoot(targets)
            delay(30.seconds)
        }
    }
}

/** The pre-unlock equivalent of [syncHeartbeat]: publish only, no database, no enrichment. */
internal suspend fun LocationTrackingService.publishDirectBoot(targets: List<DirectBootStore.Target>) {
    val location = lastKnownLocation ?: run {
        Log.d(LocationTrackingService.TAG_DIRECT_BOOT, "no fix yet")
        return
    }
    if (targets.isEmpty()) return
    val battery = runCatching {
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
    }.getOrDefault(0f)
    val lv = LocationValue(
        Networking.userid,
        Coord(location.latitude, location.longitude),
        0f,
        location.accuracy,
        Clock.System.now(),
        battery,
    )
    Log.d(LocationTrackingService.TAG_DIRECT_BOOT, "publishing ${location.latitude},${location.longitude} acc=${location.accuracy} to ${targets.size} peer(s)")
    targets.forEach {
        try {
            Networking.publishLocation(lv, it.id, it.bundle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LocationTrackingService.TAG_DIRECT_BOOT, "publish to ${it.id.toULong()} failed", e)
        }
    }
}

internal fun LocationTrackingService.onUserUnlocked() {
    serviceScope.launch {
        Log.i(LocationTrackingService.TAG_DIRECT_BOOT, "user unlocked; handing over to the normal path")
        directBootJob?.cancelAndJoin()
        directBootJob = null
        try {
            Networking.promoteToUnlocked(
                repository,
                DataStoreUtils.getInstance(this@onUserUnlocked),
                getString(R.string.me_label),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LocationTrackingService.TAG_DIRECT_BOOT, "handover failed", e)
        }
        startTracking()
    }
}
