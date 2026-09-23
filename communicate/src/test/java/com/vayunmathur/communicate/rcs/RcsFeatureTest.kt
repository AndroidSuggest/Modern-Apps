package com.vayunmathur.communicate.rcs

import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsUnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Gate + model tests for the RCS line. The dev gate mirrors WhatsApp/Signal:
 * release builds (DEV_BUILD=false) strip RCS via R8, so every read path
 * early-returns empty when the gate is off.
 */
class RcsFeatureTest {
    @Test
    fun lineEnumHasRcs() {
        assertTrue(CommunicateLine.entries.contains(CommunicateLine.Rcs))
    }

    @Test
    fun lineChoiceRcsMapsToRcsCategory() {
        assertEquals(CommunicateLine.Rcs, LineChoice.Rcs.category)
        assertEquals("RCS", LineChoice.Rcs.label)
    }

    @Test
    fun unavailableCarriesReason() {
        val state: RcsRegistrationState =
            RcsRegistrationState.Unavailable(RcsUnavailableReason.NoEntitlementUrl)
        assertIs<RcsRegistrationState.Unavailable>(state)
        assertEquals(RcsUnavailableReason.NoEntitlementUrl, state.reason)
    }

    @Test
    fun allUnavailableReasonsExist() {
        assertEquals(9, RcsUnavailableReason.entries.size)
    }

    @Test
    fun gateReflectsBuildConfig() {
        // DEV_BUILD is true for dev/debug, false for release. The test runs
        // against the dev variant, so the gate is on here.
        assertEquals(com.vayunmathur.communicate.BuildConfig.DEV_BUILD, RcsFeature.enabled)
    }
}
