package com.vayunmathur.findfamily.util

import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.havershine
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.domain.NoShowPolicy
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.findfamily.uwb.UwbInbox
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.tracker.PoweredOffKeyStore
import com.vayunmathur.findfamily.tracker.poweredOffGrantSigningBytes
import com.vayunmathur.findfamily.uwb.PoweredOffGrant
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/**
 * Accuracy beyond which a fix is not used to decide geofence membership.
 * Delegates to [NoShowPolicy.NO_SHOW_MAX_ACCURACY_METERS] so the live path and
 * the no-show path can never drift apart.
 */
internal val GEOFENCE_MAX_ACCURACY_METERS: Double
    get() = NoShowPolicy.NO_SHOW_MAX_ACCURACY_METERS

/** How far past a geofence's radius a fix has to be before it counts as having left. */
internal const val WAYPOINT_EXIT_HYSTERESIS = 1.2

/** Battery level at or below which a low-battery alert fires once per crossing. */
private const val LOW_BATTERY_THRESHOLD = 15f

/** Assumed battery when no prior fix exists to compare against. */
private const val FULL_BATTERY = 100f

/**
 * Persists a batch of freshly-decrypted peer locations and inserts unknown senders.
 * Runs on the live WebSocket reader coroutine, so it only does fast, durable work;
 * the slow best-effort part is handed to [enrichIncomingLocations]. Self-contained
 * (re-reads users) so it can be driven by any inbound path.
 */
internal suspend fun LocationTrackingService.processIncomingLocations(incoming: List<LocationValue>) {
    // A category this build cannot interpret is dropped rather than stored. Marking it is not
    // enough: getLatest() ranks on reportedAt, so it would still become the newest row for
    // that person and be drawn as their position — and at least one such category
    // (NETWORK_SIGHTING) carries someone ELSE's coordinate.
    //
    // The log names who was dropped, not just how many. This is the one failure mode that is
    // otherwise invisible: if a future build ever emits a new source as someone's primary
    // stream, that person silently disappears from this map, and "Alice was dropped" is the
    // only thread anyone will have to pull on. See the note on LocationSource before adding
    // a value that could cause it.
    val knownSource = incoming.filter { it.source != LocationSource.UNKNOWN }
    if (knownSource.size != incoming.size) {
        val dropped = incoming.filter { it.source == LocationSource.UNKNOWN }.map { it.userid.toULong() }.distinct()
        val droppedCount = incoming.size - knownSource.size
        Log.status(
            "FF-Heartbeat",
            "dropped $droppedCount fix(es) from newer peer(s) with an unrecognised source: $dropped"
        )
    }
    // Low-accuracy fixes are never used: anything worse than the intake gate never
    // reaches Room, the map, or latestLocations.
    val locList = knownSource.filter {
        it.acc.isFinite() && it.acc >= 0f && it.acc <= MAX_FIX_ACCURACY_METERS
    }
    if (locList.size != knownSource.size) {
        Log.status(
            "FF-Heartbeat",
            "dropped ${knownSource.size - locList.size} fix(es) worse than ${MAX_FIX_ACCURACY_METERS}m"
        )
    }
    if (locList.isEmpty()) return
    val currentUsers = repository.getAllUsers()
    val userIDs = currentUsers.map { it.id }

    val usersRecieved = locList.map { it.userid }.distinct()
    val receivedIds = usersRecieved.map { it.toULong() }
    val knownIds = userIDs.map { it.toULong() }
    val selfId = Networking.userid.toULong()
    Log.debug("FF-Heartbeat", "received userids=$receivedIds self=$selfId known=$knownIds")
    val newUsers = usersRecieved.filter { it !in userIDs && it != Networking.userid }
    Log.debug("FF-Heartbeat", "newUsers to insert=${newUsers.map{ it.toULong() }}")
    repository.insertUsersIgnore(newUsers.map {
        User(" ", null, "Unknown Location", false, RequestStatus.AWAITING_REQUEST, Clock.System.now(), null, it)
    })

    // Snapshot the previous latest-per-user BEFORE persisting the new fixes, so the
    // battery-threshold and self-waypoint comparisons below still see the prior state
    // rather than the fix we're about to store.
    val latestMap = repository.latestLocationsOnce().associateBy { it.userid }

    // Persist the raw fixes immediately, before any enrichment. Everything after this
    // (waypoint detection, reverse-geocoding via fetchAddress, notifications) is
    // best-effort: the geocoder is a slow network call, any of it can throw, and the
    // whole delivery is cancelled when the live socket reconnects mid-batch (the caller
    // also swallows exceptions). Persisting last meant a slow/failed/cancelled
    // enrichment step silently dropped the location, so getLatest() kept serving stale
    // fixes even across a force-stop. Writing here makes the fix durable no matter what
    // follows.
    repository.upsertLocations(locList)
    Log.debug("FF-Heartbeat", "upsertAll ${locList.size} locations done")

    // Enrichment runs off the reader coroutine. Reverse-geocoding is a slow network
    // call, so doing it inline stalled every subsequent inbound frame until the
    // liveness timeout force-reconnected the socket. The mutex keeps batches
    // serialized, so entry/exit and low-battery alerts still fire once each.
    serviceScope.launch {
        enrichmentMutex.withLock { enrichIncomingLocations(locList, latestMap) }
    }
}

/**
 * Best-effort follow-up to [processIncomingLocations]: recomputes waypoint
 * entry/exit, reverse-geocodes the display name, and raises the entry/exit and
 * low-battery notifications. [latestMap] is the latest-per-user snapshot taken
 * *before* the new fixes were stored, so the comparisons below see the prior state.
 */
internal suspend fun LocationTrackingService.enrichIncomingLocations(
    locList: List<LocationValue>,
    latestMap: Map<Long, LocationValue>,
) {
    val currentUsers = repository.getAllUsers()
    val currentWaypoints = repository.getAllWaypoints()
    currentUsers.forEach { user ->
        enrichOneUser(user, locList, latestMap, currentWaypoints)
    }
}

private suspend fun LocationTrackingService.enrichOneUser(
    user: User,
    locList: List<LocationValue>,
    latestMap: Map<Long, LocationValue>,
    currentWaypoints: List<Waypoint>,
) {
    // Self never receives its own published location, so fall back to the latest
    // stored fix; otherwise "me" never gets its waypoint recomputed.
    val lastLoc = if (user.id == Networking.userid) latestMap[Networking.userid]
    else locList.filter { it.userid == user.id }.maxByOrNull { it.timestamp }
    lastLoc ?: return
    val lastSavedLoc = latestMap[user.id]
    maybeNotifyLowBattery(user, lastLoc, lastSavedLoc)
    val prevId = user.lastWaypointId
    val currentId = resolveWaypointId(prevId, lastLoc, currentWaypoints)
    val currentWaypoint = currentWaypoints.find { it.id == currentId }
    val displayName = resolveDisplayName(currentWaypoint, lastLoc)
    if (currentId != prevId || displayName != user.locationName) {
        // Atomic partial update — avoids stale snapshot via copy() + upsert()
        // clobbering sharingAutoToggleAt / sendingEnabled and accidentally
        // disabling sharing when you didn't intend it.
        repository.updateLocationMeta(
            id = user.id,
            locationName = displayName,
            lastWaypointId = currentId,
            lastLocationChangeTime = lastLoc.timestamp.epochSeconds
        )
    }
    maybeNotifyEntryExit(user, prevId, currentId, currentWaypoint, displayName, currentWaypoints)
}

private fun LocationTrackingService.maybeNotifyLowBattery(
    user: User,
    lastLoc: LocationValue,
    lastSavedLoc: LocationValue?,
) {
    if (lastLoc.battery > LOW_BATTERY_THRESHOLD) return
    if ((lastSavedLoc?.battery ?: FULL_BATTERY) <= LOW_BATTERY_THRESHOLD) return
    if (user.id == Networking.userid) return
    createNotificationWithCategory(
        user.name,
        getString(R.string.notification_low_battery, user.name),
        "BATTERY_LOW",
        user.id
    )
}

private fun LocationTrackingService.resolveWaypointId(
    prevId: Long?,
    lastLoc: LocationValue,
    currentWaypoints: List<Waypoint>,
): Long? {
    val accuracy = lastLoc.acc.toDouble()
    // A fix that cannot say which side of the boundary it is on must not move the
    // answer. A stationary phone on network fixes wanders far enough to cross and
    // re-cross a 100 m geofence every few seconds, and every crossing notified.
    if (accuracy > GEOFENCE_MAX_ACCURACY_METERS) return prevId
    // Entering needs the whole error circle inside; leaving needs it wholly
    // outside the hysteresis margin, so the edge cases stay where they were.
    val entered = currentWaypoints.find {
        havershine(it.coord, lastLoc.coord) + accuracy < it.range
    }
    if (entered != null) return entered.id
    val stillInsidePrev = prevId?.let { pid ->
        currentWaypoints.find { it.id == pid }?.let {
            havershine(it.coord, lastLoc.coord) - accuracy < it.range * WAYPOINT_EXIT_HYSTERESIS
        }
    } ?: false
    return prevId.takeIf { stillInsidePrev }
}

private suspend fun LocationTrackingService.resolveDisplayName(
    currentWaypoint: Waypoint?,
    lastLoc: LocationValue,
): String {
    // Display name: prefer the waypoint we are in, then the geocoded address.
    if (currentWaypoint != null) return currentWaypoint.name
    val address = runCatching {
        fetchAddress(lastLoc.coord.lat, lastLoc.coord.lon)
    }.getOrNull()
    return address?.featureName ?: address?.thoroughfare ?: "Unknown Location"
}

private fun LocationTrackingService.maybeNotifyEntryExit(
    user: User,
    prevId: Long?,
    currentId: Long?,
    currentWaypoint: Waypoint?,
    displayName: String,
    currentWaypoints: List<Waypoint>,
) {
    if (currentId == prevId || user.id == Networking.userid) return
    if (currentId != null) {
        val enteredName = currentWaypoint?.name ?: displayName
        notifyEntryExit(
            user,
            getString(R.string.notification_entered_waypoint, user.name, enteredName),
            arrival = true
        )
    } else if (prevId != null) {
        val exitedName = currentWaypoints.find { it.id == prevId }?.name ?: user.locationName
        notifyEntryExit(
            user,
            getString(R.string.notification_exited_waypoint, user.name, exitedName),
            arrival = false
        )
    }
}

/**
 * Forwards decrypted UWB envelopes to [UwbInbox] and fires a local
 * notification for REQUEST envelopes. Driven by the live WebSocket push.
 *
 * Powered-off recovery grants ride the same channel (see [UwbEnvelopeKind.POF_GRANT]) and are
 * handled here instead, deliberately without reaching [UwbInbox]: they are not ranging
 * traffic and have no business waking the Find Nearby screen.
 */
internal suspend fun LocationTrackingService.handleUwbEnvelopes(list: List<UwbEnvelope>) {
    if (list.isEmpty()) return
    val users = repository.getAllUsers()
    for (envelope in list) {
        when (envelope.kind) {
            UwbEnvelopeKind.POF_GRANT -> acceptPoweredOffGrant(envelope)
            else -> {
                UwbInbox.tryEmit(envelope)
                if (envelope.kind == UwbEnvelopeKind.REQUEST) {
                    val senderId = envelope.sender.toLong()
                    val senderName = users.firstOrNull { it.id == senderId }?.name
                        ?: getString(R.string.uwb_unknown_peer_name)
                    createUwbRequestNotification(senderName, senderId)
                }
            }
        }
    }
}

/**
 * Store a family member's powered-off keys so this device can go and find their lost phone.
 *
 * Everything after this is already built: [com.vayunmathur.findfamily.tracker.PoweredOffKeyStore]
 * is keyed by userid and [pollPoweredOffSightings] already walks every user it can read, so filing
 * the keys here is the entire receiving side.
 *
 * Three reasons to refuse, all of them silent by design — a rejected grant is either an
 * attack or a stale duplicate, and neither is worth a notification:
 *  - the sender is not someone we have a [User] row for, so nobody chose to trust them;
 *  - the signature does not verify against that sender's identity bundle. The envelope is
 *    encrypted to us but its `sender` field is self-declared, so without this check any
 *    connected peer could deliver keys under a different family member's userid and have
 *    every sighting decrypted from them drawn on the map as that person's phone;
 *  - the grant is older than one we already hold, which happens when a revoke-triggered
 *    redistribution overtakes the grant it replaces.
 */
private fun LocationTrackingService.grantContextOrNull(
    envelope: UwbEnvelope,
): Triple<PoweredOffKeyStore, PoweredOffGrant, Long>? {
    val store = poweredOffKeys ?: return null
    val grant = envelope.recovery ?: return null
    val ownerId = envelope.sender.toLong()
    if (ownerId == 0L || ownerId == Networking.userid) return null
    return Triple(store, grant, ownerId)
}

internal suspend fun LocationTrackingService.acceptPoweredOffGrant(envelope: UwbEnvelope) {
    val (store, grant, ownerId) = grantContextOrNull(envelope) ?: return
    if (repository.getUser(ownerId) == null) {
        Log.status(LocationTrackingService.TAG_POWERED_OFF, "recovery grant from unknown sender, ignored")
        return
    }
    val decoded = runCatching {
        Triple(
            Base64.decode(grant.secretB64),
            Base64.decode(grant.recoveryPrivB64),
            Base64.decode(grant.sigB64),
        )
    }.getOrNull() ?: return
    val (secret, recoveryPriv, signature) = decoded

    val signed = poweredOffGrantSigningBytes(
        owner = ownerId,
        recipient = Networking.userid,
        epoch = grant.epoch,
        secret = secret,
        recoveryPrivate = recoveryPriv,
    )
    if (!Networking.verifyFrom(ownerId, signed, signature)) {
        Log.status(LocationTrackingService.TAG_POWERED_OFF, "recovery grant signature did not verify, ignored")
        return
    }
    if (grant.epoch < store.epoch(ownerId)) {
        Log.status(LocationTrackingService.TAG_POWERED_OFF, "ignoring superseded recovery grant (epoch ${grant.epoch})")
        return
    }
    store.save(ownerId, secret, recoveryPriv, grant.epoch)
    Log.status(LocationTrackingService.TAG_POWERED_OFF, "stored recovery keys for a peer (epoch ${grant.epoch})")
}
