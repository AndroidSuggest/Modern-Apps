package com.vayunmathur.cast.platform

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.mirror.MirrorDegradation
import com.vayunmathur.cast.platform.mirror.MirrorEngine
import com.vayunmathur.cast.platform.mirror.MirrorGeometry
import com.vayunmathur.cast.platform.mirror.MirrorSource
import com.vayunmathur.cast.service.CastService
import com.vayunmathur.sdk.cast.CastContract
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private const val TAG = "CastController"

/**
 * Begin mirroring with an already-granted projection.
 *
 * Called from [CastService] rather than from the UI, because the projection may only be obtained
 * after the service is in the foreground - see `MirrorConsentActivity` for the full ordering
 * constraint.
 */
fun CastController.startMirroring(context: Context, projection: MediaProjection) =
    startSession(context, MirrorSource.Screen(projection), projection)

/**
 * Cast a SYSTEM-OWNED display instead of a mirror of the phone - "desktop mode".
 *
 * The difference from [startMirroring] is what the TV receives. That one takes a
 * `MediaProjection` and sends a copy of the phone's screen; this creates a separate display
 * that the window manager can place activities on, so the TV becomes a second desktop. Whether
 * it ends up mirroring or extending is then the SYSTEM's choice, not ours: the display carries
 * `ALLOWS_CONTENT_MODE_SWITCH` and Settings already draws the switch for it, which is why this
 * app offers no chooser.
 *
 * No consent Activity, because nothing here captures the screen. It needs `ADD_TRUSTED_DISPLAY`
 * instead, held via the SYSTEM_AUTOMOTIVE_PROJECTION role pinned in `MaosFrameworkResRRO`. On a
 * build without that role `createVirtualDisplay` throws and the session fails at
 * [MirrorEngine.start], which is the honest place for a packaging problem to surface.
 *
 * [onDisplayId] fires once the display exists, with the framework display id, so a caller that
 * published a route can hand the id to `RemoteDisplay.setPresentationDisplayId`.
 */
fun CastController.startDesktopMode(context: Context, onDisplayId: (Int) -> Unit = {}) =
    startSession(context, MirrorSource.SystemDisplay(), projection = null, onDisplayId)

/**
 * Shared body of [startMirroring] and [startDesktopMode].
 *
 * [projection] is non-null only for the screen path, and exists solely so the failure routes
 * can stop it; the desktop path has nothing to release but its own display, which
 * [MirrorEngine] owns.
 */
internal fun CastController.startSession(
    context: Context,
    source: MirrorSource,
    projection: MediaProjection?,
    onDisplayId: (Int) -> Unit = {},
) {
    val appContext = context.applicationContext
    scope.launch {
        val activeClient = client
        val device = _device.value
        if (activeClient == null || device == null) {
            Log.w(TAG, "asked to mirror with no session")
            projection?.stop()
            return@launch
        }
        // The screen and an app's content are mutually exclusive - there is one session, one
        // encoder and one socket - so whichever was running loses, with the SDK client told why
        // rather than left drawing into a dead surface.
        endContentSession(CastContract.REASON_PREEMPTED)
        stopEngine()
        _mirrorPhase.value = MirrorPhase.Negotiating
        _degradation.value = MirrorDegradation()
        _failure.value = null

        // The codec comes first, because everything else is chosen against it: the frame size fits
        // *that* codec's envelope on the TV, and the bitrate is that codec's efficiency applied to
        // the H.264 reference. There is no H.264 fallback behind this - a phone or a TV without one
        // of the two hardware codecs is told which were missing and mirroring stops here.
        val (screenWidth, screenHeight) = MirrorGeometry.screenSize(appContext)
        // Mirroring encodes the phone's screen, so the codec is chosen for that size. A desktop
        // is composed at the TV's panel resolution, so choose the codec for the TV's largest
        // mode instead: at 4K that excludes this phone's AV1 encoder (capped well below 4K) and
        // selects H.265 (which reaches it), which is what lets the real panel resolutions reach
        // the picker rather than an encoder-clamped one. Falls back to the screen size if
        // nothing can encode the TV's largest, so a lower real mode still works.
        val desktopMax = (source as? MirrorSource.SystemDisplay)?.let {
            activeClient.displayModes.maxByOrNull { m -> m.width.toLong() * m.height }
        }
        val codec = when (
            val choice = chooseCodec(
                appContext, device, activeClient,
                desktopMax?.width ?: screenWidth,
                desktopMax?.height ?: screenHeight,
            )
        ) {
            is CodecOutcome.Chosen -> choice
            is CodecOutcome.Refused -> {
                val retry = if (desktopMax != null) {
                    chooseCodec(appContext, device, activeClient, screenWidth, screenHeight)
                } else {
                    choice
                }
                when (retry) {
                    is CodecOutcome.Chosen -> retry
                    is CodecOutcome.Refused -> {
                        Log.w(TAG, "refusing to mirror: ${retry.message}")
                        abandonMirroring(appContext, projection, retry.message)
                        return@launch
                    }
                }
            }
        }

        if (source is MirrorSource.SystemDisplay) {
            // Before the engine builds the display: the unique id is fixed at creation and is
            // what every persisted preference for this television is keyed on.
            source.receiverId = activeClient.receiverId ?: device.id
        }
        // The frame size is chosen from the TV's own reported limits. For mirroring it is the
        // phone's real aspect ratio - the receiver letterboxes, so none of the encoded frame is
        // wasted on bars. A desktop is composed for the television instead, because the system
        // lays it out for whatever size the display was created at rather than reproducing the
        // phone.
        val geometry = if (source is MirrorSource.SystemDisplay) {
            val desktopModes =
                MirrorGeometry.desktopModes(appContext, codec.selection, activeClient.displayModes)
            source.supportedModes = desktopModes
            desktopModes.first()
        } else {
            MirrorGeometry.forDisplay(appContext, codec.selection)
        }
        val frameRate = geometry.frameRate
        val outcome = mutex.withLock {
            activeClient.configureStream(
                width = geometry.width,
                height = geometry.height,
                frameRate = frameRate,
                bitRate = geometry.bitRate,
                videoCodec = codec.codec,
                audio = true,
                video = true,
            )
        }
        val ready = outcome as? HandshakeOutcome.Ready
        if (ready == null) {
            Log.w(TAG, "the TV would not agree a stream: $outcome")
            abandonMirroring(
                appContext,
                projection,
                appContext.getString(R.string.cast_mirror_negotiation_failed),
            )
            return@launch
        }

        val newEngine = MirrorEngine(
            context = appContext,
            source = source,
            receiverHost = device.host,
            negotiation = ready.negotiation,
            geometry = geometry,
            videoCodec = codec.codec,
            frameRate = frameRate,
            onDegraded = { _degradation.value = it },
            onStopped = { reason -> onEngineStopped(appContext, reason) },
            onCodecConfig = { csd -> sendCodecConfig(activeClient, csd) },
        ).apply { hexDump = verboseStreamLogging }
        engine = newEngine
        activeCodec = codec.codec
        activeGeometry = geometry
        if (newEngine.start()) {
            // The framework never discovers this display on its own - MediaRouterService
            // only reads back an id the provider published. See CastSystemDisplay.
            if (source is MirrorSource.SystemDisplay) {
                desktopSource = source
                onDisplayId(source.displayId)
                watchDisplay(appContext, source)
            }
            _mirrorPhase.value = MirrorPhase.Mirroring
            _sessionState.update {
                it.copy(phase = ClientPhase.Streaming, negotiation = ready.negotiation)
            }
            // A mirror or desktop session has no inbound control traffic at all, so without a
            // heartbeat the socket's read deadline is what ends it. See [startWatch].
            startWatch(appContext, activeClient, device, codec.codec, keepAlive = true)
        } else {
            // start() already called onStopped, which set the message and the phase; all that is
            // left is to make sure nothing keeps holding the screen.
            engine = null
            activeCodec = null
            runCatching { projection?.stop() }
        }
    }
}

/** Give up on mirroring, and make sure the screen stops being captured. */
internal fun CastController.abandonMirroring(
    context: Context,
    projection: MediaProjection?,
    message: String,
) {
    _mirrorPhase.value = MirrorPhase.Failed
    _failure.value = message
    runCatching { projection?.stop() }
    CastService.stopMirroring(context)
}
