package org.schabi.newpipe.extractor.services.youtube.sabrng

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for #565: a fresh PO token starts a fresh attestation epoch.
 *
 * The issue log showed "attestation remained pending without media for 6 consecutive responses"
 * against a threshold of 3: the pending counter survived PO-token rotation, so the retried
 * token tripped the threshold on arrival. [YoutubeSabrSession.setPoToken] must reset the
 * counter.
 */
class YoutubeSabrSessionAttestationTest {

    private fun session(): YoutubeSabrSession {
        val info = YoutubeSabrInfo(
            videoId = "GZVfNMVYYRY",
            cpn = "test-cpn-0123456789",
            clientVersion = "1.02",
            visitorData = "test-visitor",
            serverAbrStreamingUrl = "https://redirector.googlevideo.com/videoplayback",
            videoPlaybackUstreamerConfig = null,
            formats = emptyList(),
        )
        return YoutubeSabrSession(info, null)
    }

    @Test
    fun `setPoToken resets consecutive attestation pending count`() {
        val session = session()
        val counter = YoutubeSabrSession::class.java.getDeclaredField(
            "consecutiveAttestationPendingResponses"
        ).apply { isAccessible = true }

        counter.setInt(session, 2)
        session.setPoToken(byteArrayOf(1, 2, 3))
        assertEquals(0, counter.getInt(session))
    }

    @Test
    fun `setPoToken with null still resets the counter`() {
        val session = session()
        val counter = YoutubeSabrSession::class.java.getDeclaredField(
            "consecutiveAttestationPendingResponses"
        ).apply { isAccessible = true }

        counter.setInt(session, 2)
        session.setPoToken(null)
        assertEquals(0, counter.getInt(session))
    }
}
