package com.vayunmathur.auto.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wireless bring-up walked end to end: Bluetooth association → WiFi
 * negotiation → socket, plus the negotiation-failure and link-loss branches.
 */
class WirelessSessionTest {

    private val params = WirelessLinkParams(host = "192.168.49.1", port = 5277)

    @Test
    fun `association then negotiation then socket connects`() {
        val session = WirelessSession()
        assertEquals(WirelessSessionState.IDLE, session.state)

        session.onBluetoothAssociated()
        assertEquals(WirelessSessionState.NEGOTIATING_WIFI, session.state)

        session.onWifiNegotiated(params)
        assertEquals(WirelessSessionState.WIFI_READY, session.state)
        assertEquals(params, session.linkParams)

        session.onSocketConnected()
        assertEquals(WirelessSessionState.CONNECTED, session.state)
        assertNull(session.failure)
    }

    @Test
    fun `negotiation failure disconnects with a reason`() {
        val session = WirelessSession()
        session.onBluetoothAssociated()

        session.onWifiFailed("group formation timed out")

        assertEquals(WirelessSessionState.DISCONNECTED, session.state)
        assertEquals("group formation timed out", session.failure)
        assertNull(session.linkParams)
    }

    @Test
    fun `link loss disconnects with a reason`() {
        val session = WirelessSession()
        session.onBluetoothAssociated()
        session.onWifiNegotiated(params)
        session.onSocketConnected()

        session.onDisconnected("socket refused")

        assertEquals(WirelessSessionState.DISCONNECTED, session.state)
        assertTrue(session.failure!!.contains("socket refused"))
    }

    @Test
    fun `out-of-order events are ignored`() {
        val session = WirelessSession()

        // Socket and WiFi answers with no negotiation in flight change nothing.
        session.onSocketConnected()
        session.onWifiNegotiated(params)
        session.onWifiFailed("late")
        assertEquals(WirelessSessionState.IDLE, session.state)
        assertNull(session.linkParams)
        assertNull(session.failure)

        // A second association while negotiating does not reset the attempt.
        session.onBluetoothAssociated()
        session.onBluetoothAssociated()
        assertEquals(WirelessSessionState.NEGOTIATING_WIFI, session.state)
    }

    @Test
    fun `reset forgets the link and returns to idle`() {
        val session = WirelessSession()
        session.onBluetoothAssociated()
        session.onWifiNegotiated(params)
        session.onSocketConnected()

        session.reset()

        assertEquals(WirelessSessionState.IDLE, session.state)
        assertNull(session.linkParams)
        assertNull(session.failure)
    }

    @Test
    fun `association after a drop starts a fresh attempt`() {
        val session = WirelessSession()
        session.onBluetoothAssociated()
        session.onWifiFailed("timeout")

        session.onBluetoothAssociated()

        assertEquals(WirelessSessionState.NEGOTIATING_WIFI, session.state)
        assertNull(session.failure)
    }
}
