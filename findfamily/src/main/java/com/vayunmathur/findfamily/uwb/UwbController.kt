package com.vayunmathur.findfamily.uwb

import android.content.Context
import android.os.Build
import android.ranging.DataNotificationConfig
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.SensorFusionParams
import android.ranging.SessionConfig
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import android.ranging.raw.RawResponderRangingConfig
import android.ranging.uwb.UwbAddress
import android.ranging.uwb.UwbComplexChannel
import android.ranging.uwb.UwbRangingParams
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.io.encoding.Base64
import kotlin.random.Random

/**
 * Thin wrapper around `android.ranging.RangingManager` (the public AOSP
 * ranging API introduced in Android 16 / API 36).
 *
 * This API is the on-device, third-party-callable replacement for the
 * legacy `androidx.core.uwb` library, which only ever shipped a GMS-mediated
 * backend and therefore doesn't work on GrapheneOS / non-GMS Android.
 *
 * One instance per ranging session — call [openController] OR [openControlee]
 * to generate the local FiRa params, then [stream] to actually start ranging
 * once the peer's params have been exchanged out-of-band.
 *
 * Every entry point into this class is gated by
 * [com.vayunmathur.findfamily.util.UwbSessionManager.isSupportedSdk].
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)  // API 36 / Android 16
class UwbController(context: Context) {

    private val manager: RangingManager? =
        context.getSystemService(RangingManager::class.java)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var session: RangingSession? = null
    private val _state: MutableStateFlow<State> = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The configuration tier we last successfully opened a session with on
     * this device. Cached so we don't keep retrying the rejected config on
     * every ranging session. -1 = haven't tried yet.
     */
    private var lastWorkingTier: Int = -1

    sealed interface State {
        data object Idle : State
        data object Opening : State
        data class Ready(val localAddress: ByteArray) : State
        data class Failed(val reason: String) : State
    }

    /**
     * "Open" as the initiator. Generates local FiRa parameters (address,
     * channel, preamble, session id, session key). No system call yet —
     * `RangingManager.createRangingSession()` happens in [stream] once we
     * know the peer's address from the ACK envelope.
     */
    fun openController(): Result<ControllerInfo> {
        if (manager == null) {
            val msg = "RangingManager system service unavailable"
            _state.value = State.Failed(msg)
            return Result.failure(IllegalStateException(msg))
        }
        _state.value = State.Opening
        // Generate our local UWB MAC address (2 bytes). Avoid the reserved
        // FiRa addresses 0x00/0xFF.
        val localAddress = ByteArray(2).also {
            Random.nextBytes(it)
            if (it[0] == 0.toByte() && it[1] == 0.toByte()) it[1] = 1
        }
        val info = ControllerInfo(
            localAddress = localAddress,
            channelNumber = DEFAULT_CHANNEL,
            preambleIndex = DEFAULT_PREAMBLE,
            sessionId = Random.nextInt(),
            sessionKey = Random.nextBytes(8),
            uuid = UUID.randomUUID()
        )
        _state.value = State.Ready(info.localAddress)
        return Result.success(info)
    }

    /**
     * "Open" as the responder. Generates the local UWB MAC address only —
     * all other params come from the peer's REQUEST envelope.
     */
    fun openControlee(): Result<ByteArray> {
        if (manager == null) {
            val msg = "RangingManager system service unavailable"
            _state.value = State.Failed(msg)
            return Result.failure(IllegalStateException(msg))
        }
        _state.value = State.Opening
        val addr = ByteArray(2).also {
            Random.nextBytes(it)
            if (it[0] == 0.toByte() && it[1] == 0.toByte()) it[1] = 1
        }
        _state.value = State.Ready(addr)
        return Result.success(addr)
    }

    /**
     * Begin ranging. Builds the [RangingPreference] from the agreed FiRa
     * params and opens a `RangingSession` through `RangingManager`. The
     * returned [Flow] emits a sample for each `onResults` callback and a
     * "peer disconnected" marker for `onStopped` / `onClosed`. Cancelling
     * collection or calling [stop] closes the session.
     *
     * @param role one of [Role.Initiator] / [Role.Responder] — drives the
     *   `RangingPreference.DEVICE_ROLE_*` selection.
     * @param localAddress this device's MAC address (2 bytes).
     * @param peerAddress the peer's MAC address (2 bytes).
     * @param sessionId same on both ends; chosen by the initiator.
     * @param sessionKey same 8 bytes on both ends; chosen by the initiator.
     * @param channelNumber same on both ends; chosen by the initiator.
     * @param preambleIndex same on both ends; chosen by the initiator.
     * @param peerUuid an arbitrary unique id used by `android.ranging` to
     *   key the peer in callbacks — same on both ends would be nice but not
     *   required; we use a deterministic-ish UUID derived from sessionId.
     */
    fun stream(
        role: Role,
        localAddress: ByteArray,
        peerAddress: ByteArray,
        sessionId: Int,
        sessionKey: ByteArray,
        channelNumber: Int,
        preambleIndex: Int,
        peerUuid: UUID = UUID(0L, sessionId.toLong())
    ): Flow<RangingSample> = callbackFlow {
        val mgr = manager ?: run {
            trySend(
                RangingSample(
                    null, null, null, System.nanoTime(),
                    peerDisconnected = true
                )
            )
            close()
            return@callbackFlow
        }

        val localUwbAddress = try {
            UwbAddress.fromBytes(localAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UwbAddress from localAddress", e)
            trySend(
                RangingSample(
                    null, null, null, System.nanoTime(),
                    peerDisconnected = true
                )
            )
            close(IllegalStateException("Invalid local UWB address", e))
            return@callbackFlow
        }
        val peerUwbAddress = try {
            UwbAddress.fromBytes(peerAddress)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UwbAddress from peerAddress", e)
            trySend(
                RangingSample(
                    null, null, null, System.nanoTime(),
                    peerDisconnected = true
                )
            )
            close(IllegalStateException("Invalid peer UWB address", e))
            return@callbackFlow
        }
        val uwbParams = UwbRangingParams.Builder(
            /* sessionId = */ sessionId,
            /* configId = */ UwbRangingParams.CONFIG_UNICAST_DS_TWR,
            /* deviceAddress = */ localUwbAddress,
            /* peerAddress = */ peerUwbAddress
        )
            .setComplexChannel(
                UwbComplexChannel.Builder()
                    .setChannel(channelNumber)
                    .setPreambleIndex(preambleIndex)
                    .build()
            )
            .setSessionKeyInfo(sessionKey)
            // ~5 Hz instead of the default ~1 Hz. Smoother distance/direction
            // updates and seems to keep the radio from settling on a stale
            // sample when the peer briefly drops out of forward FOV.
            // (The UPDATE_RATE_* constants live on RawRangingDevice, not on
            // UwbRangingParams — the setter is on UwbRangingParams.Builder.)
            .setRangingUpdateRate(RawRangingDevice.UPDATE_RATE_FREQUENT)
            // (We intentionally don't call setSlotDuration here — DURATION_1_MS
            // is unsupported by older UWB stacks like the Pixel 7 Pro's and
            // causes the whole session to fail with REASON_UNSUPPORTED. Let
            // the radio pick its default slot duration.)
            .build()

        val rawDevice = RawRangingDevice.Builder()
            .setRangingDevice(RangingDevice.Builder().setUuid(peerUuid).build())
            .setUwbRangingParams(uwbParams)
            .build()

        // Try the richest SessionConfig that the device hasn't rejected.
        // Tier 2 = AoA + DIRECTIONAL antenna + IMU sensor fusion (best AoA — this
        //         is what Find My / Apple Find Nearby uses to disambiguate
        //         the front/back hemisphere and stabilise the arrow).
        // Tier 1 = AoA + IMU sensor fusion only (drop the antenna mode flag).
        // Tier 0 = AoA only (Pixel 7 Pro et al. only accept this).
        // We start one tier above whatever last worked (in case the radio became
        // available again) and degrade until the session opens successfully.
        val startTier = if (lastWorkingTier >= 0) (lastWorkingTier + 1).coerceAtMost(2) else 2
        Log.i(TAG, "stream: opening session at tier=$startTier (last working was $lastWorkingTier)")
        var currentTier = startTier

        val callback = object : RangingSession.Callback {
            override fun onOpened() {
                Log.i(TAG, "RangingSession.onOpened (tier=$currentTier)")
                lastWorkingTier = currentTier
            }
            override fun onOpenFailed(reason: Int) {
                Log.e(TAG, "RangingSession.onOpenFailed(reason=$reason, tier=$currentTier). Reasons: 0=UNKNOWN 1=LOCAL_REQUEST 2=REMOTE_REQUEST 3=UNSUPPORTED 4=SYSTEM_POLICY 5=NO_PEERS_FOUND")
                // If REASON_UNSUPPORTED, this config tier was rejected by the
                // radio. Drop one tier and retry, until we hit tier 0 (AoA-only).
                if (reason == 3 && currentTier > 0) {
                    currentTier -= 1
                    Log.w(TAG, "Retrying at tier=$currentTier")
                    runCatching { session?.close() }
                    val newConfig = try {
                        preferenceForTier(currentTier, role, rawDevice)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create preference for tier $currentTier", e)
                        null
                    }
                    if (newConfig != null) {
                        val newSession = try {
                            mgr.createRangingSession(executor, this)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create RangingSession during retry", e)
                            null
                        }
                        if (newSession != null) {
                            session = newSession
                            try {
                                newSession.start(newConfig)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start RangingSession during retry", e)
                            }
                        }
                    }
                    return
                }
                trySend(
                    RangingSample(
                        null, null, null, System.nanoTime(),
                        peerDisconnected = true
                    )
                )
                close(IllegalStateException("RangingSession open failed (reason=$reason)"))
            }
            override fun onStarted(peer: RangingDevice, technology: Int) {}
            override fun onStopped(peer: RangingDevice, technology: Int) {
                trySend(
                    RangingSample(
                        null, null, null, System.nanoTime(),
                        peerDisconnected = true
                    )
                )
            }
            override fun onClosed(reason: Int) {
                trySend(
                    RangingSample(
                        null, null, null, System.nanoTime(),
                        peerDisconnected = true
                    )
                )
                close()
            }
            override fun onResults(peer: RangingDevice, data: RangingData) {
                try {
                    val d = data.distance
                    val az = data.azimuth
                    val el = data.elevation
                    // RangingMeasurement.measurement is in RADIANS on android.ranging.
                    val rawAzDeg = az?.measurement?.let { Math.toDegrees(it).toFloat() }
                    // 2-antenna AoA (Pixel 9 Pro XL etc.) has an inherent
                    // front/back ambiguity — for a peer at azimuth α the radio
                    // CAN'T distinguish it from a peer at (180° − α). So the
                    // reading oscillates between e.g. 0° and 180°. Fold every
                    // sample into the front hemisphere [-90°, +90°]; we assume
                    // the user is pointing the phone at the peer (otherwise why
                    // open the Find screen?). 3-antenna arrays (Pixel 7 Pro)
                    // already give values inside [-90°, +90°] so the fold is a
                    // no-op there.
                    val foldedAzDeg = rawAzDeg?.let { v ->
                        when {
                            v > 90f -> 180f - v
                            v < -90f -> -180f - v
                            else -> v
                        }
                    }
                    // Filter out low-confidence individual measurements per-axis.
                    // CONFIDENCE_LOW=0, CONFIDENCE_MEDIUM=1, CONFIDENCE_HIGH=2.
                    // We keep MEDIUM and HIGH; drop LOW (the radio's own "this
                    // is junk" signal). Each axis is filtered independently so
                    // we can show distance even when the angles are noisy.
                    val distOk = (d?.confidence ?: 0) >= 1
                    val azOk = (az?.confidence ?: 0) >= 1
                    val elOk = (el?.confidence ?: 0) >= 1
                    Log.i(
                        TAG,
                        "onResults: dist=${d?.measurement} (conf=${d?.confidence})  raw_az=$rawAzDeg (conf=${az?.confidence})  folded_az=$foldedAzDeg  el=${el?.measurement} (conf=${el?.confidence})  rssi=${runCatching { data.rssi }.getOrNull()}"
                    )
                    trySend(
                        RangingSample(
                            distanceMeters = if (distOk) d?.measurement?.toFloat() else null,
                            azimuthDeg = if (azOk) foldedAzDeg else null,
                            elevationDeg = if (elOk) el?.measurement?.let { Math.toDegrees(it).toFloat() } else null,
                            timestampNanos = System.nanoTime()
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing RangingData in onResults", e)
                    // Continue processing - don't crash on malformed data
                }
            }
        }

        val newSession = try {
            mgr.createRangingSession(executor, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create RangingSession", e)
            null
        }
        if (newSession == null) {
            trySend(
                RangingSample(
                    null, null, null, System.nanoTime(),
                    peerDisconnected = true
                )
            )
            close(IllegalStateException("RangingManager.createRangingSession returned null or threw exception"))
            return@callbackFlow
        }
        session = newSession
        try {
            newSession.start(preferenceForTier(startTier, role, rawDevice))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RangingSession", e)
            trySend(
                RangingSample(
                    null, null, null, System.nanoTime(),
                    peerDisconnected = true
                )
            )
            close(IllegalStateException("Failed to start RangingSession", e))
            return@callbackFlow
        }

        awaitClose {
            runCatching { session?.stop() }
            runCatching { session?.close() }
            session = null
        }
    }

    /**
     * Build the SessionConfig + RangingPreference for the given tier:
     *
     *  tier 2: AoA + DIRECTIONAL antenna mode + IMU sensor fusion
     *          (best AoA quality — hemisphere-disambiguated, smooth)
     *  tier 1: AoA + IMU sensor fusion
     *  tier 0: AoA only (works on every device but ambiguous on 2-antenna arrays)
     *
     * If the radio rejects a higher tier with REASON_UNSUPPORTED we drop one
     * and retry. This way Pixel 7 Pro lands on tier 0 (its UWB stack rejects
     * antenna mode + sensor fusion) while Pixel 9 Pro XL gets the full
     * Find-My-grade tier 2.
     */
    private fun preferenceForTier(
        tier: Int,
        role: Role,
        rawDevice: RawRangingDevice,
    ): RangingPreference {
        val sessionConfig = SessionConfig.Builder()
            .setAngleOfArrivalNeeded(true)
            .apply {
                // setAntennaMode only exists from API 37; below that tier 2
                // degrades to the same config as tier 1.
                if (tier >= 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                    runCatching { setAntennaMode(SessionConfig.ANTENNA_MODE_DIRECTIONAL) }
                        .onFailure { Log.w(TAG, "setAntennaMode unavailable: ${it.message}") }
                }
                if (tier >= 1) {
                    runCatching {
                        setSensorFusionParams(
                            SensorFusionParams.Builder()
                                .setSensorFusionEnabled(true)
                                .build()
                        )
                    }.onFailure { Log.w(TAG, "setSensorFusionParams unavailable: ${it.message}") }
                }
            }
            .build()
        return when (role) {
            Role.Initiator -> RangingPreference.Builder(
                RangingPreference.DEVICE_ROLE_INITIATOR,
                RawInitiatorRangingConfig.Builder()
                    .addRawRangingDevice(rawDevice)
                    .build()
            )
                .setSessionConfig(sessionConfig)
                .build()
            Role.Responder -> RangingPreference.Builder(
                RangingPreference.DEVICE_ROLE_RESPONDER,
                RawResponderRangingConfig.Builder()
                    .setRawRangingDevice(rawDevice)
                    .build()
            )
                .setSessionConfig(sessionConfig)
                .build()
        }
    }

    fun stop() {
        runCatching { session?.stop() }
        runCatching { session?.close() }
        session = null
        executor.shutdown()
        _state.value = State.Idle
    }

    data class ControllerInfo(
        val localAddress: ByteArray,
        val channelNumber: Int,
        val preambleIndex: Int,
        val sessionId: Int,
        val sessionKey: ByteArray,
        val uuid: UUID,
    )

    enum class Role { Initiator, Responder }

    companion object {
        private const val TAG = "UwbController"
        /** FiRa channel 9 (the most common UWB channel; supported by every iPhone with U1/U2). */
        const val DEFAULT_CHANNEL: Int = 9
        /** Default FiRa preamble code index that pairs with channel 9. */
        const val DEFAULT_PREAMBLE: Int = 10
    }
}

/**
 * Simplified per-update sample emitted by [UwbController.stream].
 *
 * - [distanceMeters]: null when the latest event is a "peer disconnected" marker.
 * - [azimuthDeg] / [elevationDeg]: null when direction information isn't
 *   available (peer outside forward FOV, or device lacks AoA support).
 */
data class RangingSample(
    val distanceMeters: Float?,
    val azimuthDeg: Float?,
    val elevationDeg: Float?,
    val timestampNanos: Long,
    val peerDisconnected: Boolean = false
)

/** Helpers for byte-array <-> base64 round-tripping in [UwbHandshake]. */
internal object UwbBytes {
    fun b64(bytes: ByteArray): String = Base64.encode(bytes)
    fun from(b64: String): ByteArray = Base64.decode(b64)
}
