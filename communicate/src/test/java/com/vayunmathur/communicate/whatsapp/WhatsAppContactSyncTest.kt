package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppContactSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * E.164 normalization used by the device-contact sync (Phase C 2f). Pure (libphonenumber only),
 * no Android runtime required.
 */
class WhatsAppContactSyncTest {

    @Test
    fun normalizesNationalUsNumberToE164() {
        assertEquals("+12024561111", WhatsAppContactSync.normalizeE164("(202) 456-1111", "US"))
        assertEquals("+12024561111", WhatsAppContactSync.normalizeE164("202-456-1111", "US"))
    }

    @Test
    fun keepsAlreadyInternationalNumber() {
        assertEquals("+442071838750", WhatsAppContactSync.normalizeE164("+44 20 7183 8750", "US"))
    }

    @Test
    fun rejectsGarbageAndTooShort() {
        assertNull(WhatsAppContactSync.normalizeE164("not a phone", "US"))
        assertNull(WhatsAppContactSync.normalizeE164("123", "US"))
    }
}
