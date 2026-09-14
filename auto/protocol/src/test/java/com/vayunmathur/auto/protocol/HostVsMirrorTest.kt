package com.vayunmathur.auto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins host readiness for the nav card: hosting needs all three facts
 * (maps service, projection role, host library); anything less shows the
 * launch tile. No mirror path exists to fall back to.
 */
class HostVsMirrorTest {

    private val readyHost = HostFacts(
        mapsServicePresent = true,
        holdsProjectionRole = true,
        hostLibraryPresent = true,
    )

    @Test
    fun `host is ready when every fact holds`() {
        assertTrue(HostReadiness.ready(readyHost))
    }

    @Test
    fun `missing any one host fact means not ready`() {
        for (missing in listOf(
            readyHost.copy(mapsServicePresent = false),
            readyHost.copy(holdsProjectionRole = false),
            readyHost.copy(hostLibraryPresent = false),
        )) {
            assertFalse(HostReadiness.ready(missing))
        }
    }

    @Test
    fun `no host at all means not ready`() {
        val noHost = HostFacts(
            mapsServicePresent = false,
            holdsProjectionRole = false,
            hostLibraryPresent = false,
        )
        assertFalse(HostReadiness.ready(noHost))
    }
}
