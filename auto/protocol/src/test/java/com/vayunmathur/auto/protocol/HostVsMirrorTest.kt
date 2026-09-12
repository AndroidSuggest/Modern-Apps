package com.vayunmathur.auto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the host-vs-mirror default (Phase 6, maps-dev): mirror wins unless
 * the host is fully ready AND the mirror cannot draw at all.
 */
class HostVsMirrorTest {

    private val readyHost = HostFacts(
        mapsServicePresent = true,
        holdsProjectionRole = true,
        hostLibraryPresent = true,
    )
    private val fullMirror = MirrorFacts(
        rendererPresent = true,
        locationAvailable = true,
        routeActive = true,
    )

    @Test
    fun `mirror wins when it can draw, even with a ready host`() {
        assertEquals(MapsPath.MIRROR, HostVsMirror.recommend(readyHost, fullMirror))
    }

    @Test
    fun `mirror wins with no host at all`() {
        val noHost = HostFacts(
            mapsServicePresent = false,
            holdsProjectionRole = false,
            hostLibraryPresent = false,
        )
        assertEquals(MapsPath.MIRROR, HostVsMirror.recommend(noHost, fullMirror))
    }

    @Test
    fun `host wins only when ready and the mirror has no renderer and no location`() {
        val deadMirror = MirrorFacts(
            rendererPresent = false,
            locationAvailable = false,
            routeActive = false,
        )
        assertEquals(MapsPath.HOST, HostVsMirror.recommend(readyHost, deadMirror))
    }

    @Test
    fun `missing any one host fact keeps the mirror`() {
        val deadMirror = MirrorFacts(
            rendererPresent = false,
            locationAvailable = false,
            routeActive = false,
        )
        for (missing in listOf(
            readyHost.copy(mapsServicePresent = false),
            readyHost.copy(holdsProjectionRole = false),
            readyHost.copy(hostLibraryPresent = false),
        )) {
            assertEquals(MapsPath.MIRROR, HostVsMirror.recommend(missing, deadMirror))
        }
    }

    @Test
    fun `location alone keeps the mirror - last-known still draws a map`() {
        val noRendererButLocated = MirrorFacts(
            rendererPresent = false,
            locationAvailable = true,
            routeActive = false,
        )
        assertEquals(MapsPath.MIRROR, HostVsMirror.recommend(readyHost, noRendererButLocated))
    }

    @Test
    fun `route state never flips the decision`() {
        val idle = MirrorFacts(
            rendererPresent = true,
            locationAvailable = true,
            routeActive = false,
        )
        assertEquals(MapsPath.MIRROR, HostVsMirror.recommend(readyHost, idle))
    }
}
