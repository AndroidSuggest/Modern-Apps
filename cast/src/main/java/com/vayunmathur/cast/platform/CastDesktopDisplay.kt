package com.vayunmathur.cast.platform

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.mirror.MirrorEngine
import com.vayunmathur.cast.platform.mirror.MirrorSource
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

private const val TAG = "CastController"

/**
 * How far apart two frame rates may be and still be the same mode.
 *
 * The framework reports a declared mode's rate back as the float it was given, and the panel's own
 * numbers are 59.94006 and 23.976025, so exact equality is not something to compare on. Small
 * enough that 59.94 and 60 stay distinct.
 */
internal const val MODE_RATE_TOLERANCE = 0.2f

/**
 * Follow the desktop display's mode, because the user changes it from Settings and not here.
 *
 * Android's external-display resolution picker calls straight into the framework: the display
 * is resized and its mode replaced, and the app that owns it is told only through the ordinary
 * `DisplayListener`. Nothing was listening, so the encoder and the TV kept running the
 * geometry the session started at while the framework composed the desktop at the new one -
 * the picker appeared to work and changed nothing that could be seen.
 */
internal fun CastController.watchDisplay(context: Context, source: MirrorSource.SystemDisplay) {
    stopWatchingDisplay()
    val displays = context.getSystemService(DisplayManager::class.java) ?: return
    val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != source.displayId) return
            val mode = displays.getDisplay(displayId)?.mode ?: return
            onDesktopModeChanged(context, source, mode)
        }
    }
    displays.registerDisplayListener(listener, null)
    displayListener = listener
    displayManager = displays
}

internal fun CastController.stopWatchingDisplay() {
    renegotiateJob?.cancel()
    renegotiateJob = null
    val listener = displayListener ?: return
    displayListener = null
    runCatching { displayManager?.unregisterDisplayListener(listener) }
    displayManager = null
}

/**
 * Re-negotiate the stream around a mode the user picked.
 *
 * **Everything except the display is rebuilt.** A `MediaCodec` cannot be resized, so the
 * encoder and the RTP session have to go; the display cannot be, because it is the desktop -
 * recreating it would destroy every window on it and hand out a new `displayId` that the
 * Settings page the user is standing on no longer refers to. So the new encoder's surface is
 * attached to the display that is already there.
 *
 * Ignores anything that is not actually a change, which is not an optimisation: our own resize
 * fires this same listener, and without the guard each change would trigger the next.
 */
internal fun CastController.onDesktopModeChanged(
    context: Context,
    source: MirrorSource.SystemDisplay,
    mode: Display.Mode,
) {
    val running = activeGeometry ?: return
    val target = source.supportedModes.firstOrNull {
        it.width == mode.physicalWidth &&
            it.height == mode.physicalHeight &&
            abs(it.frameRate - mode.refreshRate) <= MODE_RATE_TOLERANCE
    } ?: return
    if (target.width == running.width &&
        target.height == running.height &&
        abs(target.frameRate - running.frameRate) <= MODE_RATE_TOLERANCE
    ) {
        return
    }
    if (renegotiateJob?.isActive == true) return
    renegotiateJob = scope.launch {
        val activeClient = client ?: return@launch
        val device = _device.value ?: return@launch
        val codec = activeCodec ?: return@launch
        Log.i(
            TAG,
            "the user chose ${target.width}x${target.height}@${target.frameRate}; " +
                "re-negotiating from ${running.width}x${running.height}@${running.frameRate}",
        )
        // Only the engine, so the display - and the desktop on it - stays exactly where it is.
        engine?.stop()
        engine = null
        // `reconfigureStream`, not `configureStream`: the watch job is parked in a blocking
        // read on this socket and would swallow the reply. See MirrorClient for the hand-off.
        val outcome = mutex.withLock {
            activeClient.reconfigureStream(
                width = target.width,
                height = target.height,
                frameRate = target.frameRate,
                bitRate = target.bitRate,
                videoCodec = codec,
                audio = true,
                video = true,
            )
        }
        val ready = outcome as? HandshakeOutcome.Ready
        if (ready == null) {
            Log.w(TAG, "the TV would not agree the new mode: $outcome")
            abandonMirroring(
                context,
                null,
                context.getString(R.string.cast_mirror_negotiation_failed),
            )
            return@launch
        }
        val newEngine = MirrorEngine(
            context = context,
            source = source,
            receiverHost = device.host,
            negotiation = ready.negotiation,
            geometry = target,
            videoCodec = codec,
            frameRate = target.frameRate,
            onDegraded = { _degradation.value = it },
            onStopped = { reason -> onEngineStopped(context, reason) },
            onCodecConfig = { csd -> sendCodecConfig(activeClient, csd) },
        ).apply { hexDump = verboseStreamLogging }
        engine = newEngine
        activeGeometry = target
        if (!newEngine.start()) {
            engine = null
            activeGeometry = null
            return@launch
        }
        _sessionState.update {
            it.copy(phase = ClientPhase.Streaming, negotiation = ready.negotiation)
        }
    }
}
