package com.vayunmathur.auto.protocol

import com.google.protobuf.ByteString
import com.vayunmathur.auto.protocol.gal.Service
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the GAL 11/12 gap: the media-browser and playback-status services stay
 * opaque and unanswered, with now-playing riding the ch2 video stream instead.
 */
class MediaGalGapTest {

    @Test
    fun `channels 11 and 12 are media-gap channels`() {
        assertTrue(isMediaBrowserChannel(GalService.MEDIA_PLAYBACK_STATUS.id))
        assertTrue(isMediaBrowserChannel(GalService.MEDIA_BROWSER.id))
    }

    @Test
    fun `real channels are not media-gap channels`() {
        assertFalse(isMediaBrowserChannel(GalService.CONTROL.id))
        assertFalse(isMediaBrowserChannel(GalService.VIDEO_SINK.id))
        assertFalse(isMediaBrowserChannel(GalService.INPUT_SOURCE.id))
    }

    @Test
    fun `media browser descriptor stays opaque bytes`() {
        val parsed = Service.parseFrom(
            Service.newBuilder()
                .setId(GalService.MEDIA_BROWSER.id)
                .setMediaBrowser(ByteString.copyFromUtf8("xki"))
                .build()
                .toByteArray(),
        )
        assertTrue(parsed.hasMediaBrowser())
        assertEquals("xki", parsed.mediaBrowser.toStringUtf8())
    }

    @Test
    fun `media playback status descriptor stays opaque bytes`() {
        val parsed = Service.parseFrom(
            Service.newBuilder()
                .setId(GalService.MEDIA_PLAYBACK_STATUS.id)
                .setMediaPlaybackStatus(ByteString.copyFromUtf8("xkn"))
                .build()
                .toByteArray(),
        )
        assertTrue(parsed.hasMediaPlaybackStatus())
        assertEquals("xkn", parsed.mediaPlaybackStatus.toStringUtf8())
    }
}
