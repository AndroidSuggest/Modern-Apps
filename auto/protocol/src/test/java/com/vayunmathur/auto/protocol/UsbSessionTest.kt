package com.vayunmathur.auto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The USB bring-up walked end to end: attach → permission → connected → detach,
 * plus the denial and open-failure branches the pairing UI must render.
 */
class UsbSessionTest {

    @Test
    fun `attach then grant connects`() {
        val session = UsbSession()
        assertEquals(UsbSessionState.DETACHED, session.state)

        session.onAttached("Android", "Android Auto")
        assertEquals(UsbSessionState.AWAITING_PERMISSION, session.state)
        assertEquals("Android Android Auto", session.accessoryLabel)

        session.onPermissionGranted()
        assertEquals(UsbSessionState.CONNECTED, session.state)
        assertNull(session.failure)
    }

    @Test
    fun `a persisted grant skips the permission round-trip`() {
        val session = UsbSession()

        session.onAttachedWithPermission("Android", "Android Auto", hasPermission = true)

        assertEquals(UsbSessionState.CONNECTED, session.state)
    }

    @Test
    fun `without a grant it waits on the user`() {
        val session = UsbSession()

        session.onAttachedWithPermission("Android", "Android Auto", hasPermission = false)

        assertEquals(UsbSessionState.AWAITING_PERMISSION, session.state)
    }

    @Test
    fun `denial is recorded and retryable without replug`() {
        val session = UsbSession()
        session.onAttached("Android", "Android Auto")

        session.onPermissionDenied()
        assertEquals(UsbSessionState.PERMISSION_DENIED, session.state)
        assertTrue(session.failure!!.contains("denied"))

        // Stray grants outside the permission window change nothing.
        session.onPermissionGranted()
        assertEquals(UsbSessionState.PERMISSION_DENIED, session.state)

        assertTrue(session.retryPermission())
        assertEquals(UsbSessionState.AWAITING_PERMISSION, session.state)
        assertNull(session.failure)
    }

    @Test
    fun `retry is refused outside a denial`() {
        val session = UsbSession()

        assertFalse(session.retryPermission())
        assertEquals(UsbSessionState.DETACHED, session.state)
    }

    @Test
    fun `open failure disconnects with a reason`() {
        val session = UsbSession()
        session.onAttached("Android", "Android Auto")
        session.onPermissionGranted()

        session.onOpenFailed("ParcelFileDescriptor was null")

        assertEquals(UsbSessionState.DISCONNECTED, session.state)
        assertEquals("ParcelFileDescriptor was null", session.failure)
    }

    @Test
    fun `detach clears the label and returns to detached`() {
        val session = UsbSession()
        session.onAttached("Android", "Android Auto")
        session.onPermissionGranted()

        session.onDetached()

        assertEquals(UsbSessionState.DETACHED, session.state)
        assertNull(session.accessoryLabel)
        assertNull(session.failure)
    }

    @Test
    fun `a blank accessory announcement still attaches`() {
        val session = UsbSession()

        session.onAttached(null, null)

        assertEquals(UsbSessionState.AWAITING_PERMISSION, session.state)
        assertNull(session.accessoryLabel)
    }
}
