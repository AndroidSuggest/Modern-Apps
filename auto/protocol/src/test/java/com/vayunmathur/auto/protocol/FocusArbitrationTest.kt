package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AudioFocusState
import com.vayunmathur.auto.protocol.gal.NavigationFocusType
import com.vayunmathur.auto.protocol.gal.VideoFocusIndication
import com.vayunmathur.auto.protocol.gal.VideoFocusMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The focus preemption matrix, host-tested: video modes gate streaming and input, audio
 * focus decides playback while video stays native, navigation tracks its own flag.
 */
class FocusArbitrationTest {

    @Test
    fun `starts parked with nothing allowed`() {
        val focus = FocusArbitration()

        assertEquals(VideoFocus.NONE, focus.videoFocus)
        assertFalse(focus.inputAllowed)
        assertFalse(focus.shouldStreamVideo)
        assertFalse(focus.mayPlayMedia)
        assertFalse(focus.navigationProjected)
    }

    @Test
    fun `projected focus streams and allows input`() {
        val focus = FocusArbitration()

        val change = focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED)

        assertTrue(change.videoChanged)
        assertTrue(change.any)
        assertEquals(VideoFocus.PROJECTED, focus.videoFocus)
        assertTrue(focus.inputAllowed)
        assertTrue(focus.shouldStreamVideo)
    }

    @Test
    fun `projected without input focus still streams but blocks input`() {
        // gearhead derives input focus from video focus rather than negotiating it:
        // the stream keeps flowing while head-unit touches must be dropped.
        val focus = FocusArbitration()

        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS)

        assertEquals(VideoFocus.PROJECTED, focus.videoFocus)
        assertTrue(focus.shouldStreamVideo)
        assertFalse(focus.inputAllowed)
    }

    @Test
    fun `native and transient both park the stream`() {
        val focus = FocusArbitration()

        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED)
        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_NATIVE)
        assertEquals(VideoFocus.NATIVE, focus.videoFocus)
        assertFalse(focus.shouldStreamVideo)
        assertFalse(focus.inputAllowed)

        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED)
        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_NATIVE_TRANSIENT)
        assertEquals(VideoFocus.NATIVE, focus.videoFocus)
        assertFalse(focus.shouldStreamVideo)
    }

    @Test
    fun `unknown and absent modes never clobber known state`() {
        val focus = FocusArbitration()
        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED)
        val generation = focus.generation

        // The lite runtime drops unknown enum values on parse, so a future mode
        // arrives as an absent one; the machine ignores it.
        assertEquals(FocusChange(), focus.onVideoFocus(null))

        assertEquals(VideoFocus.PROJECTED, focus.videoFocus)
        assertTrue(focus.inputAllowed)
        assertEquals(generation, focus.generation)
    }

    @Test
    fun `audio gain allows media while video stays native`() {
        // TTS over the car's own UI: video parked, speakers ours.
        val focus = FocusArbitration()
        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_NATIVE)

        focus.onAudioFocus(AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT)

        assertTrue(focus.mayPlayMedia)
        assertFalse(focus.shouldStreamVideo)
    }

    @Test
    fun `audio loss silences media without projected video`() {
        val focus = FocusArbitration()
        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_NATIVE)
        focus.onAudioFocus(AudioFocusState.AUDIO_FOCUS_STATE_GAIN)

        val change = focus.onAudioFocus(AudioFocusState.AUDIO_FOCUS_STATE_LOSS)

        assertTrue(change.audioChanged)
        assertFalse(focus.mayPlayMedia)
    }

    @Test
    fun `projected video is the implicit grant when audio is silent`() {
        val focus = FocusArbitration()
        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED)

        assertTrue(focus.mayPlayMedia)
    }

    @Test
    fun `navigation focus tracks its own flag`() {
        val focus = FocusArbitration()

        val change = focus.onNavigationFocus(NavigationFocusType.NAV_FOCUS_PROJECTED)

        assertTrue(change.navigationChanged)
        assertTrue(focus.navigationProjected)

        focus.onNavigationFocus(NavigationFocusType.NAV_FOCUS_NATIVE)
        assertFalse(focus.navigationProjected)
    }

    @Test
    fun `duplicate notifications do not bump the generation`() {
        val focus = FocusArbitration()
        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED)
        focus.onAudioFocus(AudioFocusState.AUDIO_FOCUS_STATE_GAIN)
        val generation = focus.generation

        assertEquals(FocusChange(), focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED))
        assertEquals(
            FocusChange(),
            focus.onAudioFocus(AudioFocusState.AUDIO_FOCUS_STATE_GAIN),
        )
        assertEquals(
            FocusChange(),
            focus.onNavigationFocus(NavigationFocusType.NAV_FOCUS_NATIVE),
        )

        assertEquals(generation, focus.generation)
    }

    @Test
    fun `reset clears everything for a reconnect`() {
        val focus = FocusArbitration()
        focus.onVideoFocus(VideoFocusMode.VIDEO_FOCUS_PROJECTED)
        focus.onAudioFocus(AudioFocusState.AUDIO_FOCUS_STATE_GAIN)
        focus.onNavigationFocus(NavigationFocusType.NAV_FOCUS_PROJECTED)

        focus.reset()

        assertEquals(VideoFocus.NONE, focus.videoFocus)
        assertFalse(focus.inputAllowed)
        assertFalse(focus.shouldStreamVideo)
        assertFalse(focus.mayPlayMedia)
        assertFalse(focus.navigationProjected)
    }
}
