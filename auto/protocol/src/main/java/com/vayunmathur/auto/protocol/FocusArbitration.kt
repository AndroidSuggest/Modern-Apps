package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AudioFocusState
import com.vayunmathur.auto.protocol.gal.NavigationFocusType
import com.vayunmathur.auto.protocol.gal.VideoFocusMode

/**
 * Which side currently owns the car screen, normalized from the wire modes.
 *
 * The wire carries four [VideoFocusMode] values; input focus is derived from video focus
 * rather than negotiated separately (gearhead derives it the same way), so the transient
 * and no-input variants fold into these three plus [FocusArbitration.inputAllowed].
 */
enum class VideoFocus {
    /** No focus indication has arrived yet. Nothing streams and nothing injects. */
    NONE,

    /** The car is showing its own UI; the phone must not stream video or inject input. */
    NATIVE,

    /** The phone owns the screen; video streams and input usually flows. */
    PROJECTED,
}

/**
 * What changed in one arbitration step.
 *
 * [GalConnection] watches [FocusArbitration.generation] and forwards changes to the
 * Android side, which mirrors them into `AutoSessionState` without polling.
 */
data class FocusChange(
    val videoChanged: Boolean = false,
    val audioChanged: Boolean = false,
    val navigationChanged: Boolean = false,
) {
    val any: Boolean get() = videoChanged || audioChanged || navigationChanged
}

/**
 * Video/audio/navigation focus arbitration: who owns the screen and the speakers.
 *
 * Pure state machine, no I/O, so the whole preemption matrix is host-testable. Owned by
 * [GalControlSession]: control notifications (audio 0x13, navigation 0x0E) feed it
 * directly, and the video focus indication (0x8008 on the video channel) arrives via
 * `GalControlSession.onVideoFocusIndication`.
 *
 * Semantics, from the gearhead teardown (`FINDINGS.md`):
 *
 * - `VIDEO_FOCUS_PROJECTED` gates both streaming and input.
 * - `VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS` still streams ([shouldStreamVideo]) but input
 *   from the head unit must be dropped ([inputAllowed] is false).
 * - `VIDEO_FOCUS_NATIVE` and `VIDEO_FOCUS_NATIVE_TRANSIENT` both park the stream; the
 *   transient variant is not distinguished here because neither streams.
 * - Audio focus is tracked alongside video: guidance/transient gains decide [mayPlayMedia]
 *   while video stays native (e.g. TTS over the car's own UI). Loss variants silence us.
 */
class FocusArbitration {
    /** Which side owns the screen. Starts at [VideoFocus.NONE] until told otherwise. */
    @Volatile
    var videoFocus: VideoFocus = VideoFocus.NONE
        private set

    /**
     * Whether head-unit input may be injected. Derived from video focus, not negotiated:
     * projected video with input focus only.
     */
    @Volatile
    var inputAllowed: Boolean = false
        private set

    /** Last audio focus state the head unit reported. */
    @Volatile
    var audioFocus: AudioFocusState = AudioFocusState.AUDIO_FOCUS_STATE_INVALID
        private set

    /** Whether navigation is projected (control 0x0E `NAV_FOCUS_PROJECTED`). */
    @Volatile
    var navigationProjected: Boolean = false
        private set

    /**
     * Bumps on every accepted change, so a driver can forward focus without diffing
     * three fields.
     */
    @Volatile
    var generation: Long = 0
        private set

    /** Whether video frames may go out: projected focus, transient or not. */
    val shouldStreamVideo: Boolean get() = videoFocus == VideoFocus.PROJECTED

    /**
     * Whether phone audio may play: an explicit gain, or projected video as the
     * implicit grant. Checked by the audio sinks before starting a stream.
     */
    val mayPlayMedia: Boolean get() = when (audioFocus) {
        AudioFocusState.AUDIO_FOCUS_STATE_GAIN,
        AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
        AudioFocusState.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY,
        AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT_GUIDANCE_ONLY,
        -> true
        // LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK and INVALID silence us.
        // Ducking (CAN_DUCK) is a sink-side volume decision, not a play gate.
        else -> videoFocus == VideoFocus.PROJECTED
    }

    /**
     * Folds a 0x8008 video focus indication into the state. Absent modes are ignored:
     * the lite runtime drops unknown enum values on parse, so a future mode arrives
     * as absent rather than as a wrong known one -- never clobber state with it.
     */
    fun onVideoFocus(mode: VideoFocusMode?): FocusChange {
        if (mode == null) return FocusChange()
        val (focus, input) = when (mode) {
            VideoFocusMode.VIDEO_FOCUS_PROJECTED -> VideoFocus.PROJECTED to true
            VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS -> VideoFocus.PROJECTED to false
            VideoFocusMode.VIDEO_FOCUS_NATIVE,
            VideoFocusMode.VIDEO_FOCUS_NATIVE_TRANSIENT,
            -> VideoFocus.NATIVE to false
        }
        if (focus == videoFocus && input == inputAllowed) return FocusChange()
        videoFocus = focus
        inputAllowed = input
        generation++
        return FocusChange(videoChanged = true)
    }

    /** Folds a control 0x13 audio focus notification into the state. */
    fun onAudioFocus(state: AudioFocusState): FocusChange {
        if (state == audioFocus) return FocusChange()
        audioFocus = state
        generation++
        return FocusChange(audioChanged = true)
    }

    /** Folds a control 0x0E navigation focus notification into the state. */
    fun onNavigationFocus(focus: NavigationFocusType): FocusChange {
        val projected = focus == NavigationFocusType.NAV_FOCUS_PROJECTED
        if (projected == navigationProjected) return FocusChange()
        navigationProjected = projected
        generation++
        return FocusChange(navigationChanged = true)
    }

    /** A new socket means a new session: no focus survives a reconnect. */
    fun reset() {
        videoFocus = VideoFocus.NONE
        inputAllowed = false
        audioFocus = AudioFocusState.AUDIO_FOCUS_STATE_INVALID
        navigationProjected = false
        generation++
    }
}
