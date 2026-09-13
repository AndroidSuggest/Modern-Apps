package com.vayunmathur.findfamily.util

import android.location.Location
import android.os.BatteryManager
import android.util.Log
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.havershine
import com.vayunmathur.findfamily.R
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * How stale the held fix has to be before a less accurate one replaces it.
 *
 * Long enough that a burst of coarse network fixes cannot displace a good GPS one,
 * short enough that a device which has lost GPS still reports where it now is.
 */
private val FIX_MAX_AGE_NANOS = 2.minutes.inWholeNanoseconds

/**
 * Keeps the best recent fix rather than simply the newest one.
 *
 * The network provider delivers a fix every ten seconds and its answer wanders by tens
 * to hundreds of metres between them, so overwriting a recent GPS fix with one of those
 * made a phone sitting on a table appear to move around the city. A coarser fix only
 * wins once the one being held has gone stale enough to be the worse answer.
 */
internal fun LocationTrackingService.recordFix(location: Location) {
    val held = lastKnownLocation
    if (held == null) {
        lastKnownLocation = location
        return
    }
    val age = location.elapsedRealtimeNanos - held.elapsedRealtimeNanos
    if (age <= 0) return
    if (age > FIX_MAX_AGE_NANOS || location.accuracy <= held.accuracy) {
        lastKnownLocation = location
    }
}

internal suspend fun LocationTrackingService.syncHeartbeat() {
    val location = lastKnownLocation ?: run {
        Log.d("FF-Heartbeat", "syncHeartbeat: no lastKnownLocation yet")
        return
    }
    if (Networking.userid == 0L) {
        Log.d("FF-Heartbeat", "syncHeartbeat: userid==0, not initialized yet")
        return
    }

    // Shield the entire heartbeat so one failing DAO / crypto / network call
    // does not kill the foreground service loop (which previously surfaced as
    // FATAL BadPaddingException in decrypt).
    try {
        val currentUsers = repository.getAllUsers()
        val currentLinks = repository.getAllTemporaryLinks()
        val now = Clock.System.now()

        Log.d("FF-Heartbeat", "heartbeat userid=${Networking.userid.toULong()} self raw=${Networking.userid} users=${currentUsers.size} links=${currentLinks.size} moving=$isMoving loc=${location.latitude},${location.longitude} acc=${location.accuracy}")

        val locationValue = LocationValue(
            Networking.userid,
            Coord(location.latitude, location.longitude),
            0f,
            location.accuracy,
            now,
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
        )

        Log.d("FF-Heartbeat", "upsert local LocationValue for self")
        repository.upsertLocation(locationValue)

        if (currentUsers.none { it.id == Networking.userid }) {
            Log.d("FF-Heartbeat", "self not in user DB, inserting me")
            repository.upsertUser(
                User(
                    getString(R.string.me_label),
                    null,
                    "Unnamed Location",
                    true,
                    RequestStatus.MUTUAL_CONNECTION,
                    Clock.System.now(),
                    null,
                    Networking.userid
                )
            )
        }

        // Auto-toggle check: atomic flip guarded by the timer value itself.
        // If the user manually cleared or rescheduled after we read currentUsers, the
        // WHERE clause (sharingAutoToggleAt <= now) won't match and we won't accidentally
        // disable/enable when they didn't intend it. No stale copy() + upsert().
        var publishBaseUsers = currentUsers
        try {
            val flipped = repository.applyDueAutoToggles(now.epochSeconds)
            if (flipped > 0) {
                Log.d("FF-Heartbeat", "auto-toggle flipped $flipped user(s), reloading sharing state before publish")
                // Reload fresh sharing flags so we don't publish once after an intended disable,
                // and we start publishing immediately after an intended enable.
                publishBaseUsers = repository.getAllUsers()
            }
        } catch (e: Exception) {
            Log.w("FF-Heartbeat", "auto-toggle apply failed", e)
        }

        // Arrival auto-toggle check (GitHub #406): flip sharing for any user whose trigger
        // points at a saved place that "Me" is currently inside. Uses the same atomic,
        // waypoint-id-guarded update as the timer path so a stale snapshot cannot mis-flip.
        try {
            val myCoord = Coord(location.latitude, location.longitude)
            val insideWaypointIds = repository.getAllWaypoints()
                .filter { havershine(it.coord, myCoord) < it.range }
                .map { it.id }
            if (insideWaypointIds.isNotEmpty()) {
                val flippedArrival = repository.applyDueArrivalToggles(insideWaypointIds)
                if (flippedArrival > 0) {
                    Log.d("FF-Heartbeat", "arrival auto-toggle flipped $flippedArrival user(s), reloading sharing state before publish")
                    publishBaseUsers = repository.getAllUsers()
                }
            }
        } catch (e: Exception) {
            Log.w("FF-Heartbeat", "arrival auto-toggle apply failed", e)
        }

        // The sharing tile (GitHub #648) suppresses outbound publishing without touching the
        // per-person switches, so turning it back on resumes exactly the set of people the
        // user was sharing with. Receiving, waypoints and tracker reporting are unaffected.
        val sharingOut = LocationServiceController.isGlobalSharingEnabled(this)
        val publishTargets = if (!sharingOut) emptyList<User>()
        else publishBaseUsers.filter { it.id != Networking.userid && it.sendingEnabled }
        Log.d("FF-Heartbeat", "publish targets count=${publishTargets.size} ids=${publishTargets.map{ it.id.toULong() }} names=${publishTargets.map{ it.name }} globalSharing=$sharingOut")
        publishTargets.forEach {
            val result = runCatching { Networking.publishLocation(locationValue, it) }
            if (result.isFailure) Log.w("FF-Heartbeat", "publish to ${it.id.toULong()} threw", result.exceptionOrNull())
        }
        if (sharingOut) currentLinks.filter { now < it.deleteAt }.forEach {
            val result = runCatching { Networking.publishLocation(locationValue, it) }
            if (result.isFailure) Log.w("FF-Heartbeat", "publish to link ${it.id} threw", result.exceptionOrNull())
        }
        currentLinks.filter { now >= it.deleteAt }.forEach { runCatching { repository.deleteTemporaryLink(it) } }

        // Incoming peer locations arrive via the live WebSocket push (see startTracking →
        // Networking.startLive). There is no HTTP receive; if the socket is down the loop
        // reconnects and the next heartbeat re-publishes.
    } catch (e: Exception) {
        Log.w("FF-Heartbeat", "syncHeartbeat crashed", e)
    }
}

/**
 * Mirror the identity, switches and sharing roster into device-protected storage so the
 * next reboot can report before the passcode is entered.
 *
 * Reads its own state rather than borrowing the heartbeat's, because the heartbeat gives
 * up early when there is no fix yet — and a device that has never had a fix still needs a
 * seeded mirror. Rewritten only when the result would differ, since this runs every tick.
 */
internal suspend fun LocationTrackingService.seedDirectBootMirror() {
    val sharingOut = LocationServiceController.isGlobalSharingEnabled(this)
    val trackingEnabled = LocationServiceController.isTrackingEnabled(this)
    val targets = repository.getAllUsers()
        .filter { it.id != Networking.userid && it.sendingEnabled }
        .mapNotNull { u -> u.pqcEncryptionKey?.let { DirectBootStore.Target(u.id, it) } }
    val signature = "$sharingOut|$trackingEnabled|" + targets.joinToString(",") { "${it.id}:${it.bundle.length}" }
    publishRoster = if (sharingOut) targets else emptyList()
    if (signature == lastSeededMirror) return
    DirectBootStore.seed(
        this,
        targets,
        trackingEnabled = trackingEnabled,
        globalSharingEnabled = sharingOut,
    )
    lastSeededMirror = signature
    Log.i(LocationTrackingService.TAG_DIRECT_BOOT, "mirror seeded: ${targets.size} target(s) sharing=$sharingOut tracking=$trackingEnabled")
}
