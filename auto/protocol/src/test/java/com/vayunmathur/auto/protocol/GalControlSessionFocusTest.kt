package com.vayunmathur.auto.protocol

import com.vayunmathur.auto.protocol.gal.AudioFocusNotification
import com.vayunmathur.auto.protocol.gal.AudioFocusRequestMessage
import com.vayunmathur.auto.protocol.gal.AudioFocusRequestType
import com.vayunmathur.auto.protocol.gal.AudioFocusState
import com.vayunmathur.auto.protocol.gal.NavigationFocusNotification
import com.vayunmathur.auto.protocol.gal.NavigationFocusType
import com.vayunmathur.auto.protocol.gal.VideoFocusIndication
import com.vayunmathur.auto.protocol.gal.VideoFocusMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The session half of focus arbitration: control notifications feed the state with no
 * reply on the wire, the video indication arrives via the sink, and the audio request
 * builder gives the audio sinks their control 0x18.
 */
class GalControlSessionFocusTest {

    private fun session(): GalControlSession = GalControlSession(
        GalCredential.serverEngine(TestTls.context()),
        deviceModel = "Pixel 8",
        deviceManufacturer = "Google",
    )

    @Test
    fun `audio focus notification feeds arbitration with no reply`() {
        val session = session()
        val generation = session.focus.generation

        val replies = session.onMessage(
            GalMessage.Control.AUDIO_FOCUS_NOTIFICATION,
            AudioFocusNotification.newBuilder()
                .setState(AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT)
                .build()
                .toByteArray(),
        )

        assertTrue(replies.isEmpty(), "notifications are observed, never answered")
        assertEquals(
            AudioFocusState.AUDIO_FOCUS_STATE_GAIN_TRANSIENT,
            session.focus.audioFocus,
        )
        assertTrue(session.focus.mayPlayMedia)
        assertTrue(session.focus.generation > generation)
    }

    @Test
    fun `navigation focus notification tracks projection`() {
        val session = session()

        session.onMessage(
            GalMessage.Control.NAVIGATION_FOCUS_NOTIFICATION,
            NavigationFocusNotification.newBuilder()
                .setFocus(NavigationFocusType.NAV_FOCUS_PROJECTED)
                .build()
                .toByteArray(),
        )

        assertTrue(session.focus.navigationProjected)
    }

    @Test
    fun `video focus indication gates the stream`() {
        val session = session()

        session.onVideoFocusIndication(
            VideoFocusIndication.newBuilder().setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED).build(),
        )
        assertTrue(session.focus.shouldStreamVideo)
        assertTrue(session.focus.inputAllowed)

        session.onVideoFocusIndication(
            VideoFocusIndication.newBuilder().setMode(VideoFocusMode.VIDEO_FOCUS_NATIVE).build(),
        )
        assertEquals(VideoFocus.NATIVE, session.focus.videoFocus)
        assertFalse(session.focus.shouldStreamVideo)
    }

    @Test
    fun `audio focus request builds an encrypted control 18`() {
        val out = session().requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT)

        assertEquals(GalMessage.Control.AUDIO_FOCUS_REQUEST, out.type)
        assertTrue(out.encrypted)
        assertEquals(
            AudioFocusRequestType.AUDIO_FOCUS_GAIN_TRANSIENT,
            AudioFocusRequestMessage.parseFrom(out.payload).request,
        )
    }
}
