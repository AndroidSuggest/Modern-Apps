package com.vayunmathur.auto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Transport priority and the display/role policy the pairing UI renders. */
class TransportPolicyTest {

    @Test
    fun `usb beats wireless beats loopback`() {
        assertEquals(
            TransportKind.USB,
            TransportSelector.select(usbReady = true, wirelessReady = true, devBuild = true),
        )
        assertEquals(
            TransportKind.WIRELESS,
            TransportSelector.select(usbReady = false, wirelessReady = true, devBuild = true),
        )
        assertEquals(
            TransportKind.TCP_LOOPBACK,
            TransportSelector.select(usbReady = false, wirelessReady = false, devBuild = true),
        )
    }

    @Test
    fun `release builds never fall back to loopback`() {
        assertNull(
            TransportSelector.select(usbReady = false, wirelessReady = false, devBuild = false),
        )
        // ...but a real transport still wins on release.
        assertEquals(
            TransportKind.WIRELESS,
            TransportSelector.select(usbReady = false, wirelessReady = true, devBuild = false),
        )
    }

    @Test
    fun `the display goes trusted only with the role`() {
        assertEquals(
            DisplayRouteKind.PRIVATE_VIRTUAL,
            DisplayRoutePolicy.routeFor(holdsProjectionRole = false),
        )
        assertEquals(
            DisplayRouteKind.TRUSTED,
            DisplayRoutePolicy.routeFor(holdsProjectionRole = true),
        )
    }

    @Test
    fun `capabilities follow the role`() {
        assertTrue(MaosRole.capabilities(holdsRole = false).isEmpty())
        assertEquals(
            MaosRole.Capability.entries.toSet(),
            MaosRole.capabilities(holdsRole = true),
        )
    }
}
